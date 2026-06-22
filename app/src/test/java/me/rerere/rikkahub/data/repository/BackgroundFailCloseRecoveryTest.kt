package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskSpec
import me.rerere.ai.runtime.task.TaskState
import me.rerere.rikkahub.data.ai.subagent.SubagentCompletion
import me.rerere.rikkahub.data.ai.task.TaskRecoveryRunner
import me.rerere.rikkahub.data.db.entity.AgentEventStatus
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import me.rerere.rikkahub.data.repository.fakes.FakeAgentEventDAO
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeTaskRunDAO
import me.rerere.rikkahub.data.repository.fakes.FakeWorkItemDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Slice-6 cold-start FAIL-CLOSE for detached background subagent runs. A background run whose parent
 * turn already ended must, on the next cold start, fold to terminal [TaskState.Failed] AND atomically
 * enqueue its [SubagentCompletion] (FAILED/"interrupted") — never the resumable [TaskState.Interrupted]
 * a foreground run gets. Idempotent across cold starts; AT_MOST_ONCE keeps a single completion.
 */
class BackgroundFailCloseRecoveryTest {

    private class FakeTransactions(
        private val runDao: FakeTaskRunDAO,
        private val eventDao: FakeAgentEventDAO,
    ) : BoardTransactionRunner {
        private val mutex = Mutex()
        override suspend fun <T> inTransaction(block: suspend () -> T): T = mutex.withLock {
            val runSnap = runDao.snapshot()
            val eventSnap = eventDao.snapshot()
            try {
                block()
            } catch (t: Throwable) {
                runDao.restore(runSnap)
                eventDao.restore(eventSnap)
                throw t
            }
        }
    }

    private var clock = 1L
    private val conversationId = Uuid.random()

    private fun repo(runDao: FakeTaskRunDAO, eventDao: FakeAgentEventDAO) = TaskRunRepository(
        dao = runDao,
        transactions = FakeTransactions(runDao, eventDao),
        agentEventDao = eventDao,
        now = { clock++ },
    )

    private suspend fun TaskRunRepository.seedRunning(taskId: Uuid, background: Boolean) {
        create(
            TaskSpec(
                taskId = taskId,
                parentConversationId = conversationId,
                parentToolCallId = "call",
                agentTypeId = "general",
                prompt = "p",
                isBackground = background,
            )
        )
        applyEvent(taskId, TaskEvent.Enqueued)
        applyEvent(taskId, TaskEvent.SlotClaimed)
        applyEvent(taskId, TaskEvent.ChildProgressed)
    }

