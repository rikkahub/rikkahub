package me.rerere.rikkahub.data.repository.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.rikkahub.data.db.dao.TaskRunDAO
import me.rerere.rikkahub.data.db.dao.TaskRunRetentionRow
import me.rerere.rikkahub.data.db.entity.TaskRunEntity

/**
 * In-memory [TaskRunDAO] for JVM repository tests (SPEC.md M2 testing strategy: CI runs no
 * instrumented tests, so task-run persistence is pinned at the repository seam against a DAO
 * fake). Row semantics mirror the Room DAO: [upsert] is REPLACE-on-conflict, the list/scan
 * queries filter and order the same way the `@Query` strings do, and delete queries return
 * affected-row counts.
 *
 * A plain map guarded by a monitor — the repository serializes its own read-modify-write via
 * [me.rerere.rikkahub.data.repository.BoardTransactionRunner], so cross-call atomicity is the
 * runner's job (as it is Room's), not this fake's.
 */
class FakeTaskRunDAO : TaskRunDAO {
    private val lock = Any()
    private val runs = LinkedHashMap<String, TaskRunEntity>()

    /** Snapshot/restore so a test [BoardTransactionRunner] fake can roll the table back on a throw. */
    class StateSnapshot internal constructor(internal val runs: Map<String, TaskRunEntity>)

    fun snapshot(): StateSnapshot = synchronized(lock) { StateSnapshot(runs.toMap()) }

    fun restore(snapshot: StateSnapshot) = synchronized(lock) {
        runs.clear()
        runs.putAll(snapshot.runs)
    }

    override suspend fun upsert(run: TaskRunEntity) {
        synchronized(lock) { runs[run.id] = run }
    }

    override suspend fun getById(id: String): TaskRunEntity? = synchronized(lock) { runs[id] }

    override suspend fun attachToolAnchor(
        taskId: String,
        toolCallId: String,
        toolNodeId: String,
        toolMessageId: String,
    ): Int = synchronized(lock) {
        val row = runs[taskId] ?: return 0
        // Mirror the DAO: attach only when unset OR all three fields already match (idempotent);
        // ANY conflicting anchor returns 0.
        val unset = row.toolCallId == null && row.toolNodeId == null && row.toolMessageId == null
        val sameAnchor = row.toolCallId == toolCallId &&
            row.toolNodeId == toolNodeId &&
            row.toolMessageId == toolMessageId
        if (!unset && !sameAnchor) return 0
        runs[taskId] = row.copy(
            toolCallId = toolCallId,
            toolNodeId = toolNodeId,
            toolMessageId = toolMessageId,
        )
        1
    }

    override fun getByIdFlow(id: String): Flow<TaskRunEntity?> = flow { emit(getById(id)) }

    override fun listByConversationFlow(conversationId: String): Flow<List<TaskRunEntity>> = flow {
        emit(
            synchronized(lock) {
                runs.values.filter { it.conversationId == conversationId }.sortedBy { it.createdAt }
            }
        )
    }

    override suspend fun listByStates(states: Set<String>): List<TaskRunEntity> =
        synchronized(lock) { runs.values.filter { it.latestState in states } }

    override suspend fun listBackgroundByStates(states: Set<String>): List<TaskRunEntity> =
        synchronized(lock) { runs.values.filter { it.isBackground && it.latestState in states } }

    override suspend fun listForegroundByStates(states: Set<String>): List<TaskRunEntity> =
        synchronized(lock) { runs.values.filter { !it.isBackground && it.latestState in states } }

    override suspend fun deleteById(id: String): Int = synchronized(lock) {
        if (runs.remove(id) != null) 1 else 0
    }

    override suspend fun listRetainable(states: Set<String>): List<TaskRunRetentionRow> =
        synchronized(lock) {
            runs.values.filter { it.latestState in states }
                .map { TaskRunRetentionRow(id = it.id, conversationId = it.conversationId, updatedAt = it.updatedAt) }
        }

    override suspend fun deleteByIds(ids: List<String>): Int = synchronized(lock) {
        ids.count { runs.remove(it) != null }
    }

    override suspend fun deleteByConversationId(conversationId: String): Int = synchronized(lock) {
        val ids = runs.values.filter { it.conversationId == conversationId }.map { it.id }
        ids.forEach { runs.remove(it) }
        ids.size
    }
}
