package me.rerere.rikkahub.data.agentrun

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [AgentRunDao] for JVM unit tests — no Room, no instrumentation.
 *
 * The real DAO is exercised by the instrumented `Migration_23_24_Test` (table shape) and
 * by live install testing; this fake lets the repository's Mutex serialisation,
 * boot-recovery logic, and retention behaviour be unit-tested fast on the JVM.
 *
 * Backed by a [ConcurrentHashMap] so concurrent reads/writes from the repository's
 * coroutines don't ConcurrentModificationException — the repository's own Mutex is what's
 * actually under test.
 */
class FakeAgentRunDao : AgentRunDao {

    private val rows = ConcurrentHashMap<String, AgentRun>()
    private val recentFlow = MutableStateFlow<List<AgentRun>>(emptyList())

    private fun publish() {
        recentFlow.value = rows.values.sortedByDescending { it.updatedAtMs }
    }

    fun snapshot(): List<AgentRun> = rows.values.sortedByDescending { it.updatedAtMs }

    override suspend fun insert(row: AgentRun) {
        rows[row.id] = row
        publish()
    }

    override suspend fun update(row: AgentRun) {
        rows[row.id] = row
        publish()
    }

    override suspend fun getById(id: String): AgentRun? = rows[id]

    override suspend fun findStranded(beforeMs: Long): List<AgentRun> =
        rows.values.filter {
            AgentRunStatus.fromName(it.status) in AgentRunStatus.IN_FLIGHT && it.updatedAtMs < beforeMs
        }

    override suspend fun findInFlight(): List<AgentRun> =
        rows.values.filter { AgentRunStatus.fromName(it.status) in AgentRunStatus.IN_FLIGHT }
            .sortedByDescending { it.updatedAtMs }

    override fun observeRecent(limit: Int): Flow<List<AgentRun>> =
        recentFlow.map { it.take(limit) }

    override suspend fun getRecent(limit: Int): List<AgentRun> =
        rows.values.sortedByDescending { it.updatedAtMs }.take(limit)

    override suspend fun getByDomainId(kind: String, domainId: String, limit: Int): List<AgentRun> =
        rows.values.filter { it.kind == kind && it.domainId == domainId }
            .sortedByDescending { it.updatedAtMs }.take(limit)

    override suspend fun getChildren(parentRunId: String): List<AgentRun> =
        rows.values.filter { it.parentRunId == parentRunId }.sortedBy { it.createdAtMs }

    override suspend fun count(): Int = rows.size

    override suspend fun countByStatusSince(status: String, sinceMs: Long): Int =
        rows.values.count { it.status == status && it.updatedAtMs >= sinceMs }

    override suspend fun purgeOldest(cap: Int) {
        val overBy = rows.size - cap
        if (overBy <= 0) return
        // FIFO eviction of the oldest TERMINAL rows only — never an in-flight row.
        val evictable = rows.values
            .filter { AgentRunStatus.fromName(it.status).isTerminal }
            .sortedBy { it.updatedAtMs }
            .take(overBy)
        evictable.forEach { rows.remove(it.id) }
        publish()
    }
}
