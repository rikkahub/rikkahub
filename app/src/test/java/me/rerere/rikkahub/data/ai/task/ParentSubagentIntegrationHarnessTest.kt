package me.rerere.rikkahub.data.ai.task

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.ai.runtime.board.WorkItemStatus
import me.rerere.ai.runtime.contract.BoardMutationResult
import me.rerere.ai.runtime.contract.TaskApprovalGate
import me.rerere.ai.runtime.contract.WorkItemDraft
import me.rerere.ai.runtime.task.TaskApprovalDecision
import me.rerere.ai.runtime.task.TaskApprovalRequest
import me.rerere.ai.runtime.task.TaskBudget
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.buildSpawnTool
import me.rerere.rikkahub.data.ai.subagent.subagentBoardTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.TaskBoardRepository
import me.rerere.rikkahub.data.repository.TaskRunRepository
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeTaskRunDAO
import me.rerere.rikkahub.data.repository.fakes.FakeWorkItemDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Parent + N-subagent integration harness (SPEC.md M6 / T14): the REAL repositories
 * ([TaskBoardRepository], [TaskRunRepository]) over JVM DAO fakes, the REAL [TaskCoordinator]
 * over a scripted [me.rerere.rikkahub.data.ai.subagent.SubagentGenerate] fake, and the REAL
 * production spawn seam ([buildSpawnTool] -> handle registration -> handle-owned board tools ->
 * orphan release). Children run CONCURRENTLY against ONE shared per-conversation board.
 *
 * **Caveat (binding for anyone extending this file):** the fake transaction runner is a single
 * [kotlinx.coroutines.sync.Mutex] ([FakeBoardTransactions]) — one-writer-at-a-time, the same
 * granularity `Room.withTransaction` serializes at. That makes this harness valid for the
 * INTERLEAVING LOGIC (claim atomicity, blocker gating, release ordering across concurrent
 * children) but it is NOT trust in real SQL/Room semantics (typed columns, indices, transaction
 * rollback). Pinning those would need Robolectric or in-memory Room, which is a separate
 * ask-first decision — do not smuggle it in here.
 */
class ParentSubagentIntegrationHarnessTest {

