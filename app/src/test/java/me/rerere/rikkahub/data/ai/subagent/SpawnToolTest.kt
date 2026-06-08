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
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * JVM unit tests for [buildSpawnTool] (issue #201, slice 4). The factory is Android-free, so its
 * `execute` is driven directly with a fake [SubagentRunner].
 *
 * Two regressions pinned here:
 *  - processingStatus is RESTORED on every terminal path (success AND the error() throw on an
 *    unknown subagent) — a stale "Running <sub>" label must not leak into the parent loading UI.
 *  - needsApproval=true tools are dropped from the pool handed to the subagent (approval UI is
 *    unreachable mid-subagent), and the sub's pool is actually used (not emptyList()).
 */
class SpawnToolTest {

    private val subModel = Model(modelId = "sub-model", displayName = "Sub", id = Uuid.random())

    private fun settingsWith(vararg models: Model): Settings = Settings(
        chatModelId = Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = models.toList())),
    )

    private fun tool(name: String, needsApproval: Boolean = false): Tool =
        Tool(name = name, description = name, needsApproval = needsApproval, execute = { emptyList() })

    /** A runner whose fake engine just returns one assistant text and captures the tool pool. */
    private fun fakeRunner(capturedTools: MutableList<Tool>): SubagentRunner = SubagentRunner(
        generate = { _, _, _, _, tools, _, _ ->
            capturedTools.clear()
            capturedTools.addAll(tools)
            flowOf(
                GenerationChunk.Messages(
                    listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("done"))))
                )
            )
        },
    )

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
            runner = fakeRunner(mutableListOf()),
            parentModelId = null,
            settings = settingsWith(subModel),
            buildSubagentTools = { emptyList() },
            processingStatus = status,
            progressLabel = { "Running $it" },
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
            runner = fakeRunner(mutableListOf()),
            parentModelId = null,
            settings = settingsWith(subModel),
            buildSubagentTools = { emptyList() },
            processingStatus = status,
            progressLabel = { "Running $it" },
        )

        runBlocking {
            runCatching { tool.execute(spawnArgs("Nonexistent")) }
        }

        // The unknown-subagent error() is thrown before the status is set, so it must remain
        // exactly the prior value — and never a stale "Running ..." from a partial set.
        assertEquals("prior", status.value)
    }

    @Test
    fun `execute drops needsApproval tools and uses the sub's own pool`() {
        val capturedTools = mutableListOf<Tool>()
        val status = MutableStateFlow<String?>(null)
        val sub = Assistant(name = "Researcher", chatModelId = subModel.id, spawnable = true)
        val tool = buildSpawnTool(
            spawnableAssistants = listOf(sub),
            runner = fakeRunner(capturedTools),
            parentModelId = null,
            settings = settingsWith(subModel),
            buildSubagentTools = {
                listOf(tool("mcp__search"), tool("ask_user", needsApproval = true))
            },
            processingStatus = status,
            progressLabel = { "Running $it" },
        )

        runBlocking { tool.execute(spawnArgs("Researcher")) }

        assertEquals(listOf("mcp__search"), capturedTools.map { it.name })
        assertTrue(
            "needsApproval tools are unreachable mid-subagent and must be dropped",
            capturedTools.none { it.needsApproval },
        )
    }
}
