package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.ai.runtime.contract.TaskApprovalGate
import me.rerere.ai.runtime.task.TaskApprovalDecision
import me.rerere.ai.runtime.task.TaskApprovalRequest
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.task.ExecutionHandleRegistry
import me.rerere.rikkahub.data.ai.task.TaskCoordinator
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.TaskRunRepository
import me.rerere.rikkahub.data.repository.fakes.FakeAgentEventDAO
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeTaskRunDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Slice-4: the spawn tool's opt-in `background:true` path returns a NON-BLOCKING running marker
 * immediately (a non-empty, non-deferred Text — so the parent turn ends and never trips the
 * deferred-tool conversation gate) while the detached child runs to terminal on the app scope and
 * enqueues its durable completion (Slices 3 + 2). `background:false`/absent keeps the synchronous run.
 */
class SpawnToolBackgroundTest {

    private val subModel = Model(modelId = "sub-model", displayName = "Sub", id = Uuid.random())
    private val settings = Settings(
        chatModelId = Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = listOf(subModel))),
    )
    private val sub = Assistant(name = "Researcher", chatModelId = subModel.id, spawnable = true)
    private val conv = Uuid.random()

    private val denyGate = object : TaskApprovalGate {
        override suspend fun await(taskId: Uuid, request: TaskApprovalRequest): TaskApprovalDecision =
            TaskApprovalDecision.Denied()
    }

    private fun fixture(): Triple<FakeTaskRunDAO, FakeAgentEventDAO, CoroutineScope> {
        val runDao = FakeTaskRunDAO()
        val eventDao = FakeAgentEventDAO()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return Triple(runDao, eventDao, appScope)
    }

    private fun spawnTool(runDao: FakeTaskRunDAO, eventDao: FakeAgentEventDAO, appScope: CoroutineScope) =
        buildSpawnTool(
            spawnableAssistants = listOf(sub),
            coordinator = TaskCoordinator(
                generate = { _, _, _, _, _, _, _ ->
                    flowOf(GenerationChunk.Messages(listOf(UIMessage.assistant("done"))))
                },
                store = TaskRunRepository(
                    dao = runDao,
                    transactions = FakeBoardTransactions(),
                    agentEventDao = eventDao,
                    now = { 1L },
                ),
                appScope = appScope,
            ),
            parentModelId = null,
            settings = settings,
            registry = ExecutionHandleRegistry(),
            buildSubagentTools = { _, _ -> emptyList() },
            releaseOrphanedClaims = { },
            approvalGateFor = { denyGate },
            processingStatus = MutableStateFlow(null),
            progressLabel = { "running $it" },
            parentConversationId = conv,
            backgroundScope = appScope,
        )

    @Test
    fun `background true returns a non-blocking running marker and the detached child terminalises`() =
        runBlocking {
            val (runDao, eventDao, appScope) = fixture()
            val tool = spawnTool(runDao, eventDao, appScope)

            val out = tool.execute(
                buildJsonObject {
                    put("subagent", sub.name)
                    put("prompt", "long task")
                    put("background", true)
                }
            )

            // Output is a single non-empty Text running-marker (executed, not a deferred sentinel).
            assertEquals(1, out.size)
            val text = (out.single() as UIMessagePart.Text).text
            assertTrue("output is non-empty (isExecuted)", text.isNotBlank())
            val obj = Json.parseToJsonElement(text).jsonObject
            assertEquals("running", obj["status"]!!.jsonPrimitive.content)
            assertEquals(true, obj["background"]!!.jsonPrimitive.content.toBoolean())
            val taskId = obj["taskId"]!!.jsonPrimitive.content
            // The durable row exists immediately (START HANDSHAKE).
            assertTrue("row persisted before return", runDao.getById(taskId) != null)

            // Await the detached child, then assert terminal + one durable completion.
            appScope.coroutineContext.job.children.toList().joinAll()
            assertEquals(TaskRunStateTag.SUCCEEDED.name, runDao.getById(taskId)!!.latestState)
            val pending = eventDao.listPending(conv.toString())
            assertEquals(1, pending.size)
            assertEquals(SubagentCompletion.KIND, pending.single().kind)
        }

    @Test
    fun `background false runs synchronously and enqueues no completion`() = runBlocking {
        val (runDao, eventDao, appScope) = fixture()
        val tool = spawnTool(runDao, eventDao, appScope)

        val out = tool.execute(
            buildJsonObject {
                put("subagent", sub.name)
                put("prompt", "quick task")
                // no background flag
            }
        )
        // The synchronous path returns the task envelope, not the running marker, and never enqueues.
        val text = (out.single() as UIMessagePart.Text).text
        assertFalse("synchronous output is not a running marker", text.contains("\"status\":\"running\""))
        assertEquals(0, eventDao.listPending(conv.toString()).size)
    }

    /** A spawn tool whose child BLOCKS forever, sharing one coordinator/registry, for cap + leak tests. */
    private fun blockingSpawnTool(
        runDao: FakeTaskRunDAO,
        eventDao: FakeAgentEventDAO,
        appScope: CoroutineScope,
        registry: ExecutionHandleRegistry,
        settings: Settings = this.settings,
    ): Tool = buildSpawnTool(
        spawnableAssistants = listOf(sub),
        coordinator = TaskCoordinator(
            generate = { _, _, _, _, _, _, _ -> flow { awaitCancellation() } },
            store = TaskRunRepository(
                dao = runDao,
                transactions = FakeBoardTransactions(),
                agentEventDao = eventDao,
                now = { 1L },
            ),
            appScope = appScope,
        ),
        parentModelId = null,
        settings = settings,
        registry = registry,
        buildSubagentTools = { _, _ -> emptyList() },
        releaseOrphanedClaims = { },
        approvalGateFor = { denyGate },
        processingStatus = MutableStateFlow(null),
        progressLabel = { "running $it" },
        parentConversationId = conv,
        backgroundScope = appScope,
    )

    @Test
    fun `a spawn beyond the configured limit is rejected, not spawned`() = runBlocking {
        val (runDao, eventDao, appScope) = fixture()
        val registry = ExecutionHandleRegistry()
        // The user configured a max of 1 concurrent background subagent.
        val tool = blockingSpawnTool(
            runDao, eventDao, appScope, registry,
            settings = settings.copy(maxBackgroundSubagents = 1),
        )

        // First background spawn fills the only allowed slot (its child blocks forever).
        val first = tool.execute(buildJsonObject {
            put("subagent", sub.name); put("prompt", "long"); put("background", true)
        })
        assertEquals("running", Json.parseToJsonElement((first.single() as UIMessagePart.Text).text)
            .jsonObject["status"]!!.jsonPrimitive.content)

        // A spawn beyond the configured limit is admission-capped: a `rejected` marker, no new row, no
        // leaked handle (only the first run's handle remains live).
        val second = tool.execute(buildJsonObject {
            put("subagent", sub.name); put("prompt", "another"); put("background", true)
        })
        val secondObj = Json.parseToJsonElement((second.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("rejected", secondObj["status"]!!.jsonPrimitive.content)
        assertNull("a rejected spawn carries no taskId", secondObj["taskId"])
        assertEquals(
            "only the first run's row was created (admission precedes the handshake)",
            1,
            runDao.listByStates(TaskRunStateTag.ACTIVE.map { it.name }.toSet()).size,
        )
        assertEquals("the rejected spawn leaked no handle", 1, registry.listByConversation(conv).size)

        appScope.cancel()
    }

    @Test
    fun `a pre-launch failure tears down the handle instead of leaking it`() = runBlocking {
        val (runDao, eventDao, appScope) = fixture()
        val registry = ExecutionHandleRegistry()
        // settings with NO models: runBackground's synchronous model resolution throws before launch.
        val noModelSettings = Settings(chatModelId = Uuid.random(), providers = emptyList())
        val tool = blockingSpawnTool(runDao, eventDao, appScope, registry, settings = noModelSettings)

        var thrown: Throwable? = null
        try {
            tool.execute(buildJsonObject {
                put("subagent", sub.name); put("prompt", "go"); put("background", true)
            })
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull("a pre-launch model failure surfaces as a tool error", thrown)
        assertTrue("the leaked handle was torn down", registry.listByConversation(conv).isEmpty())
        // No detached job was registered, so nothing is left to cancel.
        appScope.cancel()
    }
}
