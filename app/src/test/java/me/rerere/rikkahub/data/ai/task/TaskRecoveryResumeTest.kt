package me.rerere.rikkahub.data.ai.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.ai.runtime.board.WorkItemStatus
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SubagentGenerate
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import me.rerere.rikkahub.data.db.entity.WorkItemEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.BoardActor
import me.rerere.rikkahub.data.repository.TaskBoardRepository
import me.rerere.rikkahub.data.repository.TaskRunRepository
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeTaskRunDAO
import me.rerere.rikkahub.data.repository.fakes.FakeWorkItemDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Tests for startup recovery + summary-injection resume (SPEC.md M6 / T13, maintainer
 * decisions #1/#3/#5). Three behaviors are pinned here, all at the repository/coordinator seam
 * against DAO fakes (CI runs no instrumented tests):
 *
 * - **orphan-release-all** ([TaskBoardRepository.releaseClaimsOf]): recovery releases EVERY board
 *   claim a dead execution handle owns (decision #5), through the same validated repository path
 *   the UI/tools use — leases are only a backstop, not the mechanism.
 * - **startup recovery scan** ([TaskRunRepository.recoverInterruptedRuns]): every ACTIVE task row
 *   is folded to `Interrupted` (no auto-resume, no side-effect replay); terminals and already-
 *   `Interrupted` rows are left untouched.
 * - **resume-single-handle** ([TaskCoordinator.resume]): resuming an `Interrupted` run keeps the
 *   SAME task id, moves `Interrupted -> Resuming`, seeds the child with the original prompt + the
 *   persisted progress summary, and yields exactly ONE active handle — a second concurrent resume
 *   of the same task is rejected because the persisted state is no longer `Interrupted`
 *   (single-active-handle enforced in persisted state, decision #3).
 */
class TaskRecoveryResumeTest {

    private val conversationId = Uuid.random()
    private val subModel = Model(modelId = "sub-model", displayName = "Sub", id = Uuid.random())

    private fun settings(): Settings = Settings(
        chatModelId = Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = listOf(subModel))),
    )

    private fun assistantMsg(text: String): UIMessage =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    // --- shared board fixture -------------------------------------------------------------------

    private class BoardFixture(now: () -> Long = { 5_000L }) {
        val dao = FakeWorkItemDAO()
        val repository = TaskBoardRepository(dao = dao, transactions = FakeBoardTransactions(), now = now)
    }

    private fun workItem(
        id: Uuid,
        conversationId: Uuid,
        subject: String,
        status: WorkItemStatus,
        ownerHandleId: String?,
        leaseExpiresAt: Long?,
    ): WorkItemEntity = WorkItemEntity(
        id = id.toString(),
        conversationId = conversationId.toString(),
        subject = subject,
        status = status.name,
        ownerHandleId = ownerHandleId,
        ownerName = ownerHandleId,
        leaseExpiresAt = leaseExpiresAt,
        createdAt = 0L,
        updatedAt = 0L,
    )

    // --- orphan-release-all (decision #5) -------------------------------------------------------

    @Test
    fun `releaseClaimsOf frees every claim a dead handle holds and leaves others untouched`() = runBlocking {
        // Lease still in the FUTURE: release must NOT depend on lease expiry (the lease is only a
        // backstop). Owner "dead" holds two claims; another owner holds one that must survive.
        val board = BoardFixture(now = { 1_000L })
        val dead = "handle-dead"
        val alive = "handle-alive"
        val claimA = Uuid.random()
        val claimB = Uuid.random()
        val claimOther = Uuid.random()
        board.dao.insert(workItem(claimA, conversationId, "a", WorkItemStatus.InProgress, dead, leaseExpiresAt = 9_999L))
        board.dao.insert(workItem(claimB, conversationId, "b", WorkItemStatus.InProgress, dead, leaseExpiresAt = 9_999L))
        board.dao.insert(workItem(claimOther, conversationId, "c", WorkItemStatus.InProgress, alive, leaseExpiresAt = 9_999L))

        val released = board.repository.releaseClaimsOf(dead)

        assertEquals("both of the dead handle's claims are released", 2, released)
        // The dead handle's items are back to Pending with no owner — re-claimable by anyone.
        listOf(claimA, claimB).forEach { id ->
            val snapshot = board.repository.get(conversationId, id)!!
            assertEquals(WorkItemStatus.Pending, snapshot.item.status)
            assertNull(snapshot.item.ownerHandleId)
        }
        // The live owner's claim is untouched.
        val other = board.repository.get(conversationId, claimOther)!!
        assertEquals(WorkItemStatus.InProgress, other.item.status)
        assertEquals(alive, other.item.ownerHandleId)
    }

    @Test
    fun `releaseClaimsOf releases a claim whose lease has NOT expired`() = runBlocking {
        // Direct restatement of decision #5: orphan recovery releases ALL claims of a dead handle,
        // the lease being only a backstop — so a non-expired lease must still be released.
        val board = BoardFixture(now = { 1_000L })
        val dead = "dead"
        val id = Uuid.random()
        board.dao.insert(workItem(id, conversationId, "x", WorkItemStatus.InProgress, dead, leaseExpiresAt = Long.MAX_VALUE))

        assertEquals(1, board.repository.releaseClaimsOf(dead))
        assertEquals(WorkItemStatus.Pending, board.repository.get(conversationId, id)!!.item.status)
    }

    // --- startup recovery scan (no side-effect replay, no auto-resume) --------------------------

    private class RunFixture {
        val dao = FakeTaskRunDAO()
        var t = 1_000L
        val repository = TaskRunRepository(dao = dao, transactions = FakeBoardTransactions(), now = { ++t })
    }

    private suspend fun seedRun(
        f: RunFixture,
        taskId: Uuid,
        toState: TaskRunStateTag,
    ) {
        val spec = me.rerere.ai.runtime.task.TaskSpec(
            taskId = taskId,
            parentConversationId = conversationId,
            parentToolCallId = "call",
            agentTypeId = "agent",
            prompt = "do the thing",
        )
        f.repository.create(spec)
        // Drive to the requested persisted state through legal events.
        when (toState) {
            TaskRunStateTag.RUNNING -> {
                f.repository.applyEvent(taskId, TaskEvent.Enqueued)
                f.repository.applyEvent(taskId, TaskEvent.SlotClaimed)
                f.repository.applyEvent(taskId, TaskEvent.ChildProgressed)
            }
            TaskRunStateTag.QUEUED -> f.repository.applyEvent(taskId, TaskEvent.Enqueued)
            TaskRunStateTag.SUCCEEDED -> {
                f.repository.applyEvent(taskId, TaskEvent.Enqueued)
                f.repository.applyEvent(taskId, TaskEvent.SlotClaimed)
                f.repository.applyEvent(taskId, TaskEvent.ChildProgressed)
                f.repository.applyEvent(taskId, TaskEvent.FinalResult("already done"))
            }
            else -> error("unsupported seed state $toState")
        }
    }

    @Test
    fun `recoverInterruptedRuns marks active rows Interrupted and leaves terminals untouched`() = runBlocking {
        val f = RunFixture()
        val running = Uuid.random()
        val queued = Uuid.random()
        val succeeded = Uuid.random()
        seedRun(f, running, TaskRunStateTag.RUNNING)
        seedRun(f, queued, TaskRunStateTag.QUEUED)
        seedRun(f, succeeded, TaskRunStateTag.SUCCEEDED)

        val recovered = f.repository.recoverInterruptedRuns()

        assertEquals("both active rows are recovered, the terminal is not", setOf(running, queued), recovered.toSet())
        assertTrue(f.repository.get(running) is TaskState.Interrupted)
        assertTrue(f.repository.get(queued) is TaskState.Interrupted)
        // The terminal row is never touched — recovery never replays a finished run.
        assertEquals(TaskState.Succeeded("already done"), f.repository.get(succeeded))
    }

    @Test
    fun `recoverInterruptedRuns is idempotent - already-Interrupted rows are not re-recovered`() = runBlocking {
        val f = RunFixture()
        val running = Uuid.random()
        seedRun(f, running, TaskRunStateTag.RUNNING)

        val first = f.repository.recoverInterruptedRuns()
        assertEquals(setOf(running), first.toSet())
        // A second scan (e.g. a second cold start with no resume in between) recovers nothing —
        // Interrupted is not in the ACTIVE set, so it is never re-marked.
        val second = f.repository.recoverInterruptedRuns()
        assertTrue("Interrupted rows are not active and must not be re-recovered", second.isEmpty())
        assertTrue(f.repository.get(running) is TaskState.Interrupted)
    }

    // --- resume-single-handle (decisions #1/#3) -------------------------------------------------

    private fun capturingGenerate(captured: MutableList<List<UIMessage>>, result: String): SubagentGenerate =
        { _, _, messages, _, _, _, _ ->
            captured += messages
            flowOf(GenerationChunk.Messages(listOf(assistantMsg(result))))
        }

    @Test
    fun `resume keeps the same task id seeds prompt plus summary and reaches Succeeded`() = runBlocking {
        val f = RunFixture()
        val taskId = Uuid.random()
        seedRun(f, taskId, TaskRunStateTag.RUNNING)
        f.repository.applyEvent(taskId, TaskEvent.ProcessInterrupted("got halfway: parsed 3 files"))
        assertTrue(f.repository.get(taskId) is TaskState.Interrupted)

        val captured = mutableListOf<List<UIMessage>>()
        val coordinator = TaskCoordinator(generate = capturingGenerate(captured, "finished"), store = f.repository)

        val result = coordinator.resume(
            taskId = taskId,
            sub = Assistant(name = "Sub", chatModelId = subModel.id),
            prompt = "do the thing",
            progressSummary = "got halfway: parsed 3 files",
            parentModelId = null,
            settings = settings(),
        )

        assertEquals("finished", result.text)
        assertEquals(TaskState.Succeeded("finished"), result.state)
        // Same task id, driven to a terminal — the run is NOT a fresh task.
        assertEquals(TaskState.Succeeded("finished"), f.repository.get(taskId))
        // The seeded child message carries BOTH the original prompt AND the persisted summary
        // (decision #1: resume injects the summary as context, no transcript replay exists).
        val seeded = captured.single().joinToString("\n") { msg ->
            msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        }
        assertTrue("seed must carry the original prompt: $seeded", seeded.contains("do the thing"))
        assertTrue("seed must carry the persisted summary: $seeded", seeded.contains("got halfway: parsed 3 files"))
    }

    @Test
    fun `double resume of the same task is rejected - exactly one active handle`() = runBlocking {
        val f = RunFixture()
        val taskId = Uuid.random()
        seedRun(f, taskId, TaskRunStateTag.RUNNING)
        f.repository.applyEvent(taskId, TaskEvent.ProcessInterrupted("partial"))

        val captured = mutableListOf<List<UIMessage>>()
        val coordinator = TaskCoordinator(generate = capturingGenerate(captured, "done"), store = f.repository)

        // First resume consumes the Interrupted->Resuming edge.
        coordinator.resume(
            taskId = taskId,
            sub = Assistant(name = "Sub", chatModelId = subModel.id),
            prompt = "do the thing",
            progressSummary = "partial",
            parentModelId = null,
            settings = settings(),
        )

        // The task already ran to a terminal; a second resume must be rejected (the persisted
        // state is no longer Interrupted), so no SECOND handle is ever spawned.
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                coordinator.resume(
                    taskId = taskId,
                    sub = Assistant(name = "Sub", chatModelId = subModel.id),
                    prompt = "do the thing",
                    progressSummary = "partial",
                    parentModelId = null,
                    settings = settings(),
                )
            }
        }
        assertTrue("rejection must name resumability: ${error.message}", error.message!!.contains("resum"))
        // Exactly one child was ever spawned for this task.
        assertEquals("a rejected double-resume must not spawn a second handle", 1, captured.size)
    }

    @Test
    fun `two concurrent resumes spawn exactly one handle - the Resuming window does not duplicate`() = runBlocking {
        // The dangerous window (review finding #2): the first resume has moved the run to Resuming
        // but the child has not produced its first event yet (so it is NOT Running). A second
        // resume arriving in this window must be rejected — otherwise it spawns a second handle for
        // the same task, violating decision #3 (one active handle, resume never duplicates).
        val f = RunFixture()
        val taskId = Uuid.random()
        seedRun(f, taskId, TaskRunStateTag.RUNNING)
        f.repository.applyEvent(taskId, TaskEvent.ProcessInterrupted("partial"))

        val spawnCount = AtomicInteger(0)
        val firstReachedResuming = CompletableDeferred<Unit>()
        val releaseFirstChild = CompletableDeferred<Unit>()
        // The first resume's child suspends BEFORE emitting, holding the run in Resuming; the
        // second resume races in during that hold.
        val gatedGenerate: SubagentGenerate = { _, _, _, _, _, _, _ ->
            spawnCount.incrementAndGet()
            flow {
                firstReachedResuming.complete(Unit)
                releaseFirstChild.await()
                emit(GenerationChunk.Messages(listOf(assistantMsg("done"))))
            }
        }
        val coordinator = TaskCoordinator(generate = gatedGenerate, store = f.repository)

        coroutineScope {
            val first = async {
                coordinator.resume(
                    taskId = taskId,
                    sub = Assistant(name = "Sub", chatModelId = subModel.id),
                    prompt = "do the thing",
                    progressSummary = "partial",
                    parentModelId = null,
                    settings = settings(),
                )
            }
            try {
                // Wait until the first resume is parked in Resuming (child spawned, pre-emit).
                firstReachedResuming.await()
                assertTrue("first resume must be parked in Resuming", f.repository.get(taskId) is TaskState.Resuming)

                // Second resume in the Resuming window MUST be rejected at the claim, BEFORE it can
                // spawn anything. With the fix it throws synchronously; with the bug it slips past
                // the gate into execute() and hangs on the held child — the timeout converts that
                // hang into a deterministic RED failure rather than a frozen test.
                val error = withTimeout(2_000) {
                    try {
                        coordinator.resume(
                            taskId = taskId,
                            sub = Assistant(name = "Sub", chatModelId = subModel.id),
                            prompt = "do the thing",
                            progressSummary = "partial",
                            parentModelId = null,
                            settings = settings(),
                        )
                        null
                    } catch (e: IllegalStateException) {
                        e
                    }
                }
                assertTrue("second concurrent resume must be rejected: $error", error is IllegalStateException)
            } finally {
                releaseFirstChild.complete(Unit)
            }
            first.await()
        }
        assertEquals("exactly one child handle may exist for a task across concurrent resumes", 1, spawnCount.get())
    }

    @Test
    fun `resume rejects a run that is not Interrupted`() = runBlocking {
        val f = RunFixture()
        val taskId = Uuid.random()
        seedRun(f, taskId, TaskRunStateTag.SUCCEEDED)

        val coordinator = TaskCoordinator(
            generate = capturingGenerate(mutableListOf(), "x"),
            store = f.repository,
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                coordinator.resume(
                    taskId = taskId,
                    sub = Assistant(name = "Sub", chatModelId = subModel.id),
                    prompt = "do the thing",
                    progressSummary = "",
                    parentModelId = null,
                    settings = settings(),
                )
            }
        }
        // A terminal run is untouched by a rejected resume.
        assertEquals(TaskState.Succeeded("already done"), f.repository.get(taskId))
    }
}
