package me.rerere.ai.runtime.subagent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.AssistantConfig
import me.rerere.ai.runtime.contract.TurnConfig
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Direct unit tests for the moved subagent primitives (issue #243 slice 6):
 * [resolveSubagentModel], [filterToolsForSubagent], [extractFinalAssistantText].
 *
 * The primitives moved verbatim from `app/.../SubagentPrimitives.kt` into `:ai-runtime`, converting
 * [resolveSubagentModel]'s signature from the app `Assistant`/`Settings` to the neutral
 * [AssistantConfig]/[TurnConfig]. `Settings.chatModelId` maps 1:1 to [TurnConfig.defaultModelId] (both
 * non-null [Uuid]), so the sub-pin > parent > global-default resolution order is preserved. The
 * filter/extract primitives are byte-identical (neutral `:ai` types). These pin all three.
 */
class SubagentPrimitivesTest {

    private fun tool(name: String): Tool =
        Tool(name = name, description = "", execute = { emptyList() })

    private fun assistant(chatModelId: Uuid?): AssistantConfig = AssistantConfig(
        id = Uuid.random(),
        chatModelId = chatModelId,
        systemPrompt = "",
        streamOutput = true,
        enableMemory = false,
        useGlobalMemory = false,
        enableRecentChatsReference = false,
        messageTemplate = "{{ message }}",
        regexes = emptyList(),
        reasoningLevel = ReasoningLevel.AUTO,
        maxTokens = null,
        customHeaders = emptyList(),
        customBodies = emptyList(),
        mcpServers = emptySet(),
        localToolIds = emptyList(),
        enabledSkills = emptySet(),
        modeInjectionIds = emptySet(),
        lorebookIds = emptySet(),
        knowledgeBaseId = null,
        description = "",
        spawnable = false,
        subagentMaxSteps = null,
    )

    private fun turn(defaultModelId: Uuid): TurnConfig =
        TurnConfig(defaultModelId = defaultModelId, providers = emptyList(), assistants = emptyList())

    private fun assistantText(text: String): UIMessage =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    private fun assistantToolOnly(outputText: String): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "c1",
                toolName = "mcp__search",
                input = "{}",
                output = if (outputText.isBlank()) emptyList() else listOf(UIMessagePart.Text(outputText)),
            )
        )
    )

    // --- resolveSubagentModel -------------------------------------------------------------

    @Test
    fun `resolveSubagentModel honours sub then parent then global default`() {
        val subPin = Uuid.random()
        val parent = Uuid.random()
        val globalDefault = Uuid.random()

        assertEquals(subPin, resolveSubagentModel(assistant(subPin), parent, turn(globalDefault)))
        assertEquals(parent, resolveSubagentModel(assistant(null), parent, turn(globalDefault)))
        assertEquals(globalDefault, resolveSubagentModel(assistant(null), null, turn(globalDefault)))
    }

    // --- filterToolsForSubagent -----------------------------------------------------------

    @Test
    fun `the spawn tool is never present in a subagent pool, but look-alikes survive`() {
        val pool = listOf(
            tool("search"),
            tool(SPAWN_TOOL_NAME),
            tool("mcp__$SPAWN_TOOL_NAME"),
            tool("${SPAWN_TOOL_NAME}_runner"),
        )
        val filtered = filterToolsForSubagent(pool)

        assertFalse(filtered.any { it.name == SPAWN_TOOL_NAME })
        assertTrue(filtered.all { it in pool }) // conservation
        assertEquals(filtered, filterToolsForSubagent(filtered)) // idempotence
        // exact-name guard: substring matches are NOT stripped
        assertTrue(filtered.any { it.name == "mcp__$SPAWN_TOOL_NAME" })
        assertTrue(filtered.any { it.name == "${SPAWN_TOOL_NAME}_runner" })
    }

    // --- extractFinalAssistantText --------------------------------------------------------

    @Test
    fun `returns the last assistant text when one is present`() {
        val messages = listOf(
            UIMessage.user("hi"),
            assistantText("intermediate"),
            assistantText("final answer"),
        )
        assertEquals("final answer", extractFinalAssistantText(messages))
    }

    @Test
    fun `appending a pure tool_use assistant after a text one does not change the result`() {
        val base = listOf(UIMessage.user("hi"), assistantText("answer"))
        val withTrailingToolUse = base + assistantToolOnly(outputText = "")
        assertEquals(
            extractFinalAssistantText(base),
            extractFinalAssistantText(withTrailingToolUse),
        )
    }

    @Test
    fun `an all-tool or empty conversation extracts the empty string`() {
        assertEquals("", extractFinalAssistantText(emptyList()))
        assertEquals("", extractFinalAssistantText(listOf(UIMessage.user("hi"))))
        assertEquals(
            "",
            extractFinalAssistantText(listOf(UIMessage.user("hi"), assistantToolOnly(outputText = ""))),
        )
    }

    @Test
    fun `text living only inside Tool output is extracted, not blank`() {
        val messages = listOf(
            UIMessage.user("hi"),
            assistantToolOnly(outputText = "answer-in-tool-output"),
        )
        assertEquals("answer-in-tool-output", extractFinalAssistantText(messages))
    }
}
