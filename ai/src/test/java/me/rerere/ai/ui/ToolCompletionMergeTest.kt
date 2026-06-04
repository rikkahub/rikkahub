package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the per-tool-call completion flag ([UIMessagePart.Tool.finished])
 * and its derived predicate ([toolCallExecutionState]).
 *
 * A tool call is only "finished" once the provider emits its terminating SSE
 * event (Anthropic content_block_stop, Responses API
 * function_call_arguments.done / output_item.done function_call). These cases
 * replay realistic delta sequences and assert the flag is carried through the
 * streaming merge without disturbing the input concatenation or the persisted
 * schema/equality guards.
 */
class ToolCompletionMergeTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    // ---- helpers (mirror MessageStreamMergeTest) -------------------------

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
        streamIndex: Int? = null,
        finished: Boolean = false
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = input,
        output = emptyList()
    ).also {
        it.streamIndex = streamIndex
        it.finished = finished
    }

    private fun List<UIMessage>.applyAll(vararg chunks: MessageChunk): List<UIMessage> {
        var acc = this
        for (c in chunks) acc = acc.handleMessageChunk(c)
        return acc
    }

    private fun List<UIMessage>.tools(): List<UIMessagePart.Tool> =
        last().parts.filterIsInstance<UIMessagePart.Tool>()

    // ==================== (a) completion delta carries finished ============

    @Test
    fun `Claude-style open tool plus fragments plus completion delta yields finished and identical input`() {
        // tool_use(open) + input_json_delta fragments + content_block_stop
        // completion marker (streamIndex=0, empty input, finished=true).
        val withCompletion = assistantSeed().applyAll(
            chunkOf(toolDelta(id = "toolu_1", name = "get_weather", input = "", streamIndex = 0)),
            chunkOf(toolDelta(input = "{\"ci", streamIndex = 0)),
            chunkOf(toolDelta(input = "ty\":\"", streamIndex = 0)),
            chunkOf(toolDelta(input = "SF\"}", streamIndex = 0)),
            chunkOf(toolDelta(streamIndex = 0, finished = true))
        )

        val withoutCompletion = assistantSeed().applyAll(
            chunkOf(toolDelta(id = "toolu_1", name = "get_weather", input = "", streamIndex = 0)),
            chunkOf(toolDelta(input = "{\"ci", streamIndex = 0)),
            chunkOf(toolDelta(input = "ty\":\"", streamIndex = 0)),
            chunkOf(toolDelta(input = "SF\"}", streamIndex = 0))
        )

        val finishedTool = withCompletion.tools().single()
        val unfinishedTool = withoutCompletion.tools().single()

        assertTrue(finishedTool.finished)
        assertEquals("get_weather", finishedTool.toolName)
        // Completion marker must NOT disturb the concatenated input.
        assertEquals("{\"city\":\"SF\"}", finishedTool.input)
        assertEquals(unfinishedTool.input, finishedTool.input)
    }

    // ==================== (b) no completion delta => not finished =========

    @Test
    fun `same sequence without completion delta yields not finished`() {
        val result = assistantSeed().applyAll(
            chunkOf(toolDelta(id = "toolu_1", name = "get_weather", input = "", streamIndex = 0)),
            chunkOf(toolDelta(input = "{\"ci", streamIndex = 0)),
            chunkOf(toolDelta(input = "ty\":\"", streamIndex = 0)),
            chunkOf(toolDelta(input = "SF\"}", streamIndex = 0))
        )

        assertFalse(result.tools().single().finished)
    }

    // ==================== (c) predicate ===================================

    @Test
    fun `toolCallExecutionState maps finished to Complete and not finished to IncompleteTruncated`() {
        val finished = toolDelta(id = "x", name = "n", input = "{}", finished = true)
        val unfinished = toolDelta(id = "x", name = "n", input = "{", finished = false)

        assertEquals(ToolCallExecutionState.Complete, finished.toolCallExecutionState())
        assertEquals(
            ToolCallExecutionState.IncompleteTruncated,
            unfinished.toolCallExecutionState()
        )
    }

    // ==================== (f) fail-open: parseable input => Complete even =
    //                          when finished was never set / was lost ======

    @Test
    fun `not-finished tool with parseable input is Complete (non-aware provider regression)`() {
        // ChatCompletions / Google never emit a terminating event, so they build
        // Tool with finished=false. Their input is fully parseable because it was
        // not streamed fragment-by-fragment. Keying truncation off finished alone
        // classified these as IncompleteTruncated and silently blocked execution.
        val tool = toolDelta(id = "call_1", name = "search", input = "{\"q\":\"x\"}", finished = false)
        assertEquals(ToolCallExecutionState.Complete, tool.toolCallExecutionState())
    }

    @Test
    fun `finished lost across copy keeps Complete when input is parseable (approval-resume regression)`() {
        // The approval flow does tool.copy(approvalState = ...) and persists to the
        // DB, both of which drop the @Transient finished flag. A genuinely complete
        // tool comes back finished=false; it must still execute, not get routed to
        // the truncation retry message after the user approved it.
        val finished = toolDelta(id = "call_1", name = "search", input = "{\"q\":\"x\"}", finished = true)
        val afterCopy = finished.copy(approvalState = ToolApprovalState.Approved)

        assertFalse("copy() drops the transient finished flag", afterCopy.finished)
        assertEquals(ToolCallExecutionState.Complete, afterCopy.toolCallExecutionState())
    }

    @Test
    fun `not-finished tool with lenient-only input is Complete (relaxed-JSON regression)`() {
        // The classifier's parseability check must match the executor's lenient
        // tool-argument parser: both accept the relaxed JSON LLMs emit, e.g. the
        // unquoted key {action:"create"}. With a strict check this finished=false
        // tool was classified IncompleteTruncated and short-circuited to a retry
        // result, so the executor's lenient salvage never ran — exactly on the
        // ChatCompletions/Google providers most likely to emit relaxed JSON.
        val tool = toolDelta(id = "call_1", name = "memory", input = "{action:\"create\"}", finished = false)
        assertEquals(ToolCallExecutionState.Complete, tool.toolCallExecutionState())
    }

    // ==================== (d) serialization + equality guards ============

    @Test
    fun `finished Tool serializes without any finished field`() {
        val tool = toolDelta(id = "call_1", name = "search", input = "{}", finished = true)
        val encoded = json.encodeToString(UIMessagePart.serializer(), tool)
        assertFalse(encoded.contains("finished"))
    }

    @Test
    fun `two Tools differing only in finished remain equal incl hashCode`() {
        val a = toolDelta(id = "call_1", name = "search", input = "{}", finished = false)
        val b = toolDelta(id = "call_1", name = "search", input = "{}", finished = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ==================== (e) by-id merge carries finished ===============

    @Test
    fun `by-id merge - finished delta flips the flag on the open tool`() {
        // ResponseAPI style: open function_call by id (no streamIndex), then a
        // finished marker delta with the same id and no streamIndex.
        val result = assistantSeed().applyAll(
            chunkOf(toolDelta(id = "call_1", name = "search", input = "{\"q\":1}")),
            chunkOf(toolDelta(id = "call_1", finished = true))
        )

        val tool = result.tools().single()
        assertTrue(tool.finished)
        assertEquals("{\"q\":1}", tool.input)
    }
}
