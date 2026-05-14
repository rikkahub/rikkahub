package me.rerere.rikkahub.data.agentrun

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 24 — JVM unit tests for the boot-recovery sweep logic.
 *
 * [AgentRunBootRecovery] takes an injectable `notifyStranded` sink — the production
 * constructor wires it to a real Android notification, but the primary constructor lets a
 * JVM test pass a capturing lambda. We assert on the count flipped, the resulting ledger
 * state, and that the notifier fires exactly once with the stranded rows.
 */
class AgentRunBootRecoveryTest {

    private val now = System.currentTimeMillis()
    private val stale = now - AgentRunDefaults.STRANDED_THRESHOLD_MS - 60_000L
    private val fresh = now - 5_000L

    private var notifiedWith: List<AgentRun>? = null
    private var notifyCount = 0

    private fun recovery(repo: AgentRunRepository): AgentRunBootRecovery =
        AgentRunBootRecovery(repo) { stranded ->
            notifyCount++
            notifiedWith = stranded
        }

    @Test
    fun `stranded running rows older than the threshold are flipped to process_lost`() = runBlocking {
        val dao = FakeAgentRunDao()
        val repo = AgentRunRepository(dao)
        // A genuinely stranded run: running, not touched in over 30 minutes.
        dao.insert(
            runRow(id = "stranded-1", status = AgentRunStatus.running, updatedAtMs = stale)
        )
        dao.insert(
            runRow(id = "stranded-2", status = AgentRunStatus.awaiting_approval, updatedAtMs = stale)
        )

        val flipped = recovery(repo).runRecovery()

        assertEquals(2, flipped)
        assertEquals(AgentRunStatus.process_lost.name, repo.getById("stranded-1")!!.status)
        assertEquals(AgentRunStatus.process_lost.name, repo.getById("stranded-2")!!.status)
        // Exactly ONE aggregate notification for the whole sweep — no per-row storm.
        assertEquals("exactly one aggregate notification", 1, notifyCount)
        assertEquals(2, notifiedWith!!.size)
    }

    @Test
    fun `fresh in-flight rows are NOT swept`() = runBlocking {
        val dao = FakeAgentRunDao()
        val repo = AgentRunRepository(dao)
        // A run that updated recently — a live process owns it, leave it alone.
        dao.insert(runRow(id = "live-1", status = AgentRunStatus.running, updatedAtMs = fresh))

        val flipped = recovery(repo).runRecovery()

        assertEquals(0, flipped)
        assertEquals(AgentRunStatus.running.name, repo.getById("live-1")!!.status)
        assertEquals("no notification when nothing was flipped", 0, notifyCount)
    }

    @Test
    fun `terminal rows are never touched`() = runBlocking {
        val dao = FakeAgentRunDao()
        val repo = AgentRunRepository(dao)
        dao.insert(runRow(id = "done-1", status = AgentRunStatus.succeeded, updatedAtMs = stale))
        dao.insert(runRow(id = "done-2", status = AgentRunStatus.failed, updatedAtMs = stale))

        val flipped = recovery(repo).runRecovery()

        assertEquals(0, flipped)
        assertEquals(AgentRunStatus.succeeded.name, repo.getById("done-1")!!.status)
        assertEquals(AgentRunStatus.failed.name, repo.getById("done-2")!!.status)
    }

    @Test
    fun `recovery on an empty ledger flips nothing`() = runBlocking {
        val flipped = recovery(AgentRunRepository(FakeAgentRunDao())).runRecovery()
        assertEquals(0, flipped)
    }

    @Test
    fun `mixed ledger - only the stale in-flight rows flip`() = runBlocking {
        val dao = FakeAgentRunDao()
        val repo = AgentRunRepository(dao)
        dao.insert(runRow(id = "stranded", status = AgentRunStatus.running, updatedAtMs = stale))
        dao.insert(runRow(id = "live", status = AgentRunStatus.running, updatedAtMs = fresh))
        dao.insert(runRow(id = "done", status = AgentRunStatus.succeeded, updatedAtMs = stale))
        dao.insert(runRow(id = "queued-stale", status = AgentRunStatus.queued, updatedAtMs = stale))

        val flipped = recovery(repo).runRecovery()

        assertEquals(2, flipped)
        assertEquals(AgentRunStatus.process_lost.name, repo.getById("stranded")!!.status)
        assertEquals(AgentRunStatus.process_lost.name, repo.getById("queued-stale")!!.status)
        assertEquals(AgentRunStatus.running.name, repo.getById("live")!!.status)
        assertEquals(AgentRunStatus.succeeded.name, repo.getById("done")!!.status)
    }

    private fun runRow(id: String, status: AgentRunStatus, updatedAtMs: Long): AgentRun =
        AgentRun(
            id = id,
            kind = AgentRunKind.Cron.wire,
            domainId = "domain-$id",
            parentRunId = null,
            status = status.name,
            createdAtMs = updatedAtMs,
            updatedAtMs = updatedAtMs,
            startedAtMs = if (status == AgentRunStatus.queued) null else updatedAtMs,
            finishedAtMs = if (status.isTerminal) updatedAtMs else null,
            lastError = null,
            metadataJson = null,
        )
}
