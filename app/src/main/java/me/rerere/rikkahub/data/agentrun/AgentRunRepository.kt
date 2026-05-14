package me.rerere.rikkahub.data.agentrun

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

private const val TAG = "AgentRunRepository"

/**
 * Phase 24 — single shared writer/reader for the unified `agent_runs` ledger.
 *
 * All five autonomous paths (cron, workflow, sub-agent, Telegram, external automation)
 * write through this one repository. Every mutating method serialises through a single
 * [Mutex] so two concurrent runs transitioning at the same time can't last-writer-win
 * each other's `status` / `last_error` fields — each transition is read-modify-write
 * under the lock.
 *
 * Write throughput is comfortably within budget: the absolute peak is a workflow burst at
 * roughly ten writes/sec, far below what a serialised Room writer handles.
 *
 * Every public method is best-effort: ledger failures are logged, never thrown. The
 * ledger is observability infrastructure — a write failure here must never break the
 * domain run it is shadowing.
 */
class AgentRunRepository(private val dao: AgentRunDao) {

    private val writeMutex = Mutex()

    /**
     * Open a new ledger row. Returns the generated row id (a UUID string). The row starts
     * in [AgentRunStatus.running] with `started_at_ms` set — callers that need a distinct
     * `queued` phase should call [setStatus] afterwards, but for the five v1 paths the run
     * is already executing by the time the row is opened.
     *
     * On failure a fallback UUID is still returned so callers can keep a stable handle and
     * later [setStatus] / [markTerminal] calls simply no-op against a missing row.
     */
    suspend fun open(
        kind: AgentRunKind,
        domainId: String,
        parentRunId: String? = null,
        metadata: JsonObject? = null,
        status: AgentRunStatus = AgentRunStatus.running,
    ): String {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val started = if (status == AgentRunStatus.queued || status == AgentRunStatus.awaiting_approval) null else now
        val row = AgentRun(
            id = id,
            kind = kind.wire,
            domainId = domainId,
            parentRunId = parentRunId,
            status = status.name,
            createdAtMs = now,
            updatedAtMs = now,
            startedAtMs = started,
            finishedAtMs = null,
            lastError = null,
            metadataJson = boundMetadata(metadata),
        )
        runCatching {
            writeMutex.withLock {
                dao.insert(row)
                dao.purgeOldest(AgentRunDefaults.RETENTION_CAP)
            }
        }.onFailure { logSafe { Log.w(TAG, "open($kind, $domainId) failed", it) } }
        return id
    }

    /**
     * Move a row to a non-terminal status (e.g. `running`, `awaiting_approval`). Read-
     * modify-write under the lock. No-ops if the row is unknown or already terminal.
     */
    suspend fun setStatus(id: String, status: AgentRunStatus, lastError: String? = null) {
        runCatching {
            writeMutex.withLock {
                val existing = dao.getById(id) ?: return@withLock
                val current = AgentRunStatus.fromName(existing.status)
                if (current.isTerminal) return@withLock
                val now = System.currentTimeMillis()
                dao.update(
                    existing.copy(
                        status = status.name,
                        updatedAtMs = now,
                        startedAtMs = existing.startedAtMs ?: if (status == AgentRunStatus.running) now else null,
                        lastError = lastError?.take(LAST_ERROR_MAX) ?: existing.lastError,
                    )
                )
            }
        }.onFailure { logSafe { Log.w(TAG, "setStatus($id, $status) failed", it) } }
    }

