package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.ai.runtime.board.WorkItemStatus
import me.rerere.ai.runtime.contract.BoardMutationResult
import me.rerere.ai.runtime.contract.WorkItemDraft
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.task.ExecutionHandle
import me.rerere.rikkahub.data.ai.task.ExecutionHandleRegistry
import me.rerere.rikkahub.data.ai.task.TaskCoordinator
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.TaskBoardRepository
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeWorkItemDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Pins the PRODUCTION spawn-path handle wiring (review findings #1/#5, the ownership nit from
 * #274): every spawned subagent runs under a live [ExecutionHandleRegistry] handle, its board
 * claims are owned by the HANDLE id (not a synthetic per-assistant actor), and when the handle
 * dies — success, failure, or cancellation — orphan release frees EVERY claim it still holds
 * while completed work keeps its owner attribution.
 *
 * Driven through the real [buildSpawnTool] -> [TaskCoordinator] -> board-tool path with a fake
 * engine, the same seam production uses, so the wiring (not just [me.rerere.rikkahub.data.ai.task.BoardPortAdapter.forHandle]
 * in isolation) is what is pinned.
 */
class SpawnToolHandleLifecycleTest {

    private val subModel = Model(modelId = "sub-model", displayName = "Sub", id = Uuid.random())
    private val settings = me.rerere.rikkahub.data.datastore.Settings(
        chatModelId = Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = listOf(subModel))),
    )
    private val sub = Assistant(name = "Researcher", chatModelId = subModel.id, spawnable = true)
    private val conversationId = Uuid.random()

    private class Fixture {
        val dao = FakeWorkItemDAO()
        val repository = TaskBoardRepository(dao = dao, transactions = FakeBoardTransactions(), now = { 1_000L })
        val registry = ExecutionHandleRegistry()
        var handle: ExecutionHandle? = null
    }

    private suspend fun Fixture.seedItem(subject: String): Uuid =
        (repository.create(conversationId, WorkItemDraft(subject = subject)) as BoardMutationResult.Accepted)
            .snapshot.item.id

    /**
     * The spawn tool under test: child engine = a fake that claims [toClaim], completes
     * [toComplete] of them, and finishes — leaving the rest InProgress as the orphan claims the
     * handle's death must release.
     */
    private fun Fixture.spawnTool(toClaim: List<Uuid>, toComplete: Set<Uuid>) = buildSpawnTool(
        spawnableAssistants = listOf(sub),
        coordinator = TaskCoordinator(
            generate = { _, _, _, _, tools, _, _ ->
                flow {
                    val update = tools.single { it.name == "task_update" }
                    toClaim.forEach { id ->
                        update.execute(buildJsonObject {
                            put("id", id.toString())
                            put("action", "claim")
                        })
                    }
                    toComplete.forEach { id ->
                        update.execute(buildJsonObject {
                            put("id", id.toString())
                            put("action", "complete")
                        })
                    }
                    emit(GenerationChunk.Messages(listOf(UIMessage.assistant("done"))))
                }
            },
        ),
        parentModelId = null,
        settings = settings,
        registry = registry,
        buildSubagentTools = { spawned, handle ->
            this.handle = handle
            subagentBoardTools(
                repository = repository,
                conversationId = conversationId,
                registry = registry,
                handle = handle,
                sub = spawned,
            )
        },
        releaseOrphanedClaims = { handleId -> repository.releaseClaimsOf(handleId) },
        approvalGateFor = {
                object : me.rerere.ai.runtime.contract.TaskApprovalGate {
                    override suspend fun await(
                        taskId: kotlin.uuid.Uuid,
                        request: me.rerere.ai.runtime.task.TaskApprovalRequest,
                    ): me.rerere.ai.runtime.task.TaskApprovalDecision =
                        me.rerere.ai.runtime.task.TaskApprovalDecision.Denied()
                }
            },
        processingStatus = kotlinx.coroutines.flow.MutableStateFlow(null),
        progressLabel = { "running $it" },
        parentConversationId = conversationId,
    )

    @Test
    fun `claims are owned by the execution handle and completed work keeps its attribution`(): Unit = runBlocking {
        val fx = Fixture()
        val done = fx.seedItem("will-complete")
        val spawn = fx.spawnTool(toClaim = listOf(done), toComplete = setOf(done))

        spawn.execute(buildJsonObject {
            put("subagent", sub.name)
            put("prompt", "work the board")
        })

        assertNotNull("the spawn path must hand the subagent its execution handle", fx.handle)
        val handle = fx.handle!!
        val snapshot = fx.repository.get(conversationId, done)!!
        assertEquals(WorkItemStatus.Completed, snapshot.item.status)
        assertEquals(
            "the completed item's owner must be the execution handle id, not a synthetic actor",
            handle.id,
            snapshot.item.ownerHandleId,
        )
        assertEquals("the owner display name is the subagent's name", sub.name, snapshot.item.ownerName)
    }

    @Test
    fun `every claim still held when the handle dies is released - multi-claim orphan recovery`(): Unit = runBlocking {
        val fx = Fixture()
        val finished = fx.seedItem("finished")
        val orphanA = fx.seedItem("orphan-a")
        val orphanB = fx.seedItem("orphan-b")
        val spawn = fx.spawnTool(toClaim = listOf(finished, orphanA, orphanB), toComplete = setOf(finished))

        spawn.execute(buildJsonObject {
            put("subagent", sub.name)
            put("prompt", "claim three, finish one")
        })

        // The child died holding two claims; BOTH must be released (multi-claim per handle,
        // decision #5) so other workers can take the items over without waiting for the lease.
        listOf(orphanA, orphanB).forEach { id ->
            val item = fx.repository.get(conversationId, id)!!.item
            assertEquals("an orphaned claim must be released to Pending: $id", WorkItemStatus.Pending, item.status)
            assertNull("a released item has no owner: $id", item.ownerHandleId)
        }
        // The completed item is untouched by orphan release.
        assertEquals(WorkItemStatus.Completed, fx.repository.get(conversationId, finished)!!.item.status)

        // The handle is gone from the registry, and its job no longer pins the parent job (this
        // runBlocking would hang on an incomplete child Job if unregister leaked it).
        assertNull("the dead handle must be unregistered", fx.registry.get(fx.handle!!.id))
        assertTrue("the handle job must be completed on unregister", fx.handle!!.job.isCompleted)
    }

    @Test
    fun `a failing child still releases its claims and unregisters its handle`(): Unit = runBlocking {
        val fx = Fixture()
        val orphan = fx.seedItem("orphan")
        val spawn = buildSpawnTool(
            spawnableAssistants = listOf(sub),
            coordinator = TaskCoordinator(
                generate = { _, _, _, _, tools, _, _ ->
                    flow {
                        tools.single { it.name == "task_update" }.execute(buildJsonObject {
                            put("id", orphan.toString())
                            put("action", "claim")
                        })
                        throw IllegalStateException("provider exploded")
                    }
                },
            ),
            parentModelId = null,
            settings = settings,
            registry = fx.registry,
            buildSubagentTools = { spawned, handle ->
                fx.handle = handle
                subagentBoardTools(fx.repository, conversationId, fx.registry, handle, spawned)
            },
            releaseOrphanedClaims = { handleId -> fx.repository.releaseClaimsOf(handleId) },
            approvalGateFor = {
                object : me.rerere.ai.runtime.contract.TaskApprovalGate {
                    override suspend fun await(
                        taskId: kotlin.uuid.Uuid,
                        request: me.rerere.ai.runtime.task.TaskApprovalRequest,
                    ): me.rerere.ai.runtime.task.TaskApprovalDecision =
                        me.rerere.ai.runtime.task.TaskApprovalDecision.Denied()
                }
            },
            processingStatus = kotlinx.coroutines.flow.MutableStateFlow(null),
            progressLabel = { "running $it" },
            parentConversationId = conversationId,
        )

        spawn.execute(buildJsonObject {
            put("subagent", sub.name)
            put("prompt", "claim then die")
        })

        val item = fx.repository.get(conversationId, orphan)!!.item
        assertEquals("a failed child's claim must be released", WorkItemStatus.Pending, item.status)
        assertNull(item.ownerHandleId)
        assertNull("the failed handle must be unregistered", fx.registry.get(fx.handle!!.id))
    }

    @Test
    fun `a throwing tool assembly still tears the handle down - no parent job pin`(): Unit = runBlocking {
        // Review mustFix #3a: buildSubagentTools runs after the handle is registered; if it
        // throws outside the lifecycle closure the handle's child Job is never completed and the
        // parent generation job hangs in `completing` forever.
        val fx = Fixture()
        val spawn = buildSpawnTool(
            spawnableAssistants = listOf(sub),
            coordinator = TaskCoordinator(generate = { _, _, _, _, _, _, _ -> flow {} }),
            parentModelId = null,
            settings = settings,
            registry = fx.registry,
            buildSubagentTools = { _, handle ->
                fx.handle = handle
                throw IllegalStateException("tool assembly exploded")
            },
            releaseOrphanedClaims = { handleId -> fx.repository.releaseClaimsOf(handleId) },
            approvalGateFor = {
                object : me.rerere.ai.runtime.contract.TaskApprovalGate {
                    override suspend fun await(
                        taskId: kotlin.uuid.Uuid,
                        request: me.rerere.ai.runtime.task.TaskApprovalRequest,
                    ): me.rerere.ai.runtime.task.TaskApprovalDecision =
                        me.rerere.ai.runtime.task.TaskApprovalDecision.Denied()
                }
            },
            processingStatus = kotlinx.coroutines.flow.MutableStateFlow(null),
            progressLabel = { "running $it" },
            parentConversationId = conversationId,
        )

        runCatching {
            spawn.execute(buildJsonObject {
                put("subagent", sub.name)
                put("prompt", "never starts")
            })
        }

        assertNull("the handle must be unregistered", fx.registry.get(fx.handle!!.id))
        assertTrue("the handle job must be completed", fx.handle!!.job.isCompleted)
    }

    @Test
    fun `a throwing orphan release still unregisters the handle - no parent job pin`(): Unit = runBlocking {
        // Review mustFix #3b: unregister must be unconditional — a failing claim release must
        // not skip handle Job completion (the lease then bounds whatever the release left held).
        val fx = Fixture()
        val orphan = fx.seedItem("left-held")
        val spawn = buildSpawnTool(
            spawnableAssistants = listOf(sub),
            coordinator = TaskCoordinator(
                generate = { _, _, _, _, tools, _, _ ->
                    flow {
                        tools.single { it.name == "task_update" }.execute(buildJsonObject {
                            put("id", orphan.toString())
                            put("action", "claim")
                        })
                        emit(GenerationChunk.Messages(listOf(UIMessage.assistant("done"))))
                    }
                },
            ),
            parentModelId = null,
            settings = settings,
            registry = fx.registry,
            buildSubagentTools = { spawned, handle ->
                fx.handle = handle
                subagentBoardTools(fx.repository, conversationId, fx.registry, handle, spawned)
            },
            releaseOrphanedClaims = { throw IllegalStateException("release path exploded") },
            approvalGateFor = {
                object : me.rerere.ai.runtime.contract.TaskApprovalGate {
                    override suspend fun await(
                        taskId: kotlin.uuid.Uuid,
                        request: me.rerere.ai.runtime.task.TaskApprovalRequest,
                    ): me.rerere.ai.runtime.task.TaskApprovalDecision =
                        me.rerere.ai.runtime.task.TaskApprovalDecision.Denied()
                }
            },
            processingStatus = kotlinx.coroutines.flow.MutableStateFlow(null),
            progressLabel = { "running $it" },
            parentConversationId = conversationId,
        )

        runCatching {
            spawn.execute(buildJsonObject {
                put("subagent", sub.name)
                put("prompt", "claim then fail to release")
            })
        }

        assertNull("the handle must be unregistered despite the failed release", fx.registry.get(fx.handle!!.id))
        assertTrue("the handle job must be completed despite the failed release", fx.handle!!.job.isCompleted)
    }

    @Test
    fun `a release failure during cancellation must not mask the cancellation`(): Unit = runBlocking {
        // Review mustFix: a throw out of the teardown finally would REPLACE the in-flight
        // CancellationException, breaking cooperative cancellation. If that happened here, the
        // launched child would surface an IllegalStateException to this runBlocking scope and
        // fail the test; with the fix the cancellation wins and cancelAndJoin completes cleanly.
        val fx = Fixture()
        val held = fx.seedItem("held")
        val claimed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val spawn = buildSpawnTool(
            spawnableAssistants = listOf(sub),
            coordinator = TaskCoordinator(
                generate = { _, _, _, _, tools, _, _ ->
                    flow {
                        tools.single { it.name == "task_update" }.execute(buildJsonObject {
                            put("id", held.toString())
                            put("action", "claim")
                        })
                        claimed.complete(Unit)
                        kotlinx.coroutines.awaitCancellation()
                    }
                },
            ),
            parentModelId = null,
            settings = settings,
            registry = fx.registry,
            buildSubagentTools = { spawned, handle ->
                fx.handle = handle
                subagentBoardTools(fx.repository, conversationId, fx.registry, handle, spawned)
            },
            releaseOrphanedClaims = { throw IllegalStateException("release path exploded mid-cancel") },
            approvalGateFor = {
                object : me.rerere.ai.runtime.contract.TaskApprovalGate {
                    override suspend fun await(
                        taskId: kotlin.uuid.Uuid,
                        request: me.rerere.ai.runtime.task.TaskApprovalRequest,
                    ): me.rerere.ai.runtime.task.TaskApprovalDecision =
                        me.rerere.ai.runtime.task.TaskApprovalDecision.Denied()
                }
            },
            processingStatus = kotlinx.coroutines.flow.MutableStateFlow(null),
            progressLabel = { "running $it" },
            parentConversationId = conversationId,
        )

        val child = launch(kotlinx.coroutines.Dispatchers.Default) {
            spawn.execute(buildJsonObject {
                put("subagent", sub.name)
                put("prompt", "claim then hang")
            })
        }
        claimed.await()
        child.cancelAndJoin()

        assertTrue("the child must end CANCELLED, not failed", child.isCancelled)
        assertNull("the handle is unregistered even on this path", fx.registry.get(fx.handle!!.id))
        assertTrue(fx.handle!!.job.isCompleted)
    }
}
