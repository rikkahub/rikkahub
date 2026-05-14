package me.rerere.rikkahub.data.agentrun

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 24 — JVM unit tests for [AgentRunRepository]: the open → status → terminal
 * lifecycle, idempotent terminal writes, concurrent-write serialisation, and the
 * retention cap. Backed by [FakeAgentRunDao] so it runs fast on the JVM without Room.
 */
class AgentRunRepositoryTest {

    private fun repo() = AgentRunRepository(FakeAgentRunDao())

    @Test
    fun `open creates a running row with started_at set`() = runBlocking {
        val dao = FakeAgentRunDao()
        val repo = AgentRunRepository(dao)

        val id = repo.open(AgentRunKind.Cron, domainId = "job-1:1000")

        val row = repo.getById(id)
        assertNotNull(row)
        assertEquals("cron", row!!.kind)
        assertEquals("job-1:1000", row.domainId)
        assertEquals(AgentRunStatus.running.name, row.status)
        assertNotNull("started_at_ms should be set for a running row", row.startedAtMs)
        assertNull("finished_at_ms should be null until terminal", row.finishedAtMs)
    }

    @Test
    fun `open with queued status leaves started_at null`() = runBlocking {
        val repo = repo()
        val id = repo.open(AgentRunKind.SubAgent, domainId = "sub-1", status = AgentRunStatus.queued)
        val row = repo.getById(id)!!
        assertEquals(AgentRunStatus.queued.name, row.status)
        assertNull("queued row should not have started_at_ms", row.startedAtMs)
    }

    @Test
    fun `setStatus running stamps started_at on a previously queued row`() = runBlocking {
        val repo = repo()
        val id = repo.open(AgentRunKind.SubAgent, domainId = "sub-1", status = AgentRunStatus.queued)
        repo.setStatus(id, AgentRunStatus.running)
        val row = repo.getById(id)!!
        assertEquals(AgentRunStatus.running.name, row.status)
        assertNotNull("running transition should stamp started_at_ms", row.startedAtMs)
    }

    @Test
    fun `markTerminal sets finished_at and last_error`() = runBlocking {
        val repo = repo()
        val id = repo.open(AgentRunKind.Workflow, domainId = "wf-1")
        repo.markTerminal(id, AgentRunStatus.failed, lastError = "boom")
        val row = repo.getById(id)!!
        assertEquals(AgentRunStatus.failed.name, row.status)
        assertNotNull(row.finishedAtMs)
        assertEquals("boom", row.lastError)
    }

    @Test
    fun `markTerminal is idempotent - a second terminal write is ignored`() = runBlocking {
        val repo = repo()
        val id = repo.open(AgentRunKind.Telegram, domainId = "conv-1")
        repo.markTerminal(id, AgentRunStatus.succeeded)
        // A late terminal write from a coroutine that survived must NOT overwrite.
        repo.markTerminal(id, AgentRunStatus.failed, lastError = "late error")
        val row = repo.getById(id)!!
        assertEquals("first terminal status wins", AgentRunStatus.succeeded.name, row.status)
        assertNull(row.lastError)
    }

    @Test
    fun `setStatus on a terminal row is a no-op`() = runBlocking {
        val repo = repo()
        val id = repo.open(AgentRunKind.Cron, domainId = "job-1:1")
        repo.markTerminal(id, AgentRunStatus.succeeded)
        repo.setStatus(id, AgentRunStatus.running)
        assertEquals(AgentRunStatus.succeeded.name, repo.getById(id)!!.status)
    }

    @Test
    fun `markTerminal rejects a non-terminal status`() = runBlocking {
        val repo = repo()
        val id = repo.open(AgentRunKind.Cron, domainId = "job-1:1")
        repo.markTerminal(id, AgentRunStatus.running)  // invalid — should be ignored
        assertEquals(AgentRunStatus.running.name, repo.getById(id)!!.status)
    }

    @Test
    fun `concurrent transitions across many runs do not corrupt each other`() = runBlocking {
        val repo = repo()
        // Open 50 runs, then concurrently transition each to a terminal status. The
        // repository's Mutex must serialise these so no run's status field is lost.
        val ids = (0 until 50).map { repo.open(AgentRunKind.Workflow, domainId = "wf-$it") }
        ids.mapIndexed { i, id ->
            async {
                repo.setStatus(id, AgentRunStatus.running)
                val terminal = if (i % 2 == 0) AgentRunStatus.succeeded else AgentRunStatus.failed
                repo.markTerminal(id, terminal, lastError = if (i % 2 == 0) null else "err-$i")
            }
        }.awaitAll()

        ids.forEachIndexed { i, id ->
            val row = repo.getById(id)!!
            val expected = if (i % 2 == 0) AgentRunStatus.succeeded else AgentRunStatus.failed
            assertEquals("run $i should reach $expected", expected.name, row.status)
            assertTrue("terminal row should have finished_at_ms", row.finishedAtMs != null)
        }
    }

