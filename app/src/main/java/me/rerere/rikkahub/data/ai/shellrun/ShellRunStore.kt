package me.rerere.rikkahub.data.ai.shellrun

import me.rerere.rikkahub.data.db.dao.AgentEventDAO
import me.rerere.rikkahub.data.db.dao.ShellRunDAO
import me.rerere.rikkahub.data.db.entity.ShellRunEntity
import me.rerere.rikkahub.data.db.entity.ShellRunStatus
import me.rerere.rikkahub.data.repository.BoardTransactionRunner
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * The narrow persistence seam the shell-run coordinator depends on (DIP, mirroring `AgentEventStore`
 * / `TaskRunStore`). The concrete is [RoomShellRunStore]; tests inject an in-memory fake DAO behind
 * the same store so the property suite pins THIS store's own claim logic rather than SQLite's.
 *
 * Persistence ONLY (issue #291 ownership rule): the store never touches `ChatService`,
 * `WorkspaceManager`, or a live `ShellRunHandle`. It exposes exactly the durable operations the
 * coordinator + cold-start recovery drive — create, the state flips, the at-most-once terminal CAS,
 * the recovery scan, and the conversation-delete cleanup — no wider.
 */
interface ShellRunStore {
    /** Persist a STARTED run. */
    suspend fun create(
        taskId: Uuid,
        conversationId: Uuid,
        workspaceId: String,
        command: String,
        cwd: String,
        outputPath: String,
    )

    /** STARTED -> FOREGROUND_WAITING once the handle is held. */
    suspend fun markForegroundWaiting(taskId: Uuid, pidMeta: String?)

    /**
     * FOREGROUND_WAITING (or STARTED) -> DETACHED under the terminal CAS's non-terminal guard.
     * Returns true when this caller drove the real flip; false when the row was already detached or
     * terminal (so the caller does not double-launch the detached awaiter).
     */
    suspend fun detach(taskId: Uuid, pidMeta: String?): Boolean

    /** DETACHED -> BACKGROUND_RUNNING once the detached awaiter is running. */
    suspend fun markBackgroundRunning(taskId: Uuid)

    /**
     * The SINGLE_TERMINAL write: flip a still-running row to a terminal [status] in ONE transaction.
     * Returns a [TerminalResult] whose [TerminalResult.outcome] is [TerminalOutcome.Won] for the
     * caller that actually drove the terminal, [TerminalOutcome.Lost] for a racing caller that found
     * the row already terminal. [TerminalResult.wasDetached] reports whether the row was DETACHED /
     * BACKGROUND_RUNNING when the terminal landed — the ATOMIC arbiter of whether a completion event
     * is owed (the agent already received a `Detached` handle), read inside the SAME transaction so it
     * never races the inline-vs-detach decision.
     *
     * [buildCompletion] is invoked LAZILY — only when this write wins the CAS for a detached run — so
     * building the completion (which reads the run's output tail off disk and can throw) never runs on
     * the inline path. The built completion is inserted as the durable #290 event in THIS transaction
     * and returned in [TerminalResult.completion] so the caller delivers exactly the row it persisted.
     */
    suspend fun recordTerminal(
        taskId: Uuid,
        status: ShellRunStatus,
        exitCode: Int?,
        byteCount: Long,
        killReason: String?,
        buildCompletion: (() -> ShellCompletion)? = null,
    ): TerminalResult

    /**
     * Cold-start recovery (PROCESS_DEATH_IS_INTERRUPTED): in one transaction, scan every running row
     * and fold it to [ShellRunStatus.INTERRUPTED_PROCESS_DEATH] via the same terminal CAS — never a
     * fabricated SUCCEEDED. Returns the rows that were actually recovered (won the CAS), so the caller
     * can enqueue one honest interrupted event per row.
     */
    suspend fun recoverInterrupted(): List<ShellRunEntity>

    /**
     * Scoped row lookup for the `workspace_shell_tail` seam: the tail tool must verify a
     * model-controlled taskId resolves to a row that belongs to the calling workspace AND conversation
     * before reading its output file. Returns null for a missing/deleted row.
     */
    suspend fun getByTaskId(taskId: Uuid): ShellRunEntity?

    /** Attach the persisted detached tool anchor after the transcript node/message has been saved. */
    suspend fun attachToolAnchor(taskId: Uuid, anchor: ShellRunToolAnchor): Boolean

    /** Read the persisted detached tool anchor by task id, if one was attached. */
    suspend fun getToolAnchor(taskId: Uuid): ShellRunToolAnchor?

    /** Cleanup hook for a deleted conversation (explicit delete, no FK cascade). */
    suspend fun deleteByConversationId(conversationId: Uuid): Int

    /** Observe genuinely-backgrounded, non-terminal shell jobs for one conversation. */
    fun observeBackgroundJobs(conversationId: Uuid): Flow<List<ShellRunEntity>>
}

data class ShellRunToolAnchor(
    val toolCallId: String,
    val toolNodeId: Uuid,
    val toolMessageId: Uuid,
)

/** The outcome of a [ShellRunStore.recordTerminal] attempt (the SINGLE_TERMINAL race result). */
enum class TerminalOutcome {
    /** This caller drove the terminal write; it is responsible for the (single) completion event. */
    Won,

    /** A concurrent caller already terminalised the row; this attempt is a no-op. */
    Lost,
}

/**
 * A [ShellRunStore.recordTerminal] result. [wasDetached] is read in the SAME transaction as the CAS,
 * so "did the agent already receive a Detached handle (and is therefore owed a completion)?" can
 * never disagree with "did this write win the terminal" — the boundary race the coordinator's
 * inline-vs-detach decision would otherwise have.
 */
data class TerminalResult(
    val outcome: TerminalOutcome,
    val wasDetached: Boolean,
    /**
     * The completion the store built AND persisted as the durable #290 event in the terminal
     * transaction — non-null IFF this write won the CAS for a detached run and a [buildCompletion]
     * was supplied. The caller delivers exactly this row (a post-commit drain trigger); a CAS loss or
     * an inline run leaves it null and nothing is delivered.
     */
    val completion: ShellCompletion? = null,
)

/**
 * Room-backed [ShellRunStore]. Every operation runs inside one [BoardTransactionRunner] transaction
 * (the same one-writer-at-a-time runner the board/task/agent-event stores use), so the terminal CAS
 * is atomic against a concurrent detached-awaiter exit or a cold-start recovery scan — exactly the
 * posture of `RoomAgentEventStore`.
 */
class RoomShellRunStore(
    private val dao: ShellRunDAO,
    private val agentEventDao: AgentEventDAO,
    private val transactions: BoardTransactionRunner,
    private val now: () -> Long = System::currentTimeMillis,
) : ShellRunStore {

    override suspend fun create(
        taskId: Uuid,
        conversationId: Uuid,
        workspaceId: String,
        command: String,
        cwd: String,
        outputPath: String,
    ) = transactions.inTransaction {
        dao.insert(
            ShellRunEntity(
                taskId = taskId.toString(),
                conversationId = conversationId.toString(),
                workspaceId = workspaceId,
                command = command,
                cwd = cwd,
                outputPath = outputPath,
                status = ShellRunStatus.STARTED.name,
                createdAt = now(),
            )
        )
    }

    override suspend fun markForegroundWaiting(taskId: Uuid, pidMeta: String?) {
        transactions.inTransaction {
            dao.markForegroundWaiting(taskId.toString(), now(), pidMeta)
        }
    }

    override suspend fun detach(taskId: Uuid, pidMeta: String?): Boolean =
        transactions.inTransaction {
            dao.markDetached(taskId.toString(), now(), pidMeta) == 1
        }

    override suspend fun markBackgroundRunning(taskId: Uuid) {
        transactions.inTransaction {
            dao.markBackgroundRunning(taskId.toString())
        }
    }

    override suspend fun recordTerminal(
        taskId: Uuid,
        status: ShellRunStatus,
        exitCode: Int?,
        byteCount: Long,
        killReason: String?,
        buildCompletion: (() -> ShellCompletion)?,
    ): TerminalResult = transactions.inTransaction {
        // Read the prior status in the same transaction as the CAS, so "was this run detached (and is
        // owed a completion)" is decided atomically with "did this write win the terminal".
        val priorStatus = dao.getById(taskId.toString())?.let { ShellRunStatus.fromPersistedOrNull(it.status) }
        val wasDetached = priorStatus == ShellRunStatus.DETACHED ||
            priorStatus == ShellRunStatus.BACKGROUND_RUNNING
        val won = dao.markTerminalIfRunning(
            taskId = taskId.toString(),
            status = status.name,
            exitCode = exitCode,
            byteCount = byteCount,
            killReason = killReason,
            completedAt = now(),
        )
        // Build the completion ONLY for the detached CAS winner: building it reads the output tail off
        // disk (can throw) and is meaningless for an inline run or a CAS loser. An exception here rolls
        // back the terminal CAS too (atomic) — a detached run then stays running and is recovered honest
        // on the next cold start, never half-terminalised with a lost completion.
        val completion = if (won == 1 && wasDetached) buildCompletion?.invoke() else null
        if (completion != null) {
            val event = completion.asPendingAgentEventEntity(
                eventId = Uuid.random().toString(),
                conversationId = completion.conversationId.toString(),
                enqueueSeq = agentEventDao.nextEnqueueSeq(completion.conversationId.toString()),
                createdAt = now(),
            )
            agentEventDao.insertIgnore(event)
        }
        TerminalResult(
            outcome = if (won == 1) TerminalOutcome.Won else TerminalOutcome.Lost,
            wasDetached = wasDetached,
            completion = completion,
        )
    }

    override suspend fun recoverInterrupted(): List<ShellRunEntity> = transactions.inTransaction {
        val running = dao.runningRows()
        running.mapNotNull { row ->
            val won = dao.markTerminalIfRunning(
                taskId = row.taskId,
                status = ShellRunStatus.INTERRUPTED_PROCESS_DEATH.name,
                exitCode = null,
                byteCount = row.byteCount,
                killReason = null,
                completedAt = now(),
            )
            if (won == 1) row else null
        }
    }

    override suspend fun getByTaskId(taskId: Uuid): ShellRunEntity? =
        transactions.inTransaction { dao.getById(taskId.toString()) }

    override suspend fun attachToolAnchor(taskId: Uuid, anchor: ShellRunToolAnchor): Boolean =
        transactions.inTransaction {
            dao.attachToolAnchor(
                taskId = taskId.toString(),
                toolCallId = anchor.toolCallId,
                toolNodeId = anchor.toolNodeId.toString(),
                toolMessageId = anchor.toolMessageId.toString(),
            ) == 1
        }

    override suspend fun getToolAnchor(taskId: Uuid): ShellRunToolAnchor? =
        transactions.inTransaction {
            dao.getAnchoredByTaskId(taskId.toString())?.let { row ->
                val toolCallId = row.toolCallId ?: return@let null
                val toolNodeId = row.toolNodeId ?: return@let null
                val toolMessageId = row.toolMessageId ?: return@let null
                ShellRunToolAnchor(
                    toolCallId = toolCallId,
                    toolNodeId = Uuid.parse(toolNodeId),
                    toolMessageId = Uuid.parse(toolMessageId),
                )
            }
        }

    override suspend fun deleteByConversationId(conversationId: Uuid): Int =
        transactions.inTransaction { dao.deleteByConversationId(conversationId.toString()) }

    override fun observeBackgroundJobs(conversationId: Uuid): Flow<List<ShellRunEntity>> =
        dao.observeBackgroundJobs(conversationId.toString())
}
