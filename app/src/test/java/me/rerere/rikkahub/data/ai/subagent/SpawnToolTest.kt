package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.rikkahub.data.ai.task.ExecutionHandleRegistry
import me.rerere.rikkahub.data.ai.task.TaskCoordinator
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * JVM unit tests for [buildSpawnTool] (issue #201; rewired onto [TaskCoordinator] in SPEC.md M4).
 * The factory is Android-free, so its `execute` is driven directly with a fake [TaskCoordinator]
 * (a [TaskCoordinator] over a fake `generate` seam — no Room, no provider).
 *
 * Two regressions pinned here:
 *  - processingStatus is RESTORED on every terminal path (success AND the error() throw on an
 *    unknown subagent) — a stale "Running <sub>" label must not leak into the parent loading UI.
 *  - needsApproval=true tools are GATED through the parent approval gate (Gap A) — kept in the
 *    pool with needsApproval rewritten off — and the sub's pool is actually used (not emptyList()).
 */
class SpawnToolTest {

    private val subModel = Model(modelId = "sub-model", displayName = "Sub", id = Uuid.random())

    private fun settingsWith(vararg models: Model): Settings = Settings(
        chatModelId = Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = models.toList())),
    )

    private fun tool(name: String, needsApproval: Boolean = false): Tool =
        Tool(name = name, description = name, needsApproval = needsApproval, execute = { emptyList() })

    /** A coordinator whose fake engine just returns one assistant text and captures the tool pool. */
    private fun fakeCoordinator(
        capturedTools: MutableList<Tool>,
        store: me.rerere.rikkahub.data.ai.task.TaskRunStore = me.rerere.rikkahub.data.ai.task.NoopTaskRunStore,
    ): TaskCoordinator = TaskCoordinator(
        generate = { _, _, _, _, tools, _, _ ->
            capturedTools.clear()
            capturedTools.addAll(tools)
            flowOf(
                GenerationChunk.Messages(
                    listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("done"))))
                )
            )
        },
        store = store,
    )

    /** Captures the [me.rerere.ai.runtime.task.TaskSpec] the coordinator persists on create. */
    private class CapturingStore : me.rerere.rikkahub.data.ai.task.TaskRunStore by me.rerere.rikkahub.data.ai.task.NoopTaskRunStore {
        var spec: me.rerere.ai.runtime.task.TaskSpec? = null
        override suspend fun create(spec: me.rerere.ai.runtime.task.TaskSpec): me.rerere.ai.runtime.task.TaskState {
            this.spec = spec
            return me.rerere.ai.runtime.task.TaskState.Created
        }
    }

    @Test
    fun `execute threads the parent conversation id through to the persisted task spec`() {
        // The persisted task row must be associated with the REAL spawning conversation, not a
        // random UUID — per-conversation lookup (board panel, retention, cleanup) keys on it
        // (review finding #2).
        val store = CapturingStore()
        val status = MutableStateFlow<String?>(null)
        val sub = Assistant(name = "Researcher", chatModelId = subModel.id, spawnable = true)
        val conversationId = Uuid.random()
        val tool = buildSpawnTool(
            spawnableAssistants = listOf(sub),
            coordinator = fakeCoordinator(mutableListOf(), store = store),
            parentModelId = null,
            settings = settingsWith(subModel),
            registry = ExecutionHandleRegistry(),
            buildSubagentTools = { _, _ -> emptyList() },
            releaseOrphanedClaims = {},
            approvalGateFor = {
                object : me.rerere.ai.runtime.contract.TaskApprovalGate {
                    override suspend fun await(
                        taskId: kotlin.uuid.Uuid,
                        request: me.rerere.ai.runtime.task.TaskApprovalRequest,
                    ): me.rerere.ai.runtime.task.TaskApprovalDecision =
                        me.rerere.ai.runtime.task.TaskApprovalDecision.Denied()
                }
            },
            processingStatus = status,
            progressLabel = { "Running $it" },
            parentConversationId = conversationId,
        )

        runBlocking { tool.execute(spawnArgs("Researcher")) }

        assertEquals(conversationId, store.spec!!.parentConversationId)
    }

    @Test
    fun `execute emits the structured task envelope, not bare final text`() {
        // The tool output must be the {task:{...}} envelope so the live renderer reads the terminal
        // status / budget, not a bare-text string that always degrades to "Done" (review finding
        // #1). NoopTaskRunStore makes applyEvent return null, so the terminal degrades to Succeeded.
        val status = MutableStateFlow<String?>(null)
        val sub = Assistant(name = "Researcher", chatModelId = subModel.id, spawnable = true)
        val tool = buildSpawnTool(
            spawnableAssistants = listOf(sub),
            coordinator = fakeCoordinator(mutableListOf()),
            parentModelId = null,
            settings = settingsWith(subModel),
            registry = ExecutionHandleRegistry(),
            buildSubagentTools = { _, _ -> emptyList() },
            releaseOrphanedClaims = {},
            approvalGateFor = {
                object : me.rerere.ai.runtime.contract.TaskApprovalGate {
                    override suspend fun await(
                        taskId: kotlin.uuid.Uuid,
                        request: me.rerere.ai.runtime.task.TaskApprovalRequest,
                    ): me.rerere.ai.runtime.task.TaskApprovalDecision =
                        me.rerere.ai.runtime.task.TaskApprovalDecision.Denied()
                }
            },
            processingStatus = status,
            progressLabel = { "Running $it" },
            parentConversationId = Uuid.random(),
        )

        val output = runBlocking { tool.execute(spawnArgs("Researcher")) }

        val text = (output.single() as UIMessagePart.Text).text
        val task = kotlinx.serialization.json.Json.parseToJsonElement(text)
            .let { it as kotlinx.serialization.json.JsonObject }["task"]
        assertTrue("the tool output must carry a {task:{...}} envelope, was: $text", task != null)
    }

    private fun spawnArgs(subagent: String, prompt: String = "go") = buildJsonObject {
        put("subagent", subagent)
        put("prompt", prompt)
    }

    @Test
    fun `execute restores processingStatus to its prior value on success`() {
        val status = MutableStateFlow<String?>(null)
        val sub = Assistant(name = "Researcher", chatModelId = subModel.id, spawnable = true)
        val tool = buildSpawnTool(
            spawnableAssistants = listOf(sub),
            coordinator = fakeCoordinator(mutableListOf()),
            parentModelId = null,
            settings = settingsWith(subModel),
            registry = ExecutionHandleRegistry(),
            buildSubagentTools = { _, _ -> emptyList() },
            releaseOrphanedClaims = {},
            approvalGateFor = {
                object : me.rerere.ai.runtime.contract.TaskApprovalGate {
                    override suspend fun await(
                        taskId: kotlin.uuid.Uuid,
                        request: me.rerere.ai.runtime.task.TaskApprovalRequest,
                    ): me.rerere.ai.runtime.task.TaskApprovalDecision =
                        me.rerere.ai.runtime.task.TaskApprovalDecision.Denied()
                }
            },
            processingStatus = status,
            progressLabel = { "Running $it" },
            parentConversationId = Uuid.random(),
        )

        runBlocking { tool.execute(spawnArgs("Researcher")) }

        assertNull("processingStatus must be cleared back to its prior value", status.value)
    }

    @Test
    fun `execute restores processingStatus even when the subagent is unknown (error throw)`() {
        val status = MutableStateFlow<String?>("prior")
        val sub = Assistant(name = "Researcher", chatModelId = subModel.id, spawnable = true)
        val tool = buildSpawnTool(
            spawnableAssistants = listOf(sub),
            coordinator = fakeCoordinator(mutableListOf()),
            parentModelId = null,
            settings = settingsWith(subModel),
            registry = ExecutionHandleRegistry(),
            buildSubagentTools = { _, _ -> emptyList() },
            releaseOrphanedClaims = {},
            approvalGateFor = {
                object : me.rerere.ai.runtime.contract.TaskApprovalGate {
                    override suspend fun await(
                        taskId: kotlin.uuid.Uuid,
                        request: me.rerere.ai.runtime.task.TaskApprovalRequest,
                    ): me.rerere.ai.runtime.task.TaskApprovalDecision =
                        me.rerere.ai.runtime.task.TaskApprovalDecision.Denied()
                }
            },
            processingStatus = status,
            progressLabel = { "Running $it" },
            parentConversationId = Uuid.random(),
        )

        runBlocking {
            runCatching { tool.execute(spawnArgs("Nonexistent")) }
        }

        // The unknown-subagent error() is thrown before the status is set, so it must remain
        // exactly the prior value — and never a stale "Running ..." from a partial set.
        assertEquals("prior", status.value)
    }

    @Test
    fun `execute gates needsApproval tools instead of dropping them and uses the sub's own pool`() {
        val capturedTools = mutableListOf<Tool>()
        val status = MutableStateFlow<String?>(null)
        val sub = Assistant(name = "Researcher", chatModelId = subModel.id, spawnable = true)
        val tool = buildSpawnTool(
            spawnableAssistants = listOf(sub),
            coordinator = fakeCoordinator(capturedTools),
            parentModelId = null,
            settings = settingsWith(subModel),
            registry = ExecutionHandleRegistry(),
            buildSubagentTools = { _, _ ->
                listOf(tool("mcp__search"), tool("ask_user", needsApproval = true))
            },
            releaseOrphanedClaims = {},
            approvalGateFor = {
                object : me.rerere.ai.runtime.contract.TaskApprovalGate {
                    override suspend fun await(
                        taskId: kotlin.uuid.Uuid,
                        request: me.rerere.ai.runtime.task.TaskApprovalRequest,
                    ): me.rerere.ai.runtime.task.TaskApprovalDecision =
                        me.rerere.ai.runtime.task.TaskApprovalDecision.Denied()
                }
            },
            processingStatus = status,
            progressLabel = { "Running $it" },
            parentConversationId = Uuid.random(),
        )

        runBlocking { tool.execute(spawnArgs("Researcher")) }

        // Gap A: the approval tool stays in the pool — gated through the parent's approval gate —
        // but the child runtime must never gate anything itself: needsApproval is rewritten off.
        assertEquals(listOf("mcp__search", "ask_user"), capturedTools.map { it.name })
        assertTrue(
            "the child pool must carry no needsApproval=true tool (the gate is the only decision point)",
            capturedTools.none { it.needsApproval },
        )
    }
}