    /**
     * Move a row to a terminal status. Idempotent — if the row is already terminal the
     * call is a no-op so a process-lost row can't be overwritten by a late terminal write
     * from a coroutine that survived.
     */
    suspend fun markTerminal(id: String, status: AgentRunStatus, lastError: String? = null) {
        if (!status.isTerminal) {
            logSafe { Log.w(TAG, "markTerminal($id) called with non-terminal status $status — ignoring") }
            return
        }
        runCatching {
            writeMutex.withLock {
                val existing = dao.getById(id) ?: return@withLock
                if (AgentRunStatus.fromName(existing.status).isTerminal) return@withLock
                val now = System.currentTimeMillis()
                dao.update(
                    existing.copy(
                        status = status.name,
                        updatedAtMs = now,
                        finishedAtMs = now,
                        lastError = lastError?.take(LAST_ERROR_MAX) ?: existing.lastError,
                    )
                )
            }
        }.onFailure { logSafe { Log.w(TAG, "markTerminal($id, $status) failed", it) } }
    }

    fun observeRecent(limit: Int = 50): Flow<List<AgentRun>> = dao.observeRecent(limit)

    suspend fun getRecent(limit: Int = 50): List<AgentRun> =
        runCatching { dao.getRecent(limit) }.getOrDefault(emptyList())

    suspend fun getByDomainId(kind: AgentRunKind, domainId: String, limit: Int = 100): List<AgentRun> =
        runCatching { dao.getByDomainId(kind.wire, domainId, limit) }.getOrDefault(emptyList())

    suspend fun getChildren(parentRunId: String): List<AgentRun> =
        runCatching { dao.getChildren(parentRunId) }.getOrDefault(emptyList())

    /** In-flight rows untouched since [beforeMs] — for boot recovery. */
    suspend fun getStranded(beforeMs: Long): List<AgentRun> =
        runCatching { dao.findStranded(beforeMs) }.getOrDefault(emptyList())

    suspend fun getById(id: String): AgentRun? =
        runCatching { dao.getById(id) }.getOrNull()

    suspend fun count(): Int = runCatching { dao.count() }.getOrDefault(0)

    suspend fun countByStatusSince(status: AgentRunStatus, sinceMs: Long): Int =
        runCatching { dao.countByStatusSince(status.name, sinceMs) }.getOrDefault(0)

    /**
     * Flip every supplied row to [AgentRunStatus.process_lost] under the lock. Used by
     * [AgentRunBootRecovery]; returns the count actually flipped (rows that turned terminal
     * between the scan and the flip are skipped).
     */
    suspend fun markAllProcessLost(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        var flipped = 0
        runCatching {
            writeMutex.withLock {
                val now = System.currentTimeMillis()
                for (id in ids) {
                    val existing = dao.getById(id) ?: continue
                    if (AgentRunStatus.fromName(existing.status).isTerminal) continue
                    dao.update(
                        existing.copy(
                            status = AgentRunStatus.process_lost.name,
                            updatedAtMs = now,
                            finishedAtMs = now,
                            lastError = existing.lastError ?: "process killed mid-run",
                        )
                    )
                    flipped++
                }
            }
        }.onFailure { logSafe { Log.w(TAG, "markAllProcessLost failed", it) } }
        return flipped
    }

    private fun boundMetadata(metadata: JsonObject?): String? {
        if (metadata == null) return null
        val encoded = runCatching { metadata.toString() }.getOrNull() ?: return null
        // 4 KB cap so a runaway writer can't bloat the table. If it's over, drop it rather
        // than store a truncated-and-now-invalid JSON blob.
        return if (encoded.toByteArray(Charsets.UTF_8).size <= AgentRunDefaults.METADATA_MAX_BYTES) {
            encoded
        } else {
            logSafe { Log.w(TAG, "metadata_json over ${AgentRunDefaults.METADATA_MAX_BYTES} bytes — dropping") }
            null
        }
    }

    companion object {
        /** `last_error` column is kept short — full detail lives in the domain table. */
        private const val LAST_ERROR_MAX = 500
    }
}

/**
 * Run a [android.util.Log] call inside a guard so JVM unit tests — where `android.util.Log`
 * is an unmocked stub that throws — don't crash the code under test. Mirrors
 * `WorkflowActionRunner.logSafe`.
 */
private inline fun logSafe(block: () -> Unit) {
    runCatching { block() }
}