    @Test
    fun `recoverBackgroundInterrupted fail-closes background runs and enqueues one FAILED completion`() = runBlocking {
        val runDao = FakeTaskRunDAO(); val eventDao = FakeAgentEventDAO()
        val repo = repo(runDao, eventDao)
        val bg = Uuid.random(); val fg = Uuid.random()
        repo.seedRunning(bg, background = true)
        repo.seedRunning(fg, background = false)

        val recovered = repo.recoverBackgroundInterrupted()

        assertEquals("only the background run is fail-closed", listOf(bg), recovered)
        assertTrue("the background run is now terminal Failed", repo.get(bg) is TaskState.Failed)
        assertTrue("the foreground run is untouched (still active)", repo.get(fg) is TaskState.Running)

        val pending = eventDao.listPending(conversationId.toString())
        assertEquals("exactly one durable completion for the background run", 1, pending.size)
        val e = pending.single()
        assertEquals(bg.toString(), e.dedupeKey)
        assertEquals(SubagentCompletion.KIND, e.kind)
        assertEquals(AgentEventStatus.PENDING.name, e.status)
        val p = Json.parseToJsonElement(e.payloadJson).jsonObject
        assertEquals("FAILED", p["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a second cold start fail-closes nothing and does not double-enqueue`() = runBlocking {
        val runDao = FakeTaskRunDAO(); val eventDao = FakeAgentEventDAO()
        val repo = repo(runDao, eventDao)
        val bg = Uuid.random()
        repo.seedRunning(bg, background = true)

        val first = repo.recoverBackgroundInterrupted()
        val second = repo.recoverBackgroundInterrupted()

        assertEquals("first cold start fail-closes the background run", listOf(bg), first)
        assertEquals("a second cold start finds no active background row", emptyList<Uuid>(), second)
        assertEquals("still exactly one completion", 1, eventDao.listPending(conversationId.toString()).size)
    }

    @Test
    fun `a background run the awaiter already terminated is not re-failed by recovery`() = runBlocking {
        val runDao = FakeTaskRunDAO(); val eventDao = FakeAgentEventDAO()
        val repo = repo(runDao, eventDao)
        val bg = Uuid.random()
        repo.seedRunning(bg, background = true)
        // The detached awaiter won the terminal race BEFORE the cold-start scan.
        repo.applyEvent(bg, TaskEvent.FinalResult("done"))

        val recovered = repo.recoverBackgroundInterrupted()

        assertEquals("an already-terminal background run is not in the active scan", emptyList<Uuid>(), recovered)
        assertTrue("its real terminal stands", repo.get(bg) is TaskState.Succeeded)
        val pending = eventDao.listPending(conversationId.toString())
        assertEquals(1, pending.size)
        assertEquals(
            "the SUCCEEDED completion is preserved, never overwritten by a recovery FAILED",
            "SUCCEEDED",
            Json.parseToJsonElement(pending.single().payloadJson).jsonObject["status"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `runStartupRecovery fail-closes background and folds foreground to Interrupted`() = runBlocking {
        val runDao = FakeTaskRunDAO(); val eventDao = FakeAgentEventDAO()
        val repo = repo(runDao, eventDao)
        val boardRepo = TaskBoardRepository(
            dao = FakeWorkItemDAO(),
            transactions = FakeBoardTransactions(),
            now = { clock++ },
        )
        val bg = Uuid.random(); val fg = Uuid.random()
        repo.seedRunning(bg, background = true)
        repo.seedRunning(fg, background = false)

        val runner = TaskRecoveryRunner(taskRuns = repo, board = boardRepo)
        val result = runner.runStartupRecovery(now = clock)

        assertEquals("one background fail-closed", 1, result.backgroundFailClosed)
        assertEquals("one foreground interrupted", 1, result.recoveredRuns)
        assertTrue("background -> terminal Failed", repo.get(bg) is TaskState.Failed)
        assertTrue("foreground -> resumable Interrupted", repo.get(fg) is TaskState.Interrupted)
        // The fail-closed background run enqueued its completion; the interrupted foreground one did not.
        assertEquals(1, eventDao.listPending(conversationId.toString()).size)
    }

    @Test
    fun `the generic interrupt scan never folds a background row to Interrupted`() = runBlocking {
        val runDao = FakeTaskRunDAO(); val eventDao = FakeAgentEventDAO()
        val repo = repo(runDao, eventDao)
        val bg = Uuid.random(); val fg = Uuid.random()
        repo.seedRunning(bg, background = true)
        repo.seedRunning(fg, background = false)

        // Even if the fail-close pass is SKIPPED (e.g. it threw and was swallowed), the generic scan
        // must touch only foreground rows — a background row stays active for the next cold start,
        // never folded to the resumable Interrupted (from which a terminal completion would be lost).
        val recovered = repo.recoverInterruptedRuns()

        assertEquals("only the foreground run is interrupted", listOf(fg), recovered)
        assertTrue("the foreground run is Interrupted", repo.get(fg) is TaskState.Interrupted)
        assertTrue("the background run is left active (still Running)", repo.get(bg) is TaskState.Running)
    }

    @Test
    fun `a conflicting forked anchor cannot overwrite the original`() = runBlocking {
        val runDao = FakeTaskRunDAO(); val eventDao = FakeAgentEventDAO()
        val repo = repo(runDao, eventDao)
        val bg = Uuid.random()
        repo.seedRunning(bg, background = true)

        val original = me.rerere.rikkahub.data.ai.task.SubagentToolAnchor(
            toolCallId = "call-1", toolNodeId = Uuid.random(), toolMessageId = Uuid.random(),
        )
        assertTrue("the first anchor attaches", repo.attachToolAnchor(bg, original))
        // Re-attaching the SAME anchor is idempotent.
        assertTrue("the same anchor re-attaches idempotently", repo.attachToolAnchor(bg, original))

        // A fork preserves the call/message ids but regenerates the node id: it must NOT overwrite.
        val forked = original.copy(toolNodeId = Uuid.random())
        assertFalse("a conflicting (forked) anchor is rejected", repo.attachToolAnchor(bg, forked))
        assertEquals("the original anchor is preserved", original, repo.getToolAnchor(bg))
    }

    @Test
    fun `the fail-close error never reports success`() = runBlocking {
        val runDao = FakeTaskRunDAO(); val eventDao = FakeAgentEventDAO()
        val repo = repo(runDao, eventDao)
        val bg = Uuid.random()
        repo.seedRunning(bg, background = true)
        repo.recoverBackgroundInterrupted()

        assertEquals(
            TaskRunStateTag.FAILED.name,
            runDao.getById(bg.toString())!!.latestState,
        )
        val state = repo.get(bg)
        assertTrue(state is TaskState.Failed)
        assertEquals(TaskRunRepository.BACKGROUND_INTERRUPTED_ERROR, (state as TaskState.Failed).error)
    }
}
