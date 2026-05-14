package me.rerere.rikkahub.data.agentrun

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 24 — instrumented round-trip test for the real Room [AgentRunDao].
 *
 * The repository's logic is unit-tested on the JVM against a fake DAO; this test exercises
 * the actual generated Room DAO + table so the `@Query` SQL (stranded scan, FIFO purge,
 * status-since count, children lookup) is validated against SQLite.
 */
@RunWith(AndroidJUnit4::class)
class AgentRunDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AgentRunDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.agentRunDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(
        id: String,
        kind: AgentRunKind = AgentRunKind.Cron,
        domainId: String = "domain-$id",
        status: AgentRunStatus = AgentRunStatus.running,
        parentRunId: String? = null,
        updatedAtMs: Long = System.currentTimeMillis(),
    ): AgentRun = AgentRun(
        id = id,
        kind = kind.wire,
        domainId = domainId,
        parentRunId = parentRunId,
        status = status.name,
        createdAtMs = updatedAtMs,
        updatedAtMs = updatedAtMs,
        startedAtMs = if (status == AgentRunStatus.queued) null else updatedAtMs,
        finishedAtMs = if (status.isTerminal) updatedAtMs else null,
        lastError = null,
        metadataJson = null,
    )

    @Test
    fun insertAndGetById_roundTrips() = runBlocking {
        val r = row("a")
        dao.insert(r)
        val fetched = dao.getById("a")
        assertNotNull(fetched)
        assertEquals(r, fetched)
        assertNull(dao.getById("missing"))
    }

    @Test
    fun update_overwritesInPlace() = runBlocking {
        dao.insert(row("a", status = AgentRunStatus.running))
        val updated = dao.getById("a")!!.copy(
            status = AgentRunStatus.succeeded.name,
            finishedAtMs = 9_999L,
        )
        dao.update(updated)
        val fetched = dao.getById("a")!!
        assertEquals(AgentRunStatus.succeeded.name, fetched.status)
        assertEquals(9_999L, fetched.finishedAtMs)
    }

    @Test
    fun findStranded_returnsOnlyOldInFlightRows() = runBlocking {
        val cutoff = 1_000_000L
        dao.insert(row("stale-running", status = AgentRunStatus.running, updatedAtMs = cutoff - 1))
        dao.insert(row("stale-queued", status = AgentRunStatus.queued, updatedAtMs = cutoff - 1))
        dao.insert(row("fresh-running", status = AgentRunStatus.running, updatedAtMs = cutoff + 1))
        dao.insert(row("stale-terminal", status = AgentRunStatus.succeeded, updatedAtMs = cutoff - 1))

        val stranded = dao.findStranded(cutoff).map { it.id }.toSet()
        assertEquals(setOf("stale-running", "stale-queued"), stranded)
    }

    @Test
    fun getByDomainId_filtersByKindAndDomain() = runBlocking {
        dao.insert(row("a", kind = AgentRunKind.Cron, domainId = "job-1:1"))
        dao.insert(row("b", kind = AgentRunKind.Workflow, domainId = "job-1:1"))
        dao.insert(row("c", kind = AgentRunKind.Cron, domainId = "job-1:1"))

        val cronRows = dao.getByDomainId(AgentRunKind.Cron.wire, "job-1:1", 10)
        assertEquals(setOf("a", "c"), cronRows.map { it.id }.toSet())
    }

    @Test
    fun getChildren_returnsRowsByParentInCreationOrder() = runBlocking {
        dao.insert(row("parent"))
        dao.insert(row("child-1", parentRunId = "parent", updatedAtMs = 100L))
        dao.insert(row("child-2", parentRunId = "parent", updatedAtMs = 200L))
        dao.insert(row("unrelated"))

        val children = dao.getChildren("parent")
        assertEquals(listOf("child-1", "child-2"), children.map { it.id })
    }

    @Test
    fun countByStatusSince_countsCorrectly() = runBlocking {
        dao.insert(row("lost-old", status = AgentRunStatus.process_lost, updatedAtMs = 50L))
        dao.insert(row("lost-new", status = AgentRunStatus.process_lost, updatedAtMs = 5_000L))
        dao.insert(row("ok", status = AgentRunStatus.succeeded, updatedAtMs = 5_000L))

        assertEquals(1, dao.countByStatusSince(AgentRunStatus.process_lost.name, 1_000L))
        assertEquals(2, dao.countByStatusSince(AgentRunStatus.process_lost.name, 0L))
    }

    @Test
    fun purgeOldest_evictsOldestTerminalRowsAndKeepsInFlight() = runBlocking {
        // 5 terminal rows + 2 in-flight rows; cap at 4 → must evict 3 oldest TERMINAL,
        // never the in-flight ones.
        for (i in 1..5) {
            dao.insert(row("term-$i", status = AgentRunStatus.succeeded, updatedAtMs = i.toLong()))
        }
        dao.insert(row("inflight-a", status = AgentRunStatus.running, updatedAtMs = 0L))
        dao.insert(row("inflight-b", status = AgentRunStatus.queued, updatedAtMs = 0L))

        dao.purgeOldest(4)

        assertTrue("table must be capped at 4", dao.count() <= 4)
        // Both in-flight rows survive regardless of age.
        assertNotNull(dao.getById("inflight-a"))
        assertNotNull(dao.getById("inflight-b"))
        // The very oldest terminal row is gone.
        assertNull(dao.getById("term-1"))
        // The newest terminal row survives.
        assertNotNull(dao.getById("term-5"))
    }

    @Test
    fun purgeOldest_underCapIsNoOp() = runBlocking {
        dao.insert(row("a", status = AgentRunStatus.succeeded))
        dao.insert(row("b", status = AgentRunStatus.failed))
        dao.purgeOldest(1000)
        assertEquals(2, dao.count())
    }
}