    @Test
    fun `concurrent open and terminal on the same row never strands it`() = runBlocking {
        val repo = repo()
        // Stress: open + immediately terminal, many times in parallel. Every resulting row
        // must be terminal — none left in `running`.
        val ids = (0 until 100).map {
            async {
                val id = repo.open(AgentRunKind.SubAgent, domainId = "sub-$it")
                repo.markTerminal(id, AgentRunStatus.succeeded)
                id
            }
        }.awaitAll()
        ids.forEach { id ->
            assertEquals(AgentRunStatus.succeeded.name, repo.getById(id)!!.status)
        }
        assertEquals(0, repo.countByStatusSince(AgentRunStatus.running, 0L))
    }

    @Test
    fun `retention cap evicts oldest terminal rows and never an in-flight row`() = runBlocking {
        val dao = FakeAgentRunDao()
        val repo = AgentRunRepository(dao)
        // Insert one in-flight run first — it must survive eviction.
        val inFlightId = repo.open(AgentRunKind.Cron, domainId = "long-running")
        // Open well over the cap; each open() runs purgeOldest internally.
        repeat(AgentRunDefaults.RETENTION_CAP + 50) { i ->
            val id = repo.open(AgentRunKind.Workflow, domainId = "wf-$i")
            repo.markTerminal(id, AgentRunStatus.succeeded)
        }
        val count = repo.count()
        assertTrue(
            "table must be capped at ${AgentRunDefaults.RETENTION_CAP} (was $count)",
            count <= AgentRunDefaults.RETENTION_CAP,
        )
        assertNotNull("in-flight row must never be evicted", repo.getById(inFlightId))
        assertEquals(AgentRunStatus.running.name, repo.getById(inFlightId)!!.status)
    }

    @Test
    fun `metadata over 4KB is dropped rather than stored truncated`() = runBlocking {
        val repo = repo()
        val huge = buildJsonObject { put("blob", "x".repeat(AgentRunDefaults.METADATA_MAX_BYTES + 100)) }
        val id = repo.open(AgentRunKind.Cron, domainId = "job-x", metadata = huge)
        assertNull("oversized metadata_json should be dropped", repo.getById(id)!!.metadataJson)
    }

    @Test
    fun `small metadata is preserved`() = runBlocking {
        val repo = repo()
        val meta = buildJsonObject { put("job_name", "nightly backup") }
        val id = repo.open(AgentRunKind.Cron, domainId = "job-y", metadata = meta)
        val stored = repo.getById(id)!!.metadataJson
        assertNotNull(stored)
        assertTrue(stored!!.contains("nightly backup"))
    }

    @Test
    fun `getByDomainId and getChildren filter correctly`() = runBlocking {
        val repo = repo()
        val parent = repo.open(AgentRunKind.SubAgent, domainId = "parent-run")
        val childA = repo.open(AgentRunKind.SubAgent, domainId = "child-a", parentRunId = parent)
        val childB = repo.open(AgentRunKind.SubAgent, domainId = "child-b", parentRunId = parent)
        repo.open(AgentRunKind.Cron, domainId = "unrelated")

        val byDomain = repo.getByDomainId(AgentRunKind.SubAgent, "child-a")
        assertEquals(1, byDomain.size)

        val children = repo.getChildren(parent)
        assertEquals(2, children.size)
        assertTrue(children.map { it.id }.containsAll(listOf(childA, childB)))
    }

    @Test
    fun `markAllProcessLost flips only the supplied in-flight rows`() = runBlocking {
        val repo = repo()
        val running = repo.open(AgentRunKind.Cron, domainId = "job-1:1")
        val alreadyDone = repo.open(AgentRunKind.Cron, domainId = "job-2:1")
        repo.markTerminal(alreadyDone, AgentRunStatus.succeeded)

        val flipped = repo.markAllProcessLost(listOf(running, alreadyDone))
        assertEquals("only the still-running row should flip", 1, flipped)
        assertEquals(AgentRunStatus.process_lost.name, repo.getById(running)!!.status)
        assertEquals(
            "an already-terminal row must not be overwritten",
            AgentRunStatus.succeeded.name,
            repo.getById(alreadyDone)!!.status,
        )
    }
}
