package me.rerere.rikkahub.data.ai.agentevent

import me.rerere.rikkahub.data.db.dao.AgentEventDAO
import me.rerere.rikkahub.data.db.entity.AgentEventEntity
import me.rerere.rikkahub.data.db.entity.AgentEventStatus
import me.rerere.rikkahub.data.repository.BoardTransactionRunner
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

/**
 * The narrow persistence seam the agent-event drain depends on (DIP, mirroring `TaskRunStore`). The
 * concrete is [RoomAgentEventStore] (Room-backed), bound at the composition root; tests inject an
 * in-memory fake DAO behind the same store so the property suite pins THIS store's own
 * claim/append/consume logic rather than SQLite's.
 *
 * Persistence ONLY (issue #290 ownership rule): the store never touches `ChatTurnRuntime`,
 * `TaskCoordinator`, or `ExecutionHandleRegistry`. It exposes exactly the queue operations
 * `ChatService` drives — enqueue, the one-transaction claim-and-deliver, the replay scan, and the
 * conversation-delete cleanup — no wider.
 */
interface AgentEventStore {
    /**
     * Persist a PENDING event with the next FIFO sequence. Idempotent: a duplicate [dedupeKey] is an
     * insert-ignore no-op (AT_MOST_ONCE's enqueue half). Returns true when a new row was created,
     * false when the dedupe key already existed.
     */
    suspend fun enqueue(
        conversationId: Uuid,
        kind: String,
        payloadJson: String,
        dedupeKey: String,
    ): Boolean

    /**
     * Claim the oldest PENDING event for [conversationId], let the caller append its visible
     * synthetic message, and mark it CONSUMED — all in ONE transaction. This single transaction is
     * what encodes AT_MOST_ONCE: a second drain or a startup replay racing the same row either
     * observes it already CONSUMED (returns [ClaimOutcome.Empty]) or loses the conditional
     * `markConsumed` claim (returns [ClaimOutcome.Lost]) and the [append] side effect is rolled
     * back with the transaction.
     *
     * [append] receives the claimed event and must return the ids of the synthetic node/message it
     * persisted; those ids are stamped onto the consumed row.
     */
    suspend fun claimAndAppendAndConsume(
        conversationId: Uuid,
        append: suspend (AgentEventEntity) -> ClaimAppendAction,
    ): ClaimOutcome

    /** The PENDING events for a conversation, oldest first (replay/inspection scan). */
    suspend fun listPending(conversationId: Uuid): List<AgentEventEntity>

    /** Conversations that still hold at least one PENDING event (startup replay scan). */
    suspend fun conversationsWithPending(): List<Uuid>

    /** Cleanup hook for a deleted conversation (productDecision #6: explicit delete, no FK cascade). */
    suspend fun deleteByConversationId(conversationId: Uuid): Int
}

/** The ids of the synthetic message an [AgentEventStore.claimAndAppendAndConsume] append persisted. */
data class SyntheticAppendResult(
    val syntheticNodeId: String,
    val syntheticMessageId: String,
)

/** Terminalization outcome for a pending event. */
enum class AgentEventTerminalStatus {
    CANCELLED,
    FAILED,
}

private class ClaimLostWithinTransactionException : RuntimeException()

/** What the store should do with a claimed pending event. */
sealed interface ClaimAppendAction {
    data class Append(
        val synthetic: SyntheticAppendResult,
        val continueGeneration: Boolean = true,
    ) : ClaimAppendAction

    data class Terminalize(val terminalStatus: AgentEventTerminalStatus) : ClaimAppendAction
}

/** The outcome of one [AgentEventStore.claimAndAppendAndConsume] attempt. */
sealed interface ClaimOutcome {
    /** No PENDING event to claim — an empty-queue drain is a no-op. */
    data object Empty : ClaimOutcome

    /**
     * This caller won the claim and delivered [event]; the synthetic message ids are recorded.
     */
    data class Delivered(
        val event: AgentEventEntity,
        val synthetic: SyntheticAppendResult,
        val continueGeneration: Boolean = true,
    ) : ClaimOutcome