    private val subModel = Model(modelId = "sub-model", displayName = "Sub", id = Uuid.random())
    private val settings = Settings(
        chatModelId = Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = listOf(subModel))),
    )
    private val sub = Assistant(name = "Worker", chatModelId = subModel.id, spawnable = true)

    private val denyAllGate = object : TaskApprovalGate {
        override suspend fun await(taskId: Uuid, request: TaskApprovalRequest): TaskApprovalDecision =
            TaskApprovalDecision.Denied()
    }

    /** One scripted child turn: the child's prompt plus its (handle-bound) tool pool. */
    private fun interface ChildScript {
        suspend fun run(prompt: String, tools: List<Tool>)
    }

    private class Harness {
        val conversationId = Uuid.random()
        val boardDao = FakeWorkItemDAO()
        val board = TaskBoardRepository(dao = boardDao, transactions = FakeBoardTransactions(), now = { 1_000L })
        val runDao = FakeTaskRunDAO()
        val runs = TaskRunRepository(dao = runDao, transactions = FakeBoardTransactions(), now = { 0L })
        val registry = ExecutionHandleRegistry()
        val handleIds = ConcurrentHashMap.newKeySet<String>()
        val jobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    }

    private suspend fun Harness.seedItem(subject: String, blockedBy: List<Uuid> = emptyList()): Uuid =
        (board.create(conversationId, WorkItemDraft(subject = subject, blockedBy = blockedBy)) as BoardMutationResult.Accepted)
            .snapshot.item.id

    /** The production spawn seam over this harness, children scripted by [script]. */
    private fun Harness.spawnTool(concurrency: Int, script: ChildScript): Tool = buildSpawnTool(
        spawnableAssistants = listOf(sub),
        coordinator = TaskCoordinator(
            generate = { _, _, messages, _, tools, _, _ ->
                flow {
                    val prompt = messages.first().parts.filterIsInstance<UIMessagePart.Text>().first().text
                    script.run(prompt, tools)
                    emit(GenerationChunk.Messages(listOf(UIMessage.assistant("done"))))
                }
            },
            store = runs,
            defaultBudget = TaskBudget(globalConcurrency = concurrency),
        ),
        parentModelId = null,
        settings = settings,
        registry = registry,
        buildSubagentTools = { spawned, handle ->
            handleIds += handle.id
            jobs[handle.id] = handle.job
            subagentBoardTools(
                repository = board,
                conversationId = conversationId,
                registry = registry,
                handle = handle,
                sub = spawned,
            )
        },
        releaseOrphanedClaims = { handleId -> board.releaseClaimsOf(handleId) },
        approvalGateFor = { denyAllGate },
        processingStatus = MutableStateFlow(null),
        progressLabel = { "running $it" },
        parentConversationId = conversationId,
    )

    private fun spawnArgs(worker: String) = buildJsonObject {
        put("subagent", sub.name)
        put("prompt", worker)
    }

    private suspend fun Tool.claim(id: Uuid): Boolean = update(id, "claim")
    private suspend fun Tool.complete(id: Uuid): Boolean = update(id, "complete")
    private suspend fun Tool.update(id: Uuid, action: String): Boolean {
        val output = execute(buildJsonObject {
            put("id", id.toString())
            put("action", action)
        })
        return (output.single() as UIMessagePart.Text).text.contains("\"ok\":true")
    }

    @Test
    fun `N concurrent subagents sweep one shared board to a sequential-equivalent terminal state`(): Unit = runBlocking {
        val h = Harness()
        val items = (1..12).map { h.seedItem("item-$it") }
        // Per-item count of ACCEPTED claims across all workers — the interleaving-equivalence
        // core: any sequential schedule grants each item exactly one accepted claim.
        val acceptedClaims = ConcurrentHashMap<Uuid, Int>()

        val spawn = h.spawnTool(concurrency = 4) { _, tools ->
            val update = tools.single { it.name == "task_update" }
            items.forEach { id ->
                if (update.claim(id)) {
                    acceptedClaims.merge(id, 1, Int::plus)
                    assertTrue("the claim winner must be able to complete its item", update.complete(id))
                }
            }
        }

        coroutineScope {
            (0 until 4).map { worker ->
                async(Dispatchers.Default) { spawn.execute(spawnArgs("worker-$worker")) }
            }.awaitAll()
        }

        // Sequential equivalence: every item Completed, owned by exactly the one handle whose
        // claim was accepted — and that acceptance happened exactly once per item.
        items.forEach { id ->
            val item = h.board.get(h.conversationId, id)!!.item
            assertEquals("every item ends Completed: $id", WorkItemStatus.Completed, item.status)
            assertEquals("exactly one accepted claim per item: $id", 1, acceptedClaims[id])
            assertTrue(
                "the final owner is one of the spawned handles: $id",
                item.ownerHandleId in h.handleIds,
            )
        }
        // Every child run reached its Succeeded terminal on the REAL persistence path.
        val states = h.runDao.listByStates(setOf(TaskRunStateTag.SUCCEEDED.name))
        assertEquals("all four child runs end SUCCEEDED", 4, states.size)
        // Every handle was torn down at child death.
        h.handleIds.forEach { assertNull("handle must be unregistered: $it", h.registry.get(it)) }
    }

    @Test
    fun `a two-subagent race on one item yields exactly one owner through the coordinator path`(): Unit = runBlocking {
        val h = Harness()
        val contested = h.seedItem("contested")
        val acceptedBy = ConcurrentHashMap.newKeySet<String>()

        val spawn = h.spawnTool(concurrency = 2) { prompt, tools ->
            val update = tools.single { it.name == "task_update" }
            if (update.claim(contested)) {
                acceptedBy += prompt
                assertTrue(update.complete(contested))
            }
        }

        coroutineScope {
            listOf("racer-a", "racer-b").map { worker ->
                async(Dispatchers.Default) { spawn.execute(spawnArgs(worker)) }
            }.awaitAll()
        }

        assertEquals("exactly one racer wins the claim", 1, acceptedBy.size)
        val item = h.board.get(h.conversationId, contested)!!.item
        assertEquals(WorkItemStatus.Completed, item.status)
        assertTrue("the item is owned by a spawned handle", item.ownerHandleId in h.handleIds)
    }

    @Test
    fun `one subagent holds multiple claims at once - no per-owner cap`(): Unit = runBlocking {
        val h = Harness()
        val items = (1..3).map { h.seedItem("multi-$it") }
        var observedOwners: Set<String?> = emptySet()

        val spawn = h.spawnTool(concurrency = 1) { _, tools ->
            val update = tools.single { it.name == "task_update" }
            items.forEach { assertTrue("multi-claim must be allowed (decision #5)", update.claim(it)) }
            // All three claims are held SIMULTANEOUSLY here; capture their owners mid-hold.
            observedOwners = items.map { h.board.get(h.conversationId, it)!!.item.ownerHandleId }.toSet()
            items.forEach { assertTrue(update.complete(it)) }
        }

        spawn.execute(spawnArgs("hoarder"))

        assertEquals("all simultaneous claims belong to the ONE handle", 1, observedOwners.size)
        assertTrue(observedOwners.single() in h.handleIds)
    }

    @Test
    fun `a blocked item is not claimable until its blocker completes`(): Unit = runBlocking {
        val h = Harness()
        val blocker = h.seedItem("blocker")
        val blocked = h.seedItem("blocked", blockedBy = listOf(blocker))

        val spawn = h.spawnTool(concurrency = 1) { _, tools ->
            val update = tools.single { it.name == "task_update" }
            assertTrue("claiming a blocked item must be REJECTED", !update.claim(blocked))
            assertTrue(update.claim(blocker))
            assertTrue(update.complete(blocker))
            assertTrue("the item becomes claimable once its blocker completed", update.claim(blocked))
            assertTrue(update.complete(blocked))
        }

        spawn.execute(spawnArgs("sequencer"))

        assertEquals(WorkItemStatus.Completed, h.board.get(h.conversationId, blocked)!!.item.status)
    }

    @Test
    fun `cancelling a child mid-hold releases every claim it held and cancels its run`(): Unit = runBlocking {
        val h = Harness()
        val held = listOf(h.seedItem("held-a"), h.seedItem("held-b"))

        val spawn = h.spawnTool(concurrency = 1) { _, tools ->
            val update = tools.single { it.name == "task_update" }
            held.forEach { assertTrue(update.claim(it)) }
            awaitCancellation() // hold the claims until the parent cancels this child
        }

        val child = launch(Dispatchers.Default) { spawn.execute(spawnArgs("doomed")) }
        withTimeout(5_000) {
            while (held.any { h.board.get(h.conversationId, it)!!.item.status != WorkItemStatus.InProgress }) {
                delay(10)
            }
        }
        child.cancelAndJoin()

        // Orphan release ran on the cancellation path (NonCancellable): every held claim is free
        // again, the run row reached its Cancelled terminal, and the handle is gone.
        held.forEach { id ->
            val item = h.board.get(h.conversationId, id)!!.item
            assertEquals("a cancelled child's claim must be released: $id", WorkItemStatus.Pending, item.status)
            assertNull(item.ownerHandleId)
        }
        assertEquals(
            "the run row must reach the Cancelled terminal",
            1,
            h.runDao.listByStates(setOf(TaskRunStateTag.CANCELLED.name)).size,
        )
        h.handleIds.forEach { id ->
            assertNull("the cancelled handle must be unregistered: $id", h.registry.get(id))
            assertTrue("its job must not pin the parent job: $id", h.jobs[id]!!.isCompleted)
        }
    }
}
