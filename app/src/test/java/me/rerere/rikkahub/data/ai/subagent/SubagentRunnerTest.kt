package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.runtime.contract.TurnConfig
import me.rerere.ai.runtime.subagent.SPAWN_TOOL_NAME
import me.rerere.ai.runtime.subagent.resolveSubagentModel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.runtime.toAssistantConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * JVM unit tests for [SubagentRunner] driven by a FAKE engine (a function-typed `generate`
 * collaborator), so no Context / Provider / network is needed. Issue #201, slice 3.
 *
 * The KEY regression is `subagent forces memory OFF even when the input sub has it ON`: a
 * subagent silently mutating the PARENT's memory is the C1 data-integrity hazard called out in
 * the design. The runner copies the sub-Assistant with enableMemory=false AND
 * enableRecentChatsReference=false before handing it to the engine; this test pins exactly that
 * hand-off and would FAIL if the runner passed the sub through unchanged.
 */
class SubagentRunnerTest {

    private val parentModel = Model(modelId = "parent-model", displayName = "Parent", id = Uuid.random())
    private val subModel = Model(modelId = "sub-model", displayName = "Sub", id = Uuid.random())

    private fun settingsWith(vararg models: Model): Settings = Settings(
        chatModelId = Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = models.toList())),
    )

    /** Capture of the arguments the engine seam received, for assertions. */
    private class Captured {
        lateinit var settings: Settings
        lateinit var model: Model
        lateinit var messages: List<UIMessage>
        lateinit var assistant: Assistant
        lateinit var tools: List<Tool>
        var maxSteps: Int = -1
        lateinit var processingStatus: MutableStateFlow<String?>
    }

    private fun runnerCapturing(
        captured: Captured,
        emit: List<GenerationChunk>,
    ): SubagentRunner = SubagentRunner(
        generate = { settings, model, messages, assistant, tools, maxSteps, processingStatus ->
            captured.settings = settings
            captured.model = model
            captured.messages = messages
            captured.assistant = assistant
            captured.tools = tools
            captured.maxSteps = maxSteps
            captured.processingStatus = processingStatus
            flowOf(*emit.toTypedArray())
        },
    )

    private fun tool(name: String): Tool =
        Tool(name = name, description = name, execute = { emptyList() })

    private fun assistantMsg(text: String): UIMessage =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    @Test
    fun `subagent forces memory OFF even when the input sub has it ON`() {
        val captured = Captured()
        val sub = Assistant(
            name = "Researcher",
            chatModelId = subModel.id,
            enableMemory = true,
            useGlobalMemory = true,
            enableRecentChatsReference = true,
        )
        val runner = runnerCapturing(captured, listOf(GenerationChunk.Messages(listOf(assistantMsg("done")))))

        runBlocking {
            runner.run(sub = sub, prompt = "go", parentModelId = parentModel.id, settings = settingsWith(subModel))
        }

        // The regression: the ephemeral sub handed to the engine must have memory disabled.
        assertFalse("subagent must not write/read parent memory", captured.assistant.enableMemory)
        assertFalse("subagent must not inject recent-chats", captured.assistant.enableRecentChatsReference)
    }

    @Test
    fun `model is resolved via resolveSubagentModel and inherits the parent when sub pins none`() {
        val captured = Captured()
        // sub pins NO model -> inherits the parent's model.
        val sub = Assistant(name = "Sub", chatModelId = null)
        val settings = settingsWith(parentModel)
        val runner = runnerCapturing(captured, listOf(GenerationChunk.Messages(listOf(assistantMsg("ok")))))

        runBlocking {
            runner.run(sub = sub, prompt = "go", parentModelId = parentModel.id, settings = settings)
        }

        val turn = TurnConfig(defaultModelId = settings.chatModelId, providers = emptyList(), assistants = emptyList())
        assertEquals(resolveSubagentModel(sub.toAssistantConfig(), parentModel.id, turn), captured.model.id)
        assertEquals(parentModel.id, captured.model.id)
    }

    @Test
    fun `run returns extractFinalAssistantText of the terminal messages`() {
        val captured = Captured()
        val sub = Assistant(name = "Sub", chatModelId = subModel.id)
        // Two chunks; the runner must keep the LAST chunk's messages.
        val emit = listOf(
            GenerationChunk.Messages(listOf(assistantMsg("partial"))),
            GenerationChunk.Messages(listOf(UIMessage.user("go"), assistantMsg("final answer"))),
        )
        val runner = runnerCapturing(captured, emit)

        val result = runBlocking {
            runner.run(sub = sub, prompt = "go", parentModelId = null, settings = settingsWith(subModel))
        }

        assertEquals("final answer", result)
    }

    @Test
    fun `run extracts text from Tool output when the last assistant message is pure tool_use`() {
        val captured = Captured()
        val sub = Assistant(name = "Sub", chatModelId = subModel.id)
        val toolOnly = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "c1",
                    toolName = "mcp__search",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("answer-in-tool-output")),
                )
            )
        )
        val runner = runnerCapturing(captured, listOf(GenerationChunk.Messages(listOf(UIMessage.user("go"), toolOnly))))

        val result = runBlocking {
            runner.run(sub = sub, prompt = "go", parentModelId = null, settings = settingsWith(subModel))
        }

        assertEquals("answer-in-tool-output", result)
    }

    @Test
    fun `run threads the subagent tool pool to the engine and strips the spawn tool`() {
        val captured = Captured()
        val sub = Assistant(name = "Sub", chatModelId = subModel.id)
        val runner = runnerCapturing(captured, listOf(GenerationChunk.Messages(listOf(assistantMsg("x")))))

        runBlocking {
            runner.run(
                sub = sub,
                prompt = "go",
                parentModelId = null,
                settings = settingsWith(subModel),
                // Pool includes the spawn tool itself — the recursion guard must remove it.
                tools = listOf(tool("mcp__search"), tool(SPAWN_TOOL_NAME), tool("use_skill")),
            )
        }

        // The engine receives the sub's tools (not emptyList()), and never the spawn tool.
        assertEquals(listOf("mcp__search", "use_skill"), captured.tools.map { it.name })
        assertTrue(
            "the spawn tool must never reach a subagent (recursion guard)",
            captured.tools.none { it.name == SPAWN_TOOL_NAME },
        )
    }

    @Test
    fun `maxSteps defaults to the subagent ceiling and is overridable by the sub`() {
        val captured = Captured()
        val runner = runnerCapturing(captured, listOf(GenerationChunk.Messages(listOf(assistantMsg("x")))))

        runBlocking {
            // sub.maxSteps == null -> the subagent default.
            runner.run(
                sub = Assistant(name = "Sub", chatModelId = subModel.id, maxSteps = null),
                prompt = "go",
                parentModelId = null,
                settings = settingsWith(subModel),
            )
        }
        assertEquals(SubagentRunner.SUBAGENT_DEFAULT_MAX_STEPS, captured.maxSteps)

        val captured2 = Captured()
        val runner2 = runnerCapturing(captured2, listOf(GenerationChunk.Messages(listOf(assistantMsg("x")))))
        runBlocking {
            runner2.run(
                sub = Assistant(name = "Sub", chatModelId = subModel.id, maxSteps = 7),
                prompt = "go",
                parentModelId = null,
                settings = settingsWith(subModel),
            )
        }
        assertEquals(7, captured2.maxSteps)
    }
}
