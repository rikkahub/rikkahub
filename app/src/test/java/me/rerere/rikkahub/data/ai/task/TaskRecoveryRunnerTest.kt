package me.rerere.rikkahub.data.ai.task

import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskSpec
import me.rerere.ai.runtime.task.TaskState
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import me.rerere.rikkahub.data.repository.TaskBoardRepository
import me.rerere.rikkahub.data.repository.TaskRunRepository
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeTaskRunDAO
import me.rerere.rikkahub.data.repository.fakes.FakeWorkItemDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Startup recovery composition (SPEC.md M6, Success Criterion #4): on process restart the runner
 * marks every active task row `Interrupted` (no auto-resume, no side-effect replay) AND runs the
 * retention sweep, in one entry point invoked by the Application. Pinned at the repository seam
 * against DAO fakes (CI is JVM-only); this is the wiring the production path was missing.
 */
class TaskRecoveryRunnerTest {

    private val conversationId = Uuid.random()
    private val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000

    private suspend fun seedRun(
        repo: TaskRunRepository,
        taskId: Uuid,
        toState: TaskRunStateTag,
    ) {
        repo.create(
            TaskSpec(
                taskId = taskId,
                parentConversationId = conversationId,
                parentToolCallId = "call",
                agentTypeId = "agent",
                prompt = "p",
            )
        )
        when (toState) {
            TaskRunStateTag.RUNNING -> {
                repo.applyEvent(taskId, TaskEvent.Enqueued)
                repo.applyEvent(taskId, TaskEvent.SlotClaimed)
                repo.applyEvent(taskId, TaskEvent.ChildProgressed)
            }
            else -> error("unsupported seed state $toState")
        }
    }

    @Test
    fun `runStartupRecovery marks active runs Interrupted and sweeps expired terminals`() = runBlocking {
        val now = 100L * thirtyDaysMs
        val runDao = FakeTaskRunDAO()
        val runRepo = TaskRunRepository(dao = runDao, transactions = FakeBoardTransactions(), now = { now })
        val boardRepo = TaskBoardRepository(
            dao = FakeWorkItemDAO(),
            transactions = FakeBoardTransactions(),
            now = { now },
        )

        val active = Uuid.random()
        seedRun(runRepo, active, TaskRunStateTag.RUNNING)
        // An old terminal that the retention sweep should delete (beyond the newest-0 window).
        val expired = Uuid.random()
        runDao.upsert(
            me.rerere.rikkahub.data.db.entity.TaskRunEntity(
                id = expired.toString(),
                conversationId = conversationId.toString(),
                parentToolCallId = "c",
                agentTypeId = "a",
                prompt = "p",
                latestState = TaskRunStateTag.SUCCEEDED.name,
                createdAt = now - 2 * thirtyDaysMs,
                updatedAt = now - 2 * thirtyDaysMs,
            )
        )

        val runner = TaskRecoveryRunner(taskRuns = runRepo, board = boardRepo)
        runner.runStartupRecovery(
            now = now,
            retentionMaxAgeMillis = thirtyDaysMs,
            retentionKeepNewestPerConversation = 0,
        )

        assertTrue("the active run is now Interrupted", runRepo.get(active) is TaskState.Interrupted)
        assertNull("the expired terminal run is swept", runDao.getById(expired.toString()))
    }

    @Test
    fun `runStartupRecovery is idempotent across two cold starts`() = runBlocking {
        val now = 100L * thirtyDaysMs
        val runRepo = TaskRunRepository(dao = FakeTaskRunDAO(), transactions = FakeBoardTransactions(), now = { now })
        val boardRepo = TaskBoardRepository(dao = FakeWorkItemDAO(), transactions = FakeBoardTransactions(), now = { now })
        val active = Uuid.random()
        seedRun(runRepo, active, TaskRunStateTag.RUNNING)

        val runner = TaskRecoveryRunner(taskRuns = runRepo, board = boardRepo)
        val first = runner.runStartupRecovery(now = now)
        val second = runner.runStartupRecovery(now = now)

        assertEquals("first cold start recovers the active row", 1, first.recoveredRuns)
        assertEquals("a second cold start recovers nothing (Interrupted is not active)", 0, second.recoveredRuns)
        assertTrue(runRepo.get(active) is TaskState.Interrupted)
    }
}
