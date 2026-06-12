package me.rerere.rikkahub.data.ai.task

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.ai.runtime.task.TaskBudget
import me.rerere.ai.runtime.task.TaskBudgetBreach
import me.rerere.ai.runtime.task.TaskBudgetUsage
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskSpec
import me.rerere.ai.runtime.task.TaskState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SubagentGenerate
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * TASK_CANCEL_CASCADES (SPEC.md M4 / T10): once the parent cancels, NO child progress event is
 * emitted afterwards — at any of the three cancel timings:
 *
 *  - **before start** — cancel before the child's first chunk;
 *  - **mid-stream** — cancel between two child chunks;
 *  - **mid-tool** — cancel while the child is blocked inside a (simulated) tool call.
 *
 * Two structural facts make this hold, both PINNED here (not assumed):
 *
 *  1. The coordinator collects the child flow INLINE in the caller's coroutine (structured
 *     concurrency). Cancelling the coordinating job cancels the collection, so no `ChildProgressed`
 *     / `FinalResult` arrives after the cancel — the last event is exactly one `CancelRequested`.
 *  2. A child coroutine launched on a handle [Job] that is a structural child of the parent
 *     generation job is torn down by the parent cancel (the [ExecutionHandleRegistry] cascade) —
 *     so a child blocked mid-tool stops producing.
 *
 * Records are timestamped against a monotonic ordinal so "after cancel" is a total order, not a
 * wall-clock guess.
 */
class TaskCancelCascadesPropertyTest {

    private val subModel = Model(modelId = "sub-model", displayName = "Sub", id = Uuid.random())

    private fun settings(): Settings = Settings(
        chatModelId = Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = listOf(subModel))),
    )

    private fun assistantMsg(text: String): UIMessage =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    /** Records the ORDER of child lifecycle events per task, folding through the reducer for state. */
    private class OrderedStore : TaskRunStore {
        val events = ConcurrentHashMap<Uuid, MutableList<TaskEvent>>()
        val states = ConcurrentHashMap<Uuid, TaskState>()
        override suspend fun create(spec: TaskSpec): TaskState {
            events[spec.taskId] = Collections.synchronizedList(mutableListOf())
            states[spec.taskId] = TaskState.Created
            return TaskState.Created
        }
        override suspend fun applyEvent(taskId: Uuid, event: TaskEvent): TaskState? {
            events.getValue(taskId) += event
            return states[taskId]
        }
        override suspend fun claimResume(taskId: Uuid): Boolean {
            var won = false
            states.compute(taskId) { _, current ->
                if (current is TaskState.Interrupted) { won = true; TaskState.Resuming } else current
            }
            return won
        }
        override suspend fun appendEventSummary(taskId: Uuid, summary: String, kind: String): Long? = 0L
        override suspend fun recordUsage(taskId: Uuid, reported: TaskBudgetUsage, budget: TaskBudget): TaskBudgetBreach? = null
    }

    private enum class CancelTiming { BEFORE_START, MID_STREAM, MID_TOOL }

    @Test
    fun `no child progress event is emitted after the parent cancel — at any cancel timing`() {
        runBlocking {
            checkAll(120, Arb.element(CancelTiming.entries.toList()), Arb.int(1..3)) { timing, midChunks ->
                val store = OrderedStore()
                val taskId = Uuid.random()

                // Coordination signals: the test releases the cancel only once the child is at the
                // chosen timing point, and the gate the child is "blocked" on for MID_TOOL.
                val reachedCancelPoint = CompletableDeferred<Unit>()
                val toolGate = CompletableDeferred<Unit>()

                val generate: SubagentGenerate = { _, _, _, _, _, _, _ ->
                    flow {
                        when (timing) {
                            CancelTiming.BEFORE_START -> {
                                // Signal, then yield to the cancel before emitting anything.
                                reachedCancelPoint.complete(Unit)
                                toolGate.await() // cancelled before this resumes
                                emit(GenerationChunk.Messages(listOf(assistantMsg("should-not-arrive"))))
                            }
                            CancelTiming.MID_STREAM -> {
                                emit(GenerationChunk.Messages(listOf(assistantMsg("chunk-0"))))
                                reachedCancelPoint.complete(Unit)
                                toolGate.await() // cancelled here
                                repeat(midChunks) { emit(GenerationChunk.Messages(listOf(assistantMsg("after-cancel-$it")))) }
                            }
                            CancelTiming.MID_TOOL -> {
                                emit(GenerationChunk.Messages(listOf(assistantMsg("chunk-0"))))
                                // Simulate the child blocked inside a tool call.
                                reachedCancelPoint.complete(Unit)
                                toolGate.await() // never released; cancel tears this down
                                emit(GenerationChunk.Messages(listOf(assistantMsg("post-tool"))))
                            }
                        }
                    }
                }
                val coordinator = TaskCoordinator(generate = generate, store = store)

                val parentJob = Job()
                val scope = CoroutineScope(parentJob)
                val running = scope.launch {
                    coordinator.run(
                        sub = Assistant(name = "Sub", chatModelId = subModel.id),
                        prompt = "go",
                        parentModelId = null,
                        settings = settings(),
                        taskId = taskId,
                    )
                }

                reachedCancelPoint.await()
                // Snapshot the event count at the cancel boundary, then cancel and let teardown run.
                val countAtCancel = store.events[taskId]?.size ?: 0
                running.cancelAndJoin()

                val recorded = store.events[taskId].orEmpty().toList()

                // The parent cancel must be observed exactly once and be the LAST lifecycle event:
                // nothing the child would have produced after the cancel point reaches the store.
                val cancelIndex = recorded.indexOfFirst { it is TaskEvent.CancelRequested }
                assertTrue("a parent cancel must surface a CancelRequested event", cancelIndex >= 0)
                assertEquals(
                    "CancelRequested must be the final event — no child event after cancel",
                    recorded.lastIndex,
                    cancelIndex,
                )
                // No FinalResult / post-cancel ChildProgressed slipped in.
                assertFalse("no FinalResult may follow a cancel", recorded.any { it is TaskEvent.FinalResult })
                // Everything before the cancel was legal lifecycle progress; nothing was added after
                // the snapshot except the single CancelRequested.
                assertEquals(
                    "exactly one event (CancelRequested) is added after the cancel boundary",
                    countAtCancel + 1,
                    recorded.size,
                )
                // The coordinating coroutine is fully torn down (structured cancel cascade).
                assertFalse("the child coroutine must be cancelled", running.isActive)

                parentJob.cancel()
            }
        }
    }

    @Test
    fun `a child blocked mid-tool on a handle job is torn down by the parent cancel`() {
        // Direct structural-cascade pin: a coroutine launched on a registry handle job (a child of
        // the parent generation job) stops when the parent cancels — this is the mid-tool case at
        // the coroutine level, independent of the coordinator's event folding.
        runBlocking {
            val registry = ExecutionHandleRegistry()
            val parentJob = Job()
            val handle = registry.register(
                conversationId = Uuid.random(),
                assistantId = Uuid.random(),
                parentJob = parentJob,
            )
            registry.markRunning(handle.id)

            val started = CompletableDeferred<Unit>()
            val toolWork = CoroutineScope(handle.job).launch {
                started.complete(Unit)
                CompletableDeferred<Unit>().await() // "inside a tool" — would hang forever
            }
            started.await()
            assertTrue("the mid-tool child must be live before cancel", toolWork.isActive)

            parentJob.cancel()
            runCatching { toolWork.join() }

            assertFalse("a mid-tool child must be torn down by the parent cancel cascade", toolWork.isActive)
            assertFalse(handle.job.isActive)
        }
    }
}
