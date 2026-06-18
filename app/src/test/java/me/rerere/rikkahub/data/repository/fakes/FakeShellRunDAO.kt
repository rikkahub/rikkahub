package me.rerere.rikkahub.data.repository.fakes

import me.rerere.rikkahub.data.db.dao.ShellRunDAO
import me.rerere.rikkahub.data.db.entity.ShellRunEntity
import me.rerere.rikkahub.data.db.entity.ShellRunStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [ShellRunDAO] for JVM tests (issue #291; CI runs no instrumented tests, so the shell-run
 * lifecycle is pinned at the store seam against a DAO fake). Row semantics mirror the Room DAO
 * exactly so the property suite exercises the STORE's own logic, not SQLite's:
 *  - every state-flip update applies only when the row is in its legal predecessor status;
 *  - [markTerminalIfRunning] is the SINGLE_TERMINAL conditional CAS — it flips a row only when its
 *    status is still non-terminal (one of the four RUNNING states) and returns the affected-row count
 *    (1 = this caller won, 0 = already terminal/missing).
 *
 * A plain map guarded by a monitor — cross-call atomicity is the transaction runner's job (as it is
 * Room's), not this fake's. Mirrors [FakeAgentEventDAO].
 */
class FakeShellRunDAO : ShellRunDAO {
    private val lock = Any()
    private val rows = LinkedHashMap<String, ShellRunEntity>()
    private val rowsState = MutableStateFlow<Map<String, ShellRunEntity>>(emptyMap())

    private val runningNames = ShellRunStatus.RUNNING.map { it.name }.toSet()
    private val backgroundNames = setOf(ShellRunStatus.DETACHED.name, ShellRunStatus.BACKGROUND_RUNNING.name)

    override suspend fun insert(entity: ShellRunEntity) {
        synchronized(lock) {
            // A real @Insert throws on a duplicate primary key; taskId is freshly random in production,
            // so a collision here is a test bug worth surfacing rather than silently ignoring.
            require(!rows.containsKey(entity.taskId)) { "duplicate taskId ${entity.taskId}" }
            rows[entity.taskId] = entity
            publishLocked()
        }
    }

    override suspend fun getById(taskId: String): ShellRunEntity? = synchronized(lock) { rows[taskId] }

    override suspend fun attachToolAnchor(
        taskId: String,
        toolCallId: String,
        toolNodeId: String,
        toolMessageId: String,
    ): Int = synchronized(lock) {
        val existing = rows[taskId] ?: return 0
        val isEmpty = existing.toolCallId == null && existing.toolNodeId == null && existing.toolMessageId == null
        val isSame = existing.toolCallId == toolCallId &&
            existing.toolNodeId == toolNodeId &&
            existing.toolMessageId == toolMessageId
        if (!isEmpty && !isSame) return 0
        rows[taskId] = existing.copy(
            toolCallId = toolCallId,
            toolNodeId = toolNodeId,
            toolMessageId = toolMessageId,
        )
        publishLocked()
        1
    }

    override suspend fun getAnchoredByTaskId(taskId: String): ShellRunEntity? = synchronized(lock) {
        rows[taskId]?.takeIf {
            it.toolCallId != null && it.toolNodeId != null && it.toolMessageId != null
        }
    }

    override suspend fun markForegroundWaiting(taskId: String, startedAt: Long, pidMeta: String?): Int =
        synchronized(lock) {
            val existing = rows[taskId] ?: return 0
            if (existing.status != ShellRunStatus.STARTED.name) return 0
            rows[taskId] = existing.copy(
                status = ShellRunStatus.FOREGROUND_WAITING.name,
                startedAt = startedAt,
                pidMeta = pidMeta,
            )
            publishLocked()
            1
        }

    override suspend fun markDetached(taskId: String, detachedAt: Long, pidMeta: String?): Int =
        synchronized(lock) {
            val existing = rows[taskId] ?: return 0
            if (existing.status != ShellRunStatus.STARTED.name &&
                existing.status != ShellRunStatus.FOREGROUND_WAITING.name
            ) return 0
            rows[taskId] = existing.copy(
                status = ShellRunStatus.DETACHED.name,
                detachedAt = detachedAt,
                pidMeta = pidMeta,
            )
            publishLocked()
            1
        }

    override suspend fun markBackgroundRunning(taskId: String): Int = synchronized(lock) {
        val existing = rows[taskId] ?: return 0
        if (existing.status != ShellRunStatus.DETACHED.name) return 0
        rows[taskId] = existing.copy(status = ShellRunStatus.BACKGROUND_RUNNING.name)
        publishLocked()
        1
    }

    override suspend fun markTerminalIfRunning(
        taskId: String,
        status: String,
        exitCode: Int?,
        byteCount: Long,
        killReason: String?,
        completedAt: Long,
    ): Int = synchronized(lock) {
        val existing = rows[taskId] ?: return 0
        // The conditional claim: only a still-running row flips. A row already terminal matches the
        // WHERE's `status IN (running states)` clause for zero rows.
        if (existing.status !in runningNames) return 0
        rows[taskId] = existing.copy(
            status = status,
            exitCode = exitCode,
            byteCount = byteCount,
            killReason = killReason,
            completedAt = completedAt,
        )
        publishLocked()
        1
    }

    override suspend fun runningRows(): List<ShellRunEntity> = synchronized(lock) {
        rows.values.filter { it.status in runningNames }.toList()
    }

    override fun observeBackgroundJobs(conversationId: String): Flow<List<ShellRunEntity>> =
        rowsState.map { snapshot ->
            snapshot.values
                .filter { it.conversationId == conversationId && it.status in backgroundNames }
                .sortedByDescending { it.detachedAt ?: it.startedAt ?: it.createdAt }
        }

    override suspend fun deleteByConversationId(conversationId: String): Int = synchronized(lock) {
        val ids = rows.values.filter { it.conversationId == conversationId }.map { it.taskId }
        ids.forEach { rows.remove(it) }
        publishLocked()
        ids.size
    }

    fun snapshot(): StateSnapshot = synchronized(lock) { StateSnapshot(rows.toMap()) }

    fun restore(snapshot: StateSnapshot) = synchronized(lock) {
        rows.clear()
        rows.putAll(snapshot.rows)
        publishLocked()
    }

    data class StateSnapshot(val rows: Map<String, ShellRunEntity>)

    private fun publishLocked() {
        rowsState.value = rows.toMap()
    }
}
