package me.rerere.rikkahub.data.ai.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
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
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.TaskRunRepository
import me.rerere.rikkahub.data.repository.fakes.FakeAgentEventDAO
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeTaskRunDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Slice-3: [TaskCoordinator.runBackground] performs the START HANDSHAKE (durable row persisted before
 * return) and drives the child on the injected app-lifetime scope — DETACHED from the caller — whose
 * terminal folds the durable completion (Slice 2). The child runs on `appScope`, a different scope
 * from the caller's, so the next turn's generation-job cancel can never reach it.
 */
class TaskCoordinatorBackgroundTest {

    private val subModel = Model(modelId = "sub-model", displayName = "Sub", id = Uuid.random())

    private fun settingsWith(vararg models: Model, maxBackground: Int = 0): Settings = Settings(
        chatModelId = Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = models.toList())),
        maxBackgroundSubagents = maxBackground,
    )

    private fun assistantMsg(text: String): UIMessage =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    private val doneGenerate: SubagentGenerate =
        { _, _, _, _, _, _, _ -> flowOf(GenerationChunk.Messages(listOf(assistantMsg("done")))) }

    @Test
    fun `runBackground persists the row before return and the detached child terminalises + enqueues a completion`() =
        runBlocking {
            val runDao = FakeTaskRunDAO()
            val eventDao = FakeAgentEventDAO()
            val store = TaskRunRepository(
                dao = runDao,
                transactions = FakeBoardTransactions(),
                agentEventDao = eventDao,
                now = { 1L },
            )
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = TaskCoordinator(generate = doneGenerate, store = store, appScope = appScope)

            val conv = Uuid.random()
            val taskId = coordinator.runBackground(
                sub = Assistant(name = "Sub", chatModelId = subModel.id),
                prompt = "go",
                parentModelId = null,
                settings = settingsWith(subModel),
                parentConversationId = conv,
            )

            // START HANDSHAKE: the durable row exists synchronously, the moment runBackground returns.
            assertNotNull("row persisted before return", runDao.getById(taskId.toString()))

            // Await the DETACHED child — it runs on appScope, not this runBlocking scope.
            appScope.coroutineContext.job.children.toList().joinAll()

            assertEquals(
                TaskRunStateTag.SUCCEEDED.name,
                runDao.getById(taskId.toString())!!.latestState,
            )
            val pending = eventDao.listPending(conv.toString())
            assertEquals("detached terminal enqueued exactly one completion", 1, pending.size)
            assertEquals(taskId.toString(), pending.single().dedupeKey)
        }

    private val blockingGenerate: SubagentGenerate =
        { _, _, _, _, _, _, _ -> flow { awaitCancellation() } }

    @Test
    fun `a configured limit of 1 admits one run and refuses a concurrent second, freeing the slot on cancel`() =
        runBlocking {
            val runDao = FakeTaskRunDAO()
            val eventDao = FakeAgentEventDAO()
            val store = TaskRunRepository(
                dao = runDao,
                transactions = FakeBoardTransactions(),
                agentEventDao = eventDao,
                now = { 1L },
            )
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            // The child blocks forever, holding its slot; the user configured a max of 1.
            val coordinator = TaskCoordinator(generate = blockingGenerate, store = store, appScope = appScope)
            val conv = Uuid.random()
            val limited = settingsWith(subModel, maxBackground = 1)

            val first = coordinator.runBackground(
                sub = Assistant(name = "A", chatModelId = subModel.id),
                prompt = "go", parentModelId = null, settings = limited,
                parentConversationId = conv,
            )
            assertEquals("first run holds the only allowed slot", setOf(first), coordinator.backgroundTaskIds())

            // A second concurrent background run is admission-capped (BEFORE any durable row is created).
            val capped = assertThrows(BackgroundCapExceededException::class.java) {
                runBlocking {
                    coordinator.runBackground(
                        sub = Assistant(name = "B", chatModelId = subModel.id),
                        prompt = "go2", parentModelId = null, settings = limited,
                        parentConversationId = conv,
                    )
                }
            }
            assertEquals(1, capped.cap)
            assertEquals("the refused run never registered", setOf(first), coordinator.backgroundTaskIds())

            // Cancelling the first frees the slot; a third run is then admitted.
            assertTrue(coordinator.cancelBackground(first))
            appScope.coroutineContext.job.children.toList().joinAll()
            assertNull("the slot is free after cancel", coordinator.backgroundTaskIds().firstOrNull())

            val third = coordinator.runBackground(
                sub = Assistant(name = "C", chatModelId = subModel.id),
                prompt = "go3", parentModelId = null, settings = limited,
                parentConversationId = conv,
            )
            assertEquals(setOf(third), coordinator.backgroundTaskIds())
            appScope.cancel()
        }

    @Test
    fun `the default (unlimited) admits multiple concurrent background runs in parallel`() =
        runBlocking {
            val runDao = FakeTaskRunDAO()
            val eventDao = FakeAgentEventDAO()
            val store = TaskRunRepository(
                dao = runDao,
                transactions = FakeBoardTransactions(),
                agentEventDao = eventDao,
                now = { 1L },
            )
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = TaskCoordinator(generate = blockingGenerate, store = store, appScope = appScope)
            val conv = Uuid.random()
            // Default settings => maxBackgroundSubagents = 0 = unlimited.
            val unlimited = settingsWith(subModel)

            val ids = (1..3).map { i ->
                coordinator.runBackground(
                    sub = Assistant(name = "A$i", chatModelId = subModel.id),
                    prompt = "go$i", parentModelId = null, settings = unlimited,
                    parentConversationId = conv,
                )
            }
            // All three are admitted and live concurrently — none refused, none serialized away.
            assertEquals(ids.toSet(), coordinator.backgroundTaskIds())

            ids.forEach { coordinator.cancelBackground(it) }
            appScope.coroutineContext.job.children.toList().joinAll()
            appScope.cancel()
        }

    @Test
    fun `cancelBackgroundForConversation cancels only that conversation's runs`() =
        runBlocking {
            val runDao = FakeTaskRunDAO()
            val eventDao = FakeAgentEventDAO()
            val store = TaskRunRepository(
                dao = runDao,
                transactions = FakeBoardTransactions(),
                agentEventDao = eventDao,
                now = { 1L },
            )
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = TaskCoordinator(generate = blockingGenerate, store = store, appScope = appScope)
            val convA = Uuid.random(); val convB = Uuid.random()
            val settings = settingsWith(subModel)

            suspend fun spawn(conv: Uuid, name: String) = coordinator.runBackground(
                sub = Assistant(name = name, chatModelId = subModel.id),
                prompt = "go", parentModelId = null, settings = settings, parentConversationId = conv,
            )
            val a1 = spawn(convA, "A1"); val a2 = spawn(convA, "A2"); val b1 = spawn(convB, "B1")
            assertEquals(setOf(a1, a2, b1), coordinator.backgroundTaskIds())

            // Deleting conversation A cancels exactly its two runs; B's run is untouched. Under the
            // Unconfined dispatcher the cancellation + completion-handler removal run synchronously, so
            // we can assert the live set directly (no joinAll on b1, which would block forever).
            assertEquals(2, coordinator.cancelBackgroundForConversation(convA))
            assertEquals("only conversation B's run remains live", setOf(b1), coordinator.backgroundTaskIds())

            coordinator.cancelBackground(b1)
            appScope.cancel()
        }

    /** Delegating store that PARKS inside create() until released, so a test can cancel the caller
     *  precisely mid-handshake. */
    private class GatedCreateStore(
        private val inner: TaskRunStore,
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : TaskRunStore {
        override suspend fun create(spec: TaskSpec): TaskState {
            entered.complete(Unit)
            release.await()
            return inner.create(spec)
        }
        override suspend fun applyEvent(taskId: Uuid, event: TaskEvent): TaskState? = inner.applyEvent(taskId, event)
        override suspend fun claimResume(taskId: Uuid): Boolean = inner.claimResume(taskId)
        override suspend fun appendEventSummary(taskId: Uuid, summary: String, kind: String): Long? =
            inner.appendEventSummary(taskId, summary, kind)
        override suspend fun recordUsage(taskId: Uuid, reported: TaskBudgetUsage, budget: TaskBudget): TaskBudgetBreach? =
            inner.recordUsage(taskId, reported, budget)
        override suspend fun attachToolAnchor(taskId: Uuid, anchor: SubagentToolAnchor): Boolean =
            inner.attachToolAnchor(taskId, anchor)
        override suspend fun getToolAnchor(taskId: Uuid): SubagentToolAnchor? = inner.getToolAnchor(taskId)
    }

    @Test
    fun `a caller cancelled mid-handshake still completes a consistent, cancellable background run`() =
        runBlocking {
            val runDao = FakeTaskRunDAO()
            val eventDao = FakeAgentEventDAO()
            val inner = TaskRunRepository(
                dao = runDao,
                transactions = FakeBoardTransactions(),
                agentEventDao = eventDao,
                now = { 1L },
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store = GatedCreateStore(inner, entered, release)
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = TaskCoordinator(generate = blockingGenerate, store = store, appScope = appScope)
            val taskId = Uuid.random()
            val conv = Uuid.random()

            // Run on a SEPARATE caller scope so we can cancel it independently of runBlocking.
            val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runJob = caller.launch {
                coordinator.runBackground(
                    sub = Assistant(name = "A", chatModelId = subModel.id),
                    prompt = "go", parentModelId = null, settings = settingsWith(subModel),
                    parentConversationId = conv, taskId = taskId,
                )
            }
            entered.await()      // we are suspended INSIDE create() — the handshake is in progress
            runJob.cancel()      // cancel the caller mid-handshake
            release.complete(Unit)
            runJob.join()        // NonCancellable handshake completes despite the cancel

            // No phantom: the durable row exists AND a live, cancellable coordinator job is registered.
            assertNotNull("the row was persisted despite the mid-handshake cancel", runDao.getById(taskId.toString()))
            assertEquals("the background run is registered (cancellable, not a phantom)",
                setOf(taskId), coordinator.backgroundTaskIds())
            assertTrue("the registered run can be cancelled from the sheet", coordinator.cancelBackground(taskId))

            appScope.coroutineContext.job.children.toList().joinAll()
            caller.cancel()
            appScope.cancel()
        }

    /** A dispatcher that QUEUES every block until [runAll] — lets a test interleave a cancel before
     *  the detached job's body is ever dispatched (the pre-start-cancel window). */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }
        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    @Test
    fun `a background run cancelled before its body is dispatched still terminalises and tears down`() =
        runBlocking {
            val runDao = FakeTaskRunDAO()
            val eventDao = FakeAgentEventDAO()
            val store = TaskRunRepository(
                dao = runDao,
                transactions = FakeBoardTransactions(),
                agentEventDao = eventDao,
                now = { 1L },
            )
            val manual = ManualDispatcher()
            val appScope = CoroutineScope(SupervisorJob() + manual)
            val coordinator = TaskCoordinator(generate = blockingGenerate, store = store, appScope = appScope)
            val conv = Uuid.random()
            val taskId = Uuid.random()
            var teardownRan = false

            // runBackground completes the handshake + start() synchronously, but the LAZY body is only
            // QUEUED on the manual dispatcher (never run yet).
            coordinator.runBackground(
                sub = Assistant(name = "A", chatModelId = subModel.id),
                prompt = "go", parentModelId = null, settings = settingsWith(subModel),
                parentConversationId = conv, taskId = taskId,
                onTerminal = { teardownRan = true },
            )
            assertEquals("the run is registered", setOf(taskId), coordinator.backgroundTaskIds())

            // Cancel in the pre-start window: the body block is still queued, not executed.
            assertTrue(coordinator.cancelBackground(taskId))
            // Now run everything: the cancelled body never executes (its finally never runs), but the
            // invokeOnCompletion fallback must drive the row to a terminal + run onTerminal.
            manual.runAll()

            val finalState = store.get(taskId)
            assertTrue("the row reached a terminal despite the body never running", finalState is TaskState.Cancelled)
            assertTrue("the handle teardown (onTerminal) ran via the fallback", teardownRan)
            assertEquals("the cancel enqueued exactly one completion", 1, eventDao.listPending(conv.toString()).size)
            assertNull("the run is no longer registered", coordinator.backgroundTaskIds().firstOrNull())

            appScope.cancel()
        }
}
