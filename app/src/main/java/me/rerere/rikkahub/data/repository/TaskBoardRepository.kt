package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import me.rerere.ai.runtime.board.BoardDependencyEdge
import me.rerere.ai.runtime.board.BoardEdgeInsertResult
import me.rerere.ai.runtime.board.BoardGraph
import me.rerere.ai.runtime.board.WorkItem
import me.rerere.ai.runtime.board.WorkItemAction
import me.rerere.ai.runtime.board.WorkItemStatus
import me.rerere.ai.runtime.board.WorkItemTransitionResult
import me.rerere.ai.runtime.board.WorkItemTransitionValidator
import me.rerere.ai.runtime.contract.BoardItemSnapshot
import me.rerere.ai.runtime.contract.BoardMutationResult
import me.rerere.ai.runtime.contract.WorkItemDraft
import me.rerere.ai.runtime.contract.WorkItemPatch
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.WorkItemDAO
import me.rerere.rikkahub.data.db.entity.WorkItemDependencyEntity
import me.rerere.rikkahub.data.db.entity.WorkItemEntity
import kotlin.uuid.Uuid

/**
 * Atomicity seam between the repository and Room: `RoomDatabase.withTransaction` needs an
 * Android SQLite stack, so JVM repository tests substitute a mutex-serialized runner with the
 * same one-writer-at-a-time semantics. Every repository operation runs inside exactly one
 * transaction — that is what makes claims atomic and concurrent runs sequentially equivalent.
 */
interface BoardTransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/** Production binding: one Room transaction per repository operation. */
class RoomBoardTransactionRunner(private val database: AppDatabase) : BoardTransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction(block)
}

/**
 * Who performs a board mutation — the execution handle of a spawned subagent or the user's UI
 * session. Required for [WorkItemAction.Claim] (a claim without an owner is meaningless); other
 * actions ignore it (decision #4: the board is user-editable, so completing/releasing someone
 * else's item is allowed).
 */
data class BoardActor(
    val handleId: String,
    val displayName: String? = null,
)

/**
 * The SINGLE enforcement point for every work-item board invariant (SPEC.md M2, maintainer
 * decision #4): board tools and the board UI both mutate through this repository, so legality
 * never depends on the caller. Each public method is ONE transaction via [BoardTransactionRunner],
 * and validates everything BEFORE writing anything — no partial writes to roll back.
 *
 * Enforced here, nowhere else:
 * - Legal status transitions: every status change is an explicit [WorkItemAction] judged by
 *   [WorkItemTransitionValidator] (TerminalStatusMonotonicity by construction).
 * - SingleOwnerClaim: a claim reads item + unresolved blockers + owner + lease and sets
 *   `ownerHandleId`/`status` in the same transaction, so concurrent claims serialize and
 *   exactly one wins. One owner may hold many claims (decision #5).
 * - Lease backstop: an `InProgress` item whose lease has expired is implicitly released
 *   (a legal `Release` transition) before the new claim applies — dead handles cannot pin
 *   items forever even if orphan recovery never runs.
 * - NoDependencyCycles: edge inserts replay the persisted edges through [BoardGraph] and are
 *   rejected when the new edge would close a cycle.
 * - DeleteUnblocksDependents: delete removes every edge touching the item, in the same
 *   transaction as the status change.
 *
 * Domain rejections return [BoardMutationResult.Rejected] (expected outcomes the caller
 * surfaces); corrupt persisted rows throw, because masking them as rejections or fresh items
 * would silently double-assign work.
 */