    /**
     * A concurrent drain consumed the claimed row first (the conditional `markConsumed` matched
     * zero rows). The append was rolled back with the transaction; nothing was delivered.
     */
    data object Lost : ClaimOutcome

    data class Terminalized(
        val event: AgentEventEntity,
        val terminalStatus: AgentEventTerminalStatus,
    ) : ClaimOutcome
}

/**
 * Room-backed [AgentEventStore]. Every operation runs inside one [BoardTransactionRunner]
 * transaction (the same one-writer-at-a-time runner the board/task repositories use), so the
 * read-modify-write of a claim is atomic against a concurrent drain or replay.
 */
class RoomAgentEventStore(
    private val dao: AgentEventDAO,
    private val transactions: BoardTransactionRunner,
    private val now: () -> Long = System::currentTimeMillis,
) : AgentEventStore {

    override suspend fun enqueue(
        conversationId: Uuid,
        kind: String,
        payloadJson: String,
        dedupeKey: String,
    ): Boolean = transactions.inTransaction {
        val cid = conversationId.toString()
        val seq = dao.nextEnqueueSeq(cid)
        val event = AgentEventEntity(
            id = Uuid.random().toString(),
            conversationId = cid,
            dedupeKey = dedupeKey,
            enqueueSeq = seq,
            kind = kind,
            payloadJson = payloadJson,
            status = AgentEventStatus.PENDING.name,
            createdAt = now(),
        )
        dao.insertIgnore(event)
        // insertIgnore reports nothing on a dedupe collision; re-read by dedupe identity via the
        // freshly-inserted id. A new row exists iff the row we just tried to insert is the one in
        // the table (same id). On collision the existing row keeps its older id, so getById(ours)
        // is null.
        dao.getById(event.id) != null
    }

    override suspend fun claimAndAppendAndConsume(
        conversationId: Uuid,
        append: suspend (AgentEventEntity) -> ClaimAppendAction,
    ): ClaimOutcome = try {
        transactions.inTransaction {
            val claimed = dao.oldestPending(conversationId.toString())
                ?: return@inTransaction ClaimOutcome.Empty
            val appendAction = try {
                append(claimed)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                ClaimAppendAction.Terminalize(AgentEventTerminalStatus.FAILED)
            }
            when (appendAction) {
                is ClaimAppendAction.Append -> {
                    val won = dao.markConsumed(
                        id = claimed.id,
                        syntheticNodeId = appendAction.synthetic.syntheticNodeId,
                        syntheticMessageId = appendAction.synthetic.syntheticMessageId,
                        consumedAt = now(),
                    )
                    if (won == 1) {
                        ClaimOutcome.Delivered(
                            event = claimed,
                            synthetic = appendAction.synthetic,
                            continueGeneration = appendAction.continueGeneration,
                        )
                    } else {
                        throw ClaimLostWithinTransactionException()
                    }
                }

                is ClaimAppendAction.Terminalize -> {
                    val won = when (appendAction.terminalStatus) {
                        AgentEventTerminalStatus.CANCELLED -> dao.markCancelled(
                            id = claimed.id,
                            cancelledAt = now(),
                        )

                        AgentEventTerminalStatus.FAILED -> dao.markFailed(
                            id = claimed.id,
                            cancelledAt = now(),
                        )
                    }
                    if (won == 1) ClaimOutcome.Terminalized(claimed, appendAction.terminalStatus) else ClaimOutcome.Lost
                }
            }
        }
    } catch (_: ClaimLostWithinTransactionException) {
        ClaimOutcome.Lost
    }

    override suspend fun listPending(conversationId: Uuid): List<AgentEventEntity> =
        transactions.inTransaction { dao.listPending(conversationId.toString()) }

    override suspend fun conversationsWithPending(): List<Uuid> =
        transactions.inTransaction { dao.conversationsWithPending().map { Uuid.parse(it) } }

    override suspend fun deleteByConversationId(conversationId: Uuid): Int =
        transactions.inTransaction { dao.deleteByConversationId(conversationId.toString()) }
}
