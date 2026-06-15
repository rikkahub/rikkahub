package me.rerere.ai.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextFitTest {

    private fun system(text: String) = UIMessage.system(text)
    private fun user(text: String) = UIMessage.user(text)
    private fun assistant(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())
    private fun assistantText(text: String) = assistant(UIMessagePart.Text(text))

    private fun executedTool(
        id: String,
        outputTextLen: Int,
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = id,
        toolName = "tool_$id",
        input = """{"q":"$id"}""",
        output = listOf(UIMessagePart.Text("x".repeat(outputTextLen))),
    )

    private fun mixedFixture(): List<UIMessage> = listOf(
        system("system prompt"),
        user("anchor"),
        assistant(
            UIMessagePart.Text("context A"),
            executedTool("a", 2_500),
        ),
        assistant(
            UIMessagePart.Image("data:image/png;base64,AAAA"),
            UIMessagePart.Text("context B"),
            executedTool("b", 2_200),
        ),
        user("current turn"),
    )

    @Test
    fun `fit invariant is bounded or overBudget is flagged`() {
        runBlocking {
            checkAll(Arb.int(64..8_000), Arb.int(1..4)) { budget, repeats ->
                val messages = List(repeats) { mixedFixture() }.flatMap { it }
                val result = messages.fitToWindow(budget)
                val fittedTokens = estimateTokensForMessages(result.payload)
                assertTrue(fittedTokens <= budget || result.overBudget)
            }
        }
    }

    @Test
    fun `boundary covers exact budget identity empty and single oversized message`() {
        val fixture = mixedFixture()
        val exactBudget = estimateTokensForMessages(fixture)
        val exact = fixture.fitToWindow(exactBudget)
        assertTrue(exact.payload === fixture)
        assertFalse(exact.overBudget)

        val empty = emptyList<UIMessage>().fitToWindow(100)
        assertTrue(empty.payload.isEmpty())
        assertFalse(empty.overBudget)

        val oversized = listOf(user("u".repeat(8_000)))
        val single = oversized.fitToWindow(1)
        assertTrue(single.overBudget)
    }

    @Test
    fun `known droppable middle content fits without overBudget`() {
        runBlocking {
            checkAll(Arb.int(1_000..8_000)) { textLen ->
                val messages = listOf(
                    system("sys"),
                    user("anchor"),
                    assistantText("m".repeat(textLen)),
                    user("current turn"),
                )
                val achievableBudget = estimateTokensForMessages(
                    listOf(system("sys"), user("anchor"), user("current turn"))
                ) + 32

                val result = messages.fitToWindow(achievableBudget)

                assertFalse(result.overBudget)
                assertTrue(estimateTokensForMessages(result.payload) <= achievableBudget)
            }
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `middle drop scans past legacy cluster that would orphan protected tail result`() {
        val legacyCall = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.ToolCall(
                    toolCallId = "legacy-keep",
                    toolName = "legacy_tool",
                    arguments = "{}",
                )
            )
        )
        val droppable = assistantText("drop me ".repeat(2_000))
        val legacyResult = UIMessage(
            role = MessageRole.TOOL,
            parts = listOf(
                UIMessagePart.ToolResult(
                    toolCallId = "legacy-keep",
                    toolName = "legacy_tool",
                    content = JsonPrimitive("ok"),
                    arguments = JsonPrimitive("{}"),
                )
            )
        )
        val messages = listOf(system("sys"), user("anchor"), legacyCall, droppable, legacyResult, user("tail"))
        val budget = estimateTokensForMessages(messages) - estimateTokens(droppable.parts) + 16

        val result = messages.fitToWindow(budget = budget, minTailCount = 2)

        assertFalse(result.overBudget)
        assertTrue(estimateTokensForMessages(result.payload) <= budget)
        assertTrue(result.payload.any { it.id == legacyCall.id })
        assertTrue(result.payload.any { it.id == legacyResult.id })
        assertFalse(result.payload.any { it.id == droppable.id })
        assertTrue(result.payload.hasNoLegacyToolResultOrphans())
    }

    @Test
    fun `assistant before first user is not pinned as the head user anchor`() {
        val preUserAssistant = assistantText("pre-user assistant ".repeat(1_500))
        val messages = listOf(
            system("sys"),
            preUserAssistant,
            user("first user anchor"),
            assistantText("small middle"),
            user("current turn"),
        )
        val budget = estimateTokensForMessages(messages) - estimateTokens(preUserAssistant.parts) + 16

        val result = messages.fitToWindow(budget)

        assertFalse(result.overBudget)
        assertTrue(estimateTokensForMessages(result.payload) <= budget)
        assertFalse(result.payload.any { it.id == preUserAssistant.id })
        assertTrue(result.payload.any { it.role == MessageRole.USER && it.toText() == "first user anchor" })
    }

    @Test
    fun `guaranteed fit last resort truncates protected text envelopes`() {
        val messages = listOf(
            system("system ".repeat(1_500)),
            user("current user turn ".repeat(1_500)),
        )
        val skeletonBudget = estimateTokensForMessages(
            listOf(system("[truncated ~3000 tokens]"), user("[truncated ~3000 tokens]"))
        ) + 16

        val result = messages.fitToWindow(skeletonBudget)

        assertFalse(result.overBudget)
        assertTrue(estimateTokensForMessages(result.payload) <= skeletonBudget)
        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER), result.payload.map { it.role })
        assertTrue(result.payload.all { it.parts.isNotEmpty() })
        assertTrue(result.payload.all { it.toText().isNotBlank() })
    }

    @Test
    @Suppress("DEPRECATION")
    fun `orphan-free invariant holds for multi-tool message and legacy call-result pairs`() {
        val multiTool = listOf(
            system("s"),
            user("anchor"),
            assistant(
                executedTool("m1", 2_400),
                UIMessagePart.Text("separator"),
                executedTool("m2", 2_400),
            ),
            user("tail"),
        )
        val multiToolFit = multiTool.fitToWindow(800)
        assertTrue(multiToolFit.payload.hasNoLegacyToolResultOrphans())

        val legacyCall = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.ToolCall(
                    toolCallId = "legacy-1",
                    toolName = "legacy_tool",
                    arguments = """{"payload":"${"x".repeat(3_000)}"}""",
                )
            )
        )
        val legacyResult = UIMessage(
            role = MessageRole.TOOL,
            parts = listOf(
                UIMessagePart.ToolResult(
                    toolCallId = "legacy-1",
                    toolName = "legacy_tool",
                    content = JsonPrimitive("y".repeat(3_000)),
                    arguments = JsonPrimitive("{}"),
                )
            )
        )
        val legacyMessages = listOf(system("sys"), user("anchor"), legacyCall, legacyResult, user("tail"))
        val legacyFit = legacyMessages.fitToWindow(900)
        assertTrue(legacyFit.payload.hasNoLegacyToolResultOrphans())
    }

    @Test
    fun `isExecuted stays true after tool output elision`() {
        val tool = executedTool("exec", outputTextLen = 7_000)
        val messages = listOf(
            system("sys"),
            user("anchor"),
            assistant(tool),
            user("tail"),
        )
        val outputTokens = estimateTokens(tool.output)
        val budget = estimateTokensForMessages(messages) - outputTokens + 64

        val result = messages.fitToWindow(budget)
        val fittedTool = result.payload
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .firstOrNull { it.toolCallId == "exec" }

        assertNotNull(fittedTool)
        assertTrue(fittedTool!!.isExecuted)
        assertTrue(fittedTool.output.isNotEmpty())
    }

    @Test
    fun `fit is monotone in budget by message id subsequence`() {
        val fixture = List(2) { mixedFixture() }.flatMap { it }
        runBlocking {
            checkAll(Arb.int(64..10_000), Arb.int(64..10_000)) { a, b ->
                val low = minOf(a, b)
                val high = maxOf(a, b)
                val lowIds = fixture.fitToWindow(low).payload.map { it.id }
                val highIds = fixture.fitToWindow(high).payload.map { it.id }
                assertTrue(lowIds.isSubsequenceOf(highIds))
            }
        }
    }

    @Test
    fun `fit is idempotent at fixed budget`() {
        val fixture = List(2) { mixedFixture() }.flatMap { it }
        runBlocking {
            checkAll(Arb.int(64..10_000)) { budget ->
                val once = fixture.fitToWindow(budget)
                val twice = once.payload.fitToWindow(budget)
                assertEquals(once.payload, twice.payload)
            }
        }
    }

    @Test
    fun `fit does not mutate input`() {
        val fixture = mixedFixture()
        val before = json.encodeToString(fixture)
        fixture.fitToWindow(700)
        val after = json.encodeToString(fixture)
        assertEquals(before, after)
    }

    @Test
    fun `estimatedToolSchemaTokens is non-negative and monotone in tool count`() {
        runBlocking {
            checkAll(Arb.int(0..8), Arb.int(0..8)) { a, b ->
                val small = minOf(a, b)
                val large = maxOf(a, b)
                val smallTools = List(small) { toolForIndex(it) }
                val largeTools = List(large) { toolForIndex(it) }
                val smallEstimate = estimatedToolSchemaTokens(smallTools)
                val largeEstimate = estimatedToolSchemaTokens(largeTools)
                assertTrue(smallEstimate >= 0)
                assertTrue(largeEstimate >= smallEstimate)
            }
        }
    }

    private fun toolForIndex(index: Int): Tool = Tool(
        name = "tool_$index",
        description = "description_$index",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "value",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                        }
                    )
                },
                required = listOf("value")
            )
        },
        execute = { emptyList() },
    )

    @Suppress("DEPRECATION")
    private fun List<UIMessage>.hasNoLegacyToolResultOrphans(): Boolean {
        val callIds = buildSet {
            this@hasNoLegacyToolResultOrphans.forEach { message ->
                message.parts.forEach { part ->
                    when (part) {
                        is UIMessagePart.Tool -> add(part.toolCallId)
                        is UIMessagePart.ToolCall -> add(part.toolCallId)
                        else -> Unit
                    }
                }
            }
        }
        return all { message ->
            message.parts.all { part ->
                part !is UIMessagePart.ToolResult || part.toolCallId in callIds
            }
        }
    }

    private fun <T> List<T>.isSubsequenceOf(other: List<T>): Boolean {
        if (isEmpty()) return true
        var i = 0
        var j = 0
        while (i < size && j < other.size) {
            if (this[i] == other[j]) i++
            j++
        }
        return i == size
    }
}