class TaskBoardRepository(
    private val dao: WorkItemDAO,
    private val transactions: BoardTransactionRunner,
    private val now: () -> Long = System::currentTimeMillis,
    private val claimLeaseMillis: Long = DEFAULT_CLAIM_LEASE_MILLIS,
) {
    suspend fun create(conversationId: Uuid, draft: WorkItemDraft): BoardMutationResult =
        transactions.inTransaction {
            val blockers = draft.blockedBy.distinct()
            blockerRejection(conversationId, blockers)?.let { return@inTransaction it }

            val id = Uuid.random()
            val newEdges = blockers.map { BoardDependencyEdge(blockerId = it, blockedId = id) }
            // A fresh node cannot close a cycle, but the uniform path also surfaces corrupt
            // persisted edges instead of building on them.
            cycleRejection(conversationId, newEdges)?.let { return@inTransaction it }

            val timestamp = now()
            val item = WorkItem(
                id = id,
                conversationId = conversationId,
                subject = draft.subject,
                description = draft.description,
                activeForm = draft.activeForm,
            )
            dao.insert(WorkItemEntity.fromWorkItem(item, createdAt = timestamp, updatedAt = timestamp))
            newEdges.forEach { dao.insertDependency(it.toEntity(conversationId)) }
            BoardMutationResult.Accepted(BoardItemSnapshot(item, blockers))
        }

    /**
     * Orphan recovery (SPEC.md M6, maintainer decision #5): release EVERY board claim owned by a
     * dead execution handle, regardless of lease. The lease is only a backstop for a handle that
     * dies without recovery ever running; when recovery DOES run it must release all of the dead
     * handle's claims explicitly. Returns the number of items released.
     *
     * ONE transaction for the scan AND every release. A scan-then-update-per-item shape had a
     * TOCTOU hole: an item released and RECLAIMED by a live handle between the scan and its
     * update would have the live owner cleared by the dead handle's cleanup. Inside a single
     * transaction the owner read and the release write are atomic, so only rows still owned by
     * [handleId] can be touched. Each release is still the validator's call
     * ([WorkItemAction.Release] from `InProgress`; anything else is skipped, never force-written)
     * — legality stays the single repository enforcement point (decision #4).
     */
    suspend fun releaseClaimsOf(handleId: String): Int = transactions.inTransaction {
        var released = 0
        for (entity in dao.listByOwner(handleId)) {
            val item = entity.toDomain()
            if (item.status != WorkItemStatus.InProgress) continue
            val to = validatedTarget(item.status, WorkItemAction.Release) ?: continue
            dao.update(
                WorkItemEntity.fromWorkItem(
                    item = item.copy(status = to, ownerHandleId = null, ownerName = null),
                    createdAt = entity.createdAt,
                    updatedAt = now(),
                    metadataJson = entity.metadataJson,
                    leaseExpiresAt = null,
                )
            )
            released++
        }
        released
    }

    /**
     * Retention sweep (SPEC.md M6 + the "Unbounded board" Failure-mode): delete COMPLETED and
     * DELETED work items that are both older than [maxAgeMillis] and beyond the newest
     * [keepNewestPerConversation] of their conversation, removing their dependency edges in the
     * same transaction so no dangling edge survives. Open items (Pending/InProgress) are kept
     * indefinitely — only the panel's done/deleted history is bounded. Returns items deleted.
     *
     * The windowing decision is the shared pure [selectExpiredForRetention]; this method feeds it
     * the retained rows and applies the cascade deletion atomically.
     */
    suspend fun sweepRetention(
        now: Long = now(),
        maxAgeMillis: Long = DEFAULT_RETENTION_MAX_AGE_MILLIS,
        keepNewestPerConversation: Int = DEFAULT_RETENTION_KEEP_NEWEST,
    ): Int = transactions.inTransaction {
        val retainedStatuses = setOf(WorkItemStatus.Completed.name, WorkItemStatus.Deleted.name)
        val candidates = dao.listRetainable(retainedStatuses)
        val expired = selectExpiredForRetention(
            rows = candidates,
            now = now,
            maxAgeMillis = maxAgeMillis,
            keepNewestPerConversation = keepNewestPerConversation,
            conversationOf = { it.conversationId },
            updatedAtOf = { it.updatedAt },
            idOf = { it.id },
        )
        if (expired.isEmpty()) return@inTransaction 0
        dao.deleteDependenciesTouchingAny(expired)
        dao.deleteByIds(expired)
    }

    suspend fun get(conversationId: Uuid, id: Uuid): BoardItemSnapshot? = transactions.inTransaction {
        val entity = dao.getById(id.toString())
            ?.takeIf { it.conversationId == conversationId.toString() }
            ?: return@inTransaction null
        BoardItemSnapshot(entity.toDomain(), blockersOf(conversationId, id))
    }

    suspend fun list(
        conversationId: Uuid,
        statuses: Set<WorkItemStatus>? = null,
    ): List<BoardItemSnapshot> = transactions.inTransaction {
        dao.listByConversation(conversationId.toString())
            .map { it.toDomain() }
            .filter { statuses == null || it.status in statuses }
            .map { BoardItemSnapshot(it, blockersOf(conversationId, it.id)) }
    }

    suspend fun update(
        conversationId: Uuid,
        patch: WorkItemPatch,
        actor: BoardActor? = null,
    ): BoardMutationResult = transactions.inTransaction {
        val entity = dao.getById(patch.id.toString())
            ?.takeIf { it.conversationId == conversationId.toString() }
            ?: return@inTransaction BoardMutationResult.Rejected("work item not found: ${patch.id}")
        val item = entity.toDomain()
        if (item.status == WorkItemStatus.Deleted) {
            return@inTransaction BoardMutationResult.Rejected("work item is deleted: ${patch.id}")
        }

        val addBlockers = patch.addBlockedBy.distinct()
        blockerRejection(conversationId, addBlockers)?.let { return@inTransaction it }
        val newEdges = addBlockers.map { BoardDependencyEdge(blockerId = it, blockedId = item.id) }
        cycleRejection(conversationId, newEdges)?.let { return@inTransaction it }

        var updated = item.copy(
            subject = patch.subject ?: item.subject,
            description = patch.description ?: item.description,
            activeForm = patch.activeForm ?: item.activeForm,
        )
        var lease: Long? = entity.leaseExpiresAt

        when (val action = patch.action) {
            null -> Unit

            WorkItemAction.Claim -> {
                if (actor == null) {
                    return@inTransaction BoardMutationResult.Rejected("claim requires an owner handle")
                }
                // Lease backstop: an expired claim is implicitly released first — itself a
                // validated transition, so legality stays the validator's call.
                val expired = updated.status == WorkItemStatus.InProgress &&
                    lease != null && lease <= now()
                val from = if (expired) {
                    when (val released = WorkItemTransitionValidator.transition(updated.status, WorkItemAction.Release)) {
                        is WorkItemTransitionResult.Allowed -> released.to
                        is WorkItemTransitionResult.Rejected ->
                            return@inTransaction BoardMutationResult.Rejected(released.reason)
                    }
                } else updated.status
                val to = when (val result = WorkItemTransitionValidator.transition(from, WorkItemAction.Claim)) {
                    is WorkItemTransitionResult.Allowed -> result.to
                    is WorkItemTransitionResult.Rejected ->
                        return@inTransaction BoardMutationResult.Rejected(result.reason)
                }
                val unresolved = unresolvedBlockers(conversationId, item.id, addBlockers)
                if (unresolved.isNotEmpty()) {
                    return@inTransaction BoardMutationResult.Rejected(
                        "blocked by unresolved items: ${unresolved.joinToString()}"
                    )
                }
                updated = updated.copy(status = to, ownerHandleId = actor.handleId, ownerName = actor.displayName)
                lease = now() + claimLeaseMillis
            }

            WorkItemAction.Complete -> {
                val to = validatedTarget(updated.status, action)
                    ?: return@inTransaction rejectedTransition(updated.status, action)
                // Owner stays for display ("who did this"); the lease only guards live claims.
                updated = updated.copy(status = to)
                lease = null
            }

            WorkItemAction.Release, WorkItemAction.Reopen -> {
                val to = validatedTarget(updated.status, action)
                    ?: return@inTransaction rejectedTransition(updated.status, action)
                updated = updated.copy(status = to, ownerHandleId = null, ownerName = null)
                lease = null
            }

            WorkItemAction.Delete -> {
                val to = validatedTarget(updated.status, action)
                    ?: return@inTransaction rejectedTransition(updated.status, action)
                updated = updated.copy(status = to, ownerHandleId = null, ownerName = null)
                lease = null
            }
        }

        newEdges.forEach { dao.insertDependency(it.toEntity(conversationId)) }
        dao.update(
            WorkItemEntity.fromWorkItem(
                item = updated,
                createdAt = entity.createdAt,
                updatedAt = now(),
                metadataJson = entity.metadataJson,
                leaseExpiresAt = lease,
            )
        )
        val blockedBy = if (updated.status == WorkItemStatus.Deleted) {
            // DeleteUnblocksDependents: every edge touching the item goes with it, atomically.
            dao.deleteDependenciesTouching(conversationId.toString(), item.id.toString())
            emptyList()
        } else {
            blockersOf(conversationId, item.id)
        }
        BoardMutationResult.Accepted(BoardItemSnapshot(updated, blockedBy))
    }

    // --- invariant helpers (transaction-internal) -----------------------------------------------

    private fun validatedTarget(from: WorkItemStatus, action: WorkItemAction): WorkItemStatus? =
        (WorkItemTransitionValidator.transition(from, action) as? WorkItemTransitionResult.Allowed)?.to

    private fun rejectedTransition(from: WorkItemStatus, action: WorkItemAction): BoardMutationResult.Rejected =
        BoardMutationResult.Rejected(WorkItemTransitionResult.Rejected(from, action).reason)

    /** Blockers must exist on THIS board and not be deleted — a deleted blocker can never resolve. */
    private suspend fun blockerRejection(
        conversationId: Uuid,
        blockerIds: List<Uuid>,
    ): BoardMutationResult.Rejected? {
        for (blockerId in blockerIds) {
            val row = dao.getById(blockerId.toString())
                ?.takeIf { it.conversationId == conversationId.toString() }
                ?: return BoardMutationResult.Rejected("unknown blocker: $blockerId")
            if (row.status == WorkItemStatus.Deleted.name) {
                return BoardMutationResult.Rejected("blocker is deleted: $blockerId")
            }
        }
        return null
    }

    /**
     * Replays the persisted edges through [BoardGraph] (acyclic by construction — a rejection
     * there means corrupt rows) and then tries [newEdges]; the first cycle-closing one rejects
     * the whole mutation with the existing blocking path.
     */
    private suspend fun cycleRejection(
        conversationId: Uuid,
        newEdges: List<BoardDependencyEdge>,
    ): BoardMutationResult.Rejected? {
        var graph = BoardGraph.EMPTY
        for (edge in persistedEdges(conversationId)) {
            graph = when (val result = graph.insert(edge)) {
                is BoardEdgeInsertResult.Inserted -> result.graph
                is BoardEdgeInsertResult.CycleRejected ->
                    error("corrupt board $conversationId: persisted dependency edges contain a cycle ${result.cyclePath}")
            }
        }
        for (edge in newEdges) {
            graph = when (val result = graph.insert(edge)) {
                is BoardEdgeInsertResult.Inserted -> result.graph
                is BoardEdgeInsertResult.CycleRejected -> return BoardMutationResult.Rejected(
                    "dependency would create a cycle: ${result.cyclePath.joinToString(" -> ")}"
                )
            }
        }
        return null
    }

    /**
     * The claim gate: persisted blockers plus the edges this very patch adds; unresolved =
     * anything not yet [WorkItemStatus.Completed]. Deleted blockers cannot appear — delete
     * removes their edges in the same transaction.
     */
    private suspend fun unresolvedBlockers(
        conversationId: Uuid,
        itemId: Uuid,
        addedBlockers: List<Uuid>,
    ): List<Uuid> = (blockersOf(conversationId, itemId) + addedBlockers).distinct().filter { blockerId ->
        val row = dao.getById(blockerId.toString())
            ?: error("corrupt board $conversationId: dangling dependency edge $blockerId -> $itemId")
        row.status != WorkItemStatus.Completed.name
    }

    private suspend fun blockersOf(conversationId: Uuid, itemId: Uuid): List<Uuid> =
        dao.listBlockersOf(conversationId.toString(), itemId.toString()).map { parseUuid(it.blockerId) }

    private suspend fun persistedEdges(conversationId: Uuid): List<BoardDependencyEdge> =
        dao.listDependencies(conversationId.toString()).map {
            BoardDependencyEdge(blockerId = parseUuid(it.blockerId), blockedId = parseUuid(it.blockedId))
        }

    private fun BoardDependencyEdge.toEntity(conversationId: Uuid): WorkItemDependencyEntity =
        WorkItemDependencyEntity(
            conversationId = conversationId.toString(),
            blockerId = blockerId.toString(),
            blockedId = blockedId.toString(),
        )

    private fun WorkItemEntity.toDomain(): WorkItem =
        toWorkItem() ?: error("corrupt work-item row: $id")

    private fun parseUuid(value: String): Uuid =
        runCatching { Uuid.parse(value) }.getOrElse { error("corrupt board row: unparseable uuid $value") }

    companion object {
        /**
         * Default claim lease. Matches the task wall-time default (SPEC.md `TaskBudget`,
         * 10 min): a handle that outlives this without renewing is presumed dead, and its
         * claims become take-over-able.
         */
        const val DEFAULT_CLAIM_LEASE_MILLIS: Long = 10 * 60_000L

        /** Retention cutoff for completed/deleted items: 30 days (SPEC.md M6 Failure-modes row). */
        const val DEFAULT_RETENTION_MAX_AGE_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

        /** Newest completed/deleted items kept per conversation regardless of age (SPEC.md M6). */
        const val DEFAULT_RETENTION_KEEP_NEWEST: Int = 200
    }
}
