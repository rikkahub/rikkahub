package me.rerere.rikkahub.data.ai.subagent

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.TurnConfig
import me.rerere.ai.runtime.subagent.SPAWN_TOOL_NAME
import me.rerere.ai.runtime.subagent.extractFinalAssistantText
import me.rerere.ai.runtime.subagent.filterToolsForSubagent
import me.rerere.ai.runtime.subagent.resolveSubagentModel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.runtime.toAssistantConfig
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Property-based tests for the pure subagent primitives (issue #201, slice 2):
 * [resolveSubagentModel], [filterToolsForSubagent], [extractFinalAssistantText].
 *
 * Mirrors the existing kotest.property idiom (checkAll + JUnit @Test + runBlocking) used by
 * ai/.../ToolApprovalStateInvariantPropertyTest.kt.
 */
class SubagentPrimitivesPropertyTest {

    // --- generators -----------------------------------------------------------------------

    private val arbUuid: Arb<Uuid> = arbitrary { Uuid.random() }

    /** A noop tool with a generated name; never the reserved spawn name unless we say so. */
    private fun tool(name: String): Tool =
        Tool(name = name, description = "", execute = { emptyList() })

    private val arbToolName: Arb<String> = Arb.string(0..12).map { it.ifBlank { "t" } }

    // A pool of arbitrary tools that MAY include the spawn tool by name, plus look-alikes
    // (`mcp__task`, `task_runner`) that must NOT be filtered (they are not the reserved name).
    private val arbToolPool: Arb<List<Tool>> = arbitrary { rs ->
        val base = Arb.list(arbToolName, 0..6).bind().map { tool(it) }
        val maybeSpawn = if (Arb.boolean().bind()) listOf(tool(SPAWN_TOOL_NAME)) else emptyList()
        val lookAlikes = listOf(tool("mcp__$SPAWN_TOOL_NAME"), tool("${SPAWN_TOOL_NAME}_runner"))
        (base + maybeSpawn + lookAlikes).shuffled(rs.random)
    }

    private fun assistant(chatModelId: Uuid?): Assistant =
        Assistant(chatModelId = chatModelId, name = "sub")

    /** Minimal neutral turn snapshot — only [TurnConfig.defaultModelId] is read by the resolver. */
    private fun turn(defaultModelId: Uuid): TurnConfig =
        TurnConfig(defaultModelId = defaultModelId, providers = emptyList(), assistants = emptyList())

    private fun assistantText(text: String): UIMessage =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    /** An assistant message that is pure tool_use with the given text living inside Tool.output. */
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
        runBlocking {
            checkAll(300, arbUuid.orNull(0.4), arbUuid.orNull(0.4), arbUuid) { subPin, parent, globalDefault ->
                val resolved = resolveSubagentModel(assistant(subPin).toAssistantConfig(), parent, turn(globalDefault))

                val expected = subPin ?: parent ?: globalDefault
                assertEquals(expected, resolved)
            }
        }
    }

    @Test
    fun `metamorphic - clearing the sub pin falls back to the parent model`() {
        runBlocking {
            checkAll(200, arbUuid, arbUuid, arbUuid) { subPin, parent, globalDefault ->
                val turn = turn(globalDefault)
                // With a pin, the sub's own model wins.
                assertEquals(subPin, resolveSubagentModel(assistant(subPin).toAssistantConfig(), parent, turn))
                // Clearing the pin (chatModelId = null) -> the parent's model.
                assertEquals(parent, resolveSubagentModel(assistant(null).toAssistantConfig(), parent, turn))
            }
        }
    }

    // --- filterToolsForSubagent -----------------------------------------------------------

    @Test
    fun `invariant - the spawn tool is never present in a subagent pool`() {
        runBlocking {
            checkAll(300, arbToolPool) { pool ->
                val filtered = filterToolsForSubagent(pool)
                // INVARIANT (recursion guard): no `task` tool survives, for any input pool.
                assertFalse(filtered.any { it.name == SPAWN_TOOL_NAME })
                // CONSERVATION: output is a subset of input.
                assertTrue(filtered.all { it in pool })
                assertTrue(filtered.size <= pool.size)
                // IDEMPOTENCE.
                assertEquals(filtered, filterToolsForSubagent(filtered))
            }
        }
    }

    @Test
    fun `a non-spawn tool whose name merely contains task survives the filter`() {
        runBlocking {
            checkAll(200, arbToolPool) { pool ->
                val filtered = filterToolsForSubagent(pool)
                // The guard keys off the EXACT reserved name, not a substring: `mcp__task`
                // and `task_runner` are distinct tools and must NOT be filtered.
                assertTrue(filtered.any { it.name == "mcp__$SPAWN_TOOL_NAME" })
                assertTrue(filtered.any { it.name == "${SPAWN_TOOL_NAME}_runner" })
            }
        }
    }

    // --- extractFinalAssistantText --------------------------------------------------------

    @Test
    fun `invariant - returns the last assistant text when one is present`() {
        runBlocking {
            checkAll(200, Arb.string(1..20).map { it.trim().ifBlank { "answer" } }) { text ->
                val messages = listOf(
                    UIMessage.user("hi"),
                    assistantText("intermediate"),
                    assistantText(text),
                )
                assertEquals(text, extractFinalAssistantText(messages))
            }
        }
    }

    @Test
    fun `metamorphic - appending a pure tool_use assistant after a text one does not change the result`() {
        runBlocking {
            checkAll(200, Arb.string(1..20).map { it.trim().ifBlank { "answer" } }) { text ->
                val base = listOf(UIMessage.user("hi"), assistantText(text))
                val withTrailingToolUse = base + assistantToolOnly(outputText = "")

                assertEquals(
                    extractFinalAssistantText(base),
                    extractFinalAssistantText(withTrailingToolUse),
                )
            }
        }
    }

    @Test
    fun `boundary - an all-tool or empty conversation extracts the empty string`() {
        runBlocking {
            // empty
            assertEquals("", extractFinalAssistantText(emptyList()))
            // only a user message
            assertEquals("", extractFinalAssistantText(listOf(UIMessage.user("hi"))))
            // assistant present but pure tool_use with no text output anywhere
            assertEquals(
                "",
                extractFinalAssistantText(listOf(UIMessage.user("hi"), assistantToolOnly(outputText = ""))),
            )
        }
    }

    @Test
    fun `tool output fallback - text living only inside Tool output is extracted, not blank`() {
        runBlocking {
            checkAll(200, Arb.string(1..20).map { it.trim().ifBlank { "tool answer" } }) { outputText ->
                val messages = listOf(
                    UIMessage.user("hi"),
                    assistantToolOnly(outputText = outputText),
                )
                // The last assistant message has NO top-level Text; the answer lives in
                // Tool.output. A naive last().toText() would return blank here.
                assertEquals(outputText, extractFinalAssistantText(messages))
            }
        }
    }
}
