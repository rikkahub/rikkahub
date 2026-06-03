package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the streaming tool-call merge reducer in [UIMessage.appendChunk] /
 * [handleMessageChunk]. These replay realistic provider delta sequences and
 * assert the assembled message.
 *
 * Part A cases CHARACTERIZE current behavior (single tool, atomic, interleaved
 * modalities) and must stay green before AND after the index-keyed merge fix.
 *
 * Part B cases REGRESS the parallel-interleaved bug: they FAIL on the unfixed
 * "blank id => attach to last Tool" heuristic and PASS once the merge keys on
 * the protocol stream index.
 */
class MessageStreamMergeTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    // ---- helpers ---------------------------------------------------------

    private fun assistantSeed() = listOf(
        UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
    )

    private fun chunkOf(vararg parts: UIMessagePart): MessageChunk = MessageChunk(
        id = "test",
        model = "test",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
                message = null,
                finishReason = null
            )
        )
    )

    private fun toolDelta(
        id: String = "",
        name: String = "",
        input: String = "",
        streamIndex: Int? = null
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = input,
        output = emptyList()
    ).also { it.streamIndex = streamIndex }

    private fun List<UIMessage>.applyAll(vararg chunks: MessageChunk): List<UIMessage> {
        var acc = this
        for (c in chunks) acc = acc.handleMessageChunk(c)
        return acc
    }

    private fun List<UIMessage>.tools(): List<UIMessagePart.Tool> =
        last().parts.filterIsInstance<UIMessagePart.Tool>()

    // ==================== Part A — characterization ====================

    @Test
    fun `Claude single tool - tool_use then input_json_delta fragments assemble into one Tool`() {
        // Mirrors ClaudeProvider: tool_use(real id+name, empty input) then
        // input_json_delta(blank id/name, partial json) frames.
        val result = assistantSeed().applyAll(
            chunkOf(toolDelta(id = "toolu_1", name = "get_weather", input = "", streamIndex = 0)),
            chunkOf(toolDelta(input = "{\"ci", streamIndex = 0)),
            chunkOf(toolDelta(input = "ty\":\"", streamIndex = 0)),
            chunkOf(toolDelta(input = "SF\"}", streamIndex = 0))
        )

        val tools = result.tools()
        assertEquals(1, tools.size)
        assertEquals("toolu_1", tools[0].toolCallId)
        assertEquals("get_weather", tools[0].toolName)
        assertEquals("{\"city\":\"SF\"}", tools[0].input)
    }

    @Test
    fun `ChatCompletions single tool - first delta with id then arg-only deltas concatenate`() {
        // Mirrors ChatCompletionsAPI: first tool_call(id+name+partial args),
        // continuation deltas have null id (=> blank) + partial args.
        val result = assistantSeed().applyAll(
            chunkOf(toolDelta(id = "call_1", name = "search", input = "{\"q\":", streamIndex = 0)),
            chunkOf(toolDelta(input = "\"hi\"}", streamIndex = 0))
        )

        val tools = result.tools()
        assertEquals(1, tools.size)
        assertEquals("call_1", tools[0].toolCallId)
        assertEquals("search", tools[0].toolName)
        assertEquals("{\"q\":\"hi\"}", tools[0].input)
    }

    @Test
    fun `interleaved modalities - reasoning text and single tool each concatenate in order`() {
        val result = assistantSeed().applyAll(
            chunkOf(UIMessagePart.Reasoning(reasoning = "let me ", finishedAt = null)),
            chunkOf(UIMessagePart.Reasoning(reasoning = "think", finishedAt = null)),
            chunkOf(UIMessagePart.Text("Calling ")),
            chunkOf(UIMessagePart.Text("the tool")),
            chunkOf(toolDelta(id = "call_1", name = "search", input = "{\"q\":", streamIndex = 0)),
            chunkOf(toolDelta(input = "1}", streamIndex = 0))
        )

        val parts = result.last().parts
        val reasoning = parts.filterIsInstance<UIMessagePart.Reasoning>()
        val texts = parts.filterIsInstance<UIMessagePart.Text>()
        val tools = parts.filterIsInstance<UIMessagePart.Tool>()

        assertEquals(1, reasoning.size)
        assertEquals("let me think", reasoning[0].reasoning)
        assertEquals(1, texts.size)
        assertEquals("Calling the tool", texts[0].text)
        assertEquals(1, tools.size)
        assertEquals("{\"q\":1}", tools[0].input)
        // Reasoning part comes before Text part (insertion order preserved).
        assertTrue(parts.indexOfFirst { it is UIMessagePart.Reasoning } <
            parts.indexOfFirst { it is UIMessagePart.Text })
    }

    @Test
    fun `atomic single tool - one complete Tool with no stream index stays unchanged`() {
        // Google / ResponseAPI style: a single complete Tool, no streamIndex.
        val result = assistantSeed().applyAll(
            chunkOf(toolDelta(id = "fn_1", name = "lookup", input = "{\"x\":1}", streamIndex = null))
        )

        val tools = result.tools()
        assertEquals(1, tools.size)
        assertEquals("fn_1", tools[0].toolCallId)
        assertEquals("lookup", tools[0].toolName)
        assertEquals("{\"x\":1}", tools[0].input)
    }

    @Test
    fun `atomic tool followed by blank-id continuation falls back to last Tool`() {
        // No streamIndex on any frame => must regress to today's
        // blank-id => lastOrNull behavior exactly.
        val result = assistantSeed().applyAll(
            chunkOf(toolDelta(id = "fn_1", name = "lookup", input = "{\"x\":")),
            chunkOf(toolDelta(input = "1}"))
        )

        val tools = result.tools()
        assertEquals(1, tools.size)
        assertEquals("fn_1", tools[0].toolCallId)
        assertEquals("{\"x\":1}", tools[0].input)
    }

    // ==================== Part B — regression (parallel interleaved) ====================

    @Test
    fun `Claude parallel interleaved - two tools keyed by content-block index stay separate`() {
        // tool_use index 0 (toolu_A) + index 1 (toolu_B), then INTERLEAVED
        // input_json_delta frames carrying only the block index (blank id).
        val result = assistantSeed().applyAll(
            chunkOf(toolDelta(id = "toolu_A", name = "a", input = "", streamIndex = 0)),
            chunkOf(toolDelta(id = "toolu_B", name = "b", input = "", streamIndex = 1)),
            chunkOf(toolDelta(input = "{\"x\":", streamIndex = 0)),
            chunkOf(toolDelta(input = "{\"y\":", streamIndex = 1)),
            chunkOf(toolDelta(input = "1}", streamIndex = 0)),
            chunkOf(toolDelta(input = "2}", streamIndex = 1))
        )

        val tools = result.tools()
        assertEquals(2, tools.size)
        val a = tools.first { it.toolCallId == "toolu_A" }
        val b = tools.first { it.toolCallId == "toolu_B" }
        assertEquals("{\"x\":1}", a.input)
        assertEquals("{\"y\":2}", b.input)
    }

    @Test
    fun `ChatCompletions parallel interleaved - two tools keyed by index stay separate`() {
        // tool_calls[index:0,id:call_A], [index:1,id:call_B], then arg-only
        // interleaved deltas with null id but their own index.
        val result = assistantSeed().applyAll(
            chunkOf(toolDelta(id = "call_A", name = "a", input = "{\"x\":", streamIndex = 0)),
            chunkOf(toolDelta(id = "call_B", name = "b", input = "{\"y\":", streamIndex = 1)),
            chunkOf(toolDelta(input = "1}", streamIndex = 0)),
            chunkOf(toolDelta(input = "2}", streamIndex = 1))
        )

        val tools = result.tools()
        assertEquals(2, tools.size)
        val a = tools.first { it.toolCallId == "call_A" }
        val b = tools.first { it.toolCallId == "call_B" }
        assertEquals("{\"x\":1}", a.input)
        assertEquals("{\"y\":2}", b.input)
    }

    // ==================== Guards — persisted schema + equality ====================

    @Test
    fun `finalized Tool serializes without any streamIndex field`() {
        val result = assistantSeed().applyAll(
            chunkOf(toolDelta(id = "call_1", name = "search", input = "{\"q\":", streamIndex = 0)),
            chunkOf(toolDelta(input = "1}", streamIndex = 0))
        )

        val tool = result.tools().single()
        val encoded = json.encodeToString(UIMessagePart.serializer(), tool)
        assertFalse(encoded.contains("streamIndex"))
        assertFalse(encoded.contains("\"index\""))
    }

    @Test
    fun `two Tools differing only in streamIndex remain equal`() {
        val a = toolDelta(id = "call_1", name = "search", input = "{}", streamIndex = 0)
        val b = toolDelta(id = "call_1", name = "search", input = "{}", streamIndex = 7)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
