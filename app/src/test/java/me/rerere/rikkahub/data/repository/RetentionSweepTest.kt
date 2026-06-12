package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.board.WorkItemStatus
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import me.rerere.rikkahub.data.db.entity.WorkItemEntity
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeTaskRunDAO
import me.rerere.rikkahub.data.repository.fakes.FakeWorkItemDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Retention sweep (SPEC.md M6 + the Failure-modes "Unbounded board" row): completed/deleted rows
 * are retained for 30 days OR the newest 200 per conversation, whichever keeps MORE — a row is
 * swept only when it is BOTH older than the cutoff AND outside the newest-200 window. Open/active
 * rows are kept indefinitely. Pinned at the repository seam against DAO fakes (CI runs JVM only).
 */
class RetentionSweepTest {

    private val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000

    // --- task_runs ------------------------------------------------------------------------------

    private class RunFixture {
        val dao = FakeTaskRunDAO()
        val repository = TaskRunRepository(dao = dao, transactions = FakeBoardTransactions(), now = { 0L })
    }

    private suspend fun seedRun(
        f: RunFixture,
        conversationId: Uuid,
        state: TaskRunStateTag,
        updatedAt: Long,
    ): Uuid {
        val id = Uuid.random()
        f.dao.upsert(
            me.rerere.rikkahub.data.db.entity.TaskRunEntity(
                id = id.toString(),
                conversationId = conversationId.toString(),
                parentToolCallId = "call",
                agentTypeId = "agent",
                prompt = "p",
                latestState = state.name,
                createdAt = updatedAt,
                updatedAt = updatedAt,
            )
        )
        return id
    }

    @Test
    fun `sweep deletes a terminal run older than the cutoff and beyond the newest-N window`() = runBlocking {
        val f = RunFixture()
        val conv = Uuid.random()
        val now = 100L * thirtyDaysMs
        val old = seedRun(f, conv, TaskRunStateTag.SUCCEEDED, updatedAt = now - 2 * thirtyDaysMs)
        val recent = seedRun(f, conv, TaskRunStateTag.SUCCEEDED, updatedAt = now - 1)

        val swept = f.repository.sweepRetention(now = now, maxAgeMillis = thirtyDaysMs, keepNewestPerConversation = 1)

        assertEquals(1, swept)
        assertNull("old terminal beyond the window is swept", f.dao.getById(old.toString()))
        assertNotNull("the newest-1 terminal is kept regardless of age", f.dao.getById(recent.toString()))
    }

    @Test
    fun `sweep keeps a terminal run that is old but within the newest-N window`() = runBlocking {
        val f = RunFixture()
        val conv = Uuid.random()
        val now = 100L * thirtyDaysMs
        // Both old, but keepNewest=2 covers both, so neither is swept (newest-N wins over age).
        val a = seedRun(f, conv, TaskRunStateTag.FAILED, updatedAt = now - 3 * thirtyDaysMs)
        val b = seedRun(f, conv, TaskRunStateTag.CANCELLED, updatedAt = now - 2 * thirtyDaysMs)

        val swept = f.repository.sweepRetention(now = now, maxAgeMillis = thirtyDaysMs, keepNewestPerConversation = 2)

        assertEquals(0, swept)
        assertNotNull(f.dao.getById(a.toString()))
        assertNotNull(f.dao.getById(b.toString()))
    }

    @Test
    fun `sweep never deletes an active or interrupted run no matter how old`() = runBlocking {
        val f = RunFixture()
        val conv = Uuid.random()
        val now = 100L * thirtyDaysMs
        val running = seedRun(f, conv, TaskRunStateTag.RUNNING, updatedAt = 0L)
        val interrupted = seedRun(f, conv, TaskRunStateTag.INTERRUPTED, updatedAt = 0L)

        val swept = f.repository.sweepRetention(now = now, maxAgeMillis = thirtyDaysMs, keepNewestPerConversation = 0)

        assertEquals("non-terminal rows are never swept", 0, swept)
        assertNotNull(f.dao.getById(running.toString()))
        assertNotNull(f.dao.getById(interrupted.toString()))
    }

    @Test
    fun `sweep is per-conversation - one conversation's window does not consume another's`() = runBlocking {
        val f = RunFixture()
        val convA = Uuid.random()
        val convB = Uuid.random()
        val now = 100L * thirtyDaysMs
        // Each conversation has one old terminal; keepNewest=1 keeps each one independently.
        val a = seedRun(f, convA, TaskRunStateTag.SUCCEEDED, updatedAt = now - 5 * thirtyDaysMs)
        val b = seedRun(f, convB, TaskRunStateTag.SUCCEEDED, updatedAt = now - 5 * thirtyDaysMs)

        val swept = f.repository.sweepRetention(now = now, maxAgeMillis = thirtyDaysMs, keepNewestPerConversation = 1)

        assertEquals(0, swept)
        assertNotNull(f.dao.getById(a.toString()))
        assertNotNull(f.dao.getById(b.toString()))
    }

    // --- work_items -----------------------------------------------------------------------------

    private class BoardFixture {
        val dao = FakeWorkItemDAO()
        val repository = TaskBoardRepository(dao = dao, transactions = FakeBoardTransactions(), now = { 0L })
    }

    private suspend fun seedItem(
        f: BoardFixture,
        conversationId: Uuid,
        status: WorkItemStatus,
        updatedAt: Long,
    ): Uuid {
        val id = Uuid.random()
        f.dao.insert(
            WorkItemEntity(
                id = id.toString(),
                conversationId = conversationId.toString(),
                subject = "s",
                status = status.name,
                createdAt = updatedAt,
                updatedAt = updatedAt,
            )
        )
        return id
    }

    @Test
    fun `board sweep deletes an old completed item beyond the window but keeps open items`() = runBlocking {
        val f = BoardFixture()
        val conv = Uuid.random()
        val now = 100L * thirtyDaysMs
        val oldDone = seedItem(f, conv, WorkItemStatus.Completed, updatedAt = now - 2 * thirtyDaysMs)
        val oldDeleted = seedItem(f, conv, WorkItemStatus.Deleted, updatedAt = now - 2 * thirtyDaysMs)
        val oldOpen = seedItem(f, conv, WorkItemStatus.Pending, updatedAt = 0L)

        val swept = f.repository.sweepRetention(now = now, maxAgeMillis = thirtyDaysMs, keepNewestPerConversation = 0)

        assertEquals("both terminal items beyond the window are swept", 2, swept)
        assertNull(f.dao.getById(oldDone.toString()))
        assertNull(f.dao.getById(oldDeleted.toString()))
        assertNotNull("an open item is kept indefinitely", f.dao.getById(oldOpen.toString()))
    }
}
