package me.rerere.rikkahub.data.ai.task

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.runtime.task.TaskBudget
import me.rerere.ai.runtime.task.TaskBudgetUsage
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskSpec
import me.rerere.ai.runtime.task.TaskState
import me.rerere.ai.runtime.task.TaskStateReducer
import me.rerere.rikkahub.data.ai.subagent.SubagentGenerate
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Review mustFix regression (PR #274 gate): every exit from an ACTIVE persisted state must reach
 * a terminal (or resumable) state on EVERY exit path — including the pre-execute() window.
 *
 * Three verified leaks of the same invariant:
 * 1. run(): cancellation while suspended on slot acquisition (state Queued, before execute())
 *    had no handler — the run stayed Queued forever.
 * 2. resume(): after claimResume committed Interrupted -> Resuming, a pre-execute() failure
 *    (model resolution) or cancellation (slot wait) stranded the run in Resuming, blocking
 *    every future resume.
 * 3. execute()'s own CancellationException handler called the SUSPENDING store from an
 *    already-cancelled coroutine without NonCancellable — against a Room-backed store the
 *    terminal write itself was cancelled at its first suspension point. The in-memory test
 *    fake never suspended, which is exactly why this passed before: this suite's store
 *    yield()s first, like Room.
 */
class TaskCoordinatorLifecycleClosureTest {

    private val subModel = Model(modelId = "sub-model", displayName = "Sub", id = Uuid.random())

    private fun settingsWith(vararg models: Model): Settings = Settings(
        chatModelId = Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = models.toList())),
    )

    /** Like the coordinator's Room-backed store, every operation SUSPENDS before mutating. */
    private class SuspendingStore : TaskRunStore {
        val states = ConcurrentHashMap<Uuid, TaskState>()
        val events = ConcurrentHashMap<Uuid, MutableList<TaskEvent>>()

        fun seed(taskId: Uuid, state: TaskState) {
            states[taskId] = state
            events[taskId] = mutableListOf()
        }

        override suspend fun create(spec: TaskSpec): TaskState {
            yield()
            seed(spec.taskId, TaskState.Created)
            return TaskState.Created
        }

        override suspend fun applyEvent(taskId: Uuid, event: TaskEvent): TaskState? {
            yield() // first suspension point — a cancelled caller dies HERE unless NonCancellable
            val current = states[taskId] ?: return null
            events.getValue(taskId) += event
            val next = TaskStateReducer.reduce(current, event)
            states[taskId] = next
            return next
        }

        override suspend fun claimResume(taskId: Uuid): Boolean {
            yield()
            var won = false
            states.compute(taskId) { _, current ->
                if (current is TaskState.Interrupted) {
                    won = true
                    TaskState.Resuming
                } else current
            }
            return won
        }

        override suspend fun appendEventSummary(taskId: Uuid, summary: String, kind: String): Long? {
            yield()
            return 1L
        }

        override suspend fun recordUsage(taskId: Uuid, reported: TaskBudgetUsage, budget: TaskBudget) =
            budget.firstBreach(TaskBudgetUsage())
    }

    private val hangingGenerate: SubagentGenerate = { _, _, _, _, _, _, _ ->
        flow { awaitCancellation() }
    }

    private fun coordinator(store: TaskRunStore, generate: SubagentGenerate = hangingGenerate) =
        TaskCoordinator(generate = generate, store = store, defaultBudget = TaskBudget(globalConcurrency = 1))

    private fun sub() = Assistant(name = "Worker", chatModelId = subModel.id)

    /** Occupies the single global slot until the returned job is cancelled. */
    private fun kotlinx.coroutines.CoroutineScope.occupySlot(c: TaskCoordinator, settings: Settings) =
        launch {
            c.run(sub = sub(), prompt = "occupier", parentModelId = null, settings = settings)
        }

    @Test
    fun `cancelling a run queued on the slot semaphore records a Cancelled terminal`() = runBlocking {
        val store = SuspendingStore()
        val c = coordinator(store)
        val settings = settingsWith(subModel)

        val occupier = occupySlot(c, settings)
        while (store.states.values.none { it is TaskState.Starting || it is TaskState.Running }) yield()

        val queuedId = Uuid.random()
        val queued = launch {
            c.run(sub = sub(), prompt = "queued", parentModelId = null, settings = settings, taskId = queuedId)
        }
        while (store.states[queuedId] != TaskState.Queued) yield()

        queued.cancel()
        queued.join()

        assertEquals(
            "a run cancelled while Queued must reach the Cancelled terminal, not stay Queued",
            TaskState.Cancelled, store.states[queuedId],
        )
        occupier.cancel()
        occupier.join()
        Unit
    }

    @Test
    fun `resume that fails before spawning restores Interrupted instead of stranding Resuming`() = runBlocking {
        val store = SuspendingStore()
        val c = coordinator(store)
        val taskId = Uuid.random()
        store.seed(taskId, TaskState.Interrupted("progress so far"))

        try {
            // Settings WITHOUT the sub's model: resolution fails after claimResume committed Resuming.
            c.resume(
                taskId = taskId, sub = sub(), prompt = "task", progressSummary = "progress so far",
                parentModelId = null, settings = settingsWith(/* no models */),
            )
            fail("resume with an unresolvable model must throw")
        } catch (expected: IllegalStateException) {
            // expected
        }

        val state = store.states[taskId]
        assertTrue(
            "a failed pre-execute resume must restore Interrupted (resumable), got $state",
            state is TaskState.Interrupted,
        )
    }

    @Test
    fun `cancelling a resume waiting for a slot restores Interrupted`() = runBlocking {
        val store = SuspendingStore()
        val c = coordinator(store)
        val settings = settingsWith(subModel)

        val occupier = occupySlot(c, settings)
        while (store.states.values.none { it is TaskState.Starting || it is TaskState.Running }) yield()

        val taskId = Uuid.random()
        store.seed(taskId, TaskState.Interrupted("progress so far"))
        val resuming = launch {
            c.resume(
                taskId = taskId, sub = sub(), prompt = "task", progressSummary = "progress so far",
                parentModelId = null, settings = settings,
            )
        }
        while (store.states[taskId] != TaskState.Resuming) yield()

        resuming.cancel()
        resuming.join()

        val state = store.states[taskId]
        assertTrue(
            "a resume cancelled while waiting for a slot must restore Interrupted, got $state",
            state is TaskState.Interrupted,
        )
        occupier.cancel()
        occupier.join()
        Unit
    }

    /** A store that PARKS (suspends indefinitely) on one specific event kind, once. */
    private class GatedStore(private val parkOn: (TaskEvent) -> Boolean) : TaskRunStore {
        val inner = SuspendingStore()
        val parked = kotlinx.coroutines.CompletableDeferred<Unit>()
        private var tripped = false

        val states get() = inner.states
        fun seed(taskId: Uuid, state: TaskState) = inner.seed(taskId, state)

        override suspend fun create(spec: TaskSpec) = inner.create(spec)
        override suspend fun applyEvent(taskId: Uuid, event: TaskEvent): TaskState? {
            if (!tripped && parkOn(event)) {
                tripped = true
                parked.complete(Unit)
                awaitCancellation() // the cancelled write never lands — exactly a cancelled Room call
            }
            return inner.applyEvent(taskId, event)
        }

        override suspend fun claimResume(taskId: Uuid) = inner.claimResume(taskId)
        override suspend fun appendEventSummary(taskId: Uuid, summary: String, kind: String) =
            inner.appendEventSummary(taskId, summary, kind)
        override suspend fun recordUsage(taskId: Uuid, reported: TaskBudgetUsage, budget: TaskBudget) =
            inner.recordUsage(taskId, reported, budget)
    }

    @Test
    fun `cancellation striking the Enqueued write still terminal-closes the created row`() = runBlocking {
        val store = GatedStore { it is TaskEvent.Enqueued }
        val c = coordinator(store)
        val settings = settingsWith(subModel)
        val taskId = Uuid.random()

        val job = launch {
            c.run(sub = sub(), prompt = "task", parentModelId = null, settings = settings, taskId = taskId)
        }
        store.parked.await()
        job.cancel()
        job.join()

        assertEquals(
            "a cancellation during the Enqueued write must still drive the Created row to Cancelled",
            TaskState.Cancelled, store.states[taskId],
        )
    }

    @Test
    fun `cancellation striking the SlotClaimed write still terminal-closes the run`() = runBlocking {
        val store = GatedStore { it is TaskEvent.SlotClaimed }
        val c = coordinator(store)
        val settings = settingsWith(subModel)
        val taskId = Uuid.random()

        val job = launch {
            c.run(sub = sub(), prompt = "task", parentModelId = null, settings = settings, taskId = taskId)
        }
        store.parked.await()
        job.cancel()
        job.join()

        assertEquals(
            "a cancellation during the SlotClaimed write must still drive the run to Cancelled",
            TaskState.Cancelled, store.states[taskId],
        )
    }

    @Test
    fun `cancellation during execute persists Cancelled through a SUSPENDING store`() = runBlocking {
        val store = SuspendingStore()
        val c = coordinator(store)
        val settings = settingsWith(subModel)
        val taskId = Uuid.random()

        val running = launch {
            c.run(sub = sub(), prompt = "long job", parentModelId = null, settings = settings, taskId = taskId)
        }
        while (store.states[taskId] !is TaskState.Starting && store.states[taskId] !is TaskState.Running) yield()

        running.cancel()
        running.join()

        assertEquals(
            "the Cancelled terminal must be persisted even though the store suspends (NonCancellable write)",
            TaskState.Cancelled, store.states[taskId],
        )
    }
}
