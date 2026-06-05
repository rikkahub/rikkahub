package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.ToolCallExecutionState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toolCallExecutionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the GenerationHandler tool-execution decision as a pure
 * function.
 *
 * Before the fix: a tool call cut off by a stream interruption (finished=false,
 * partial un-parseable input) was fed straight into json.parseToJsonElement,
 * producing a raw "Unexpected EOF" the model could not act on. After the fix:
 * such a tool is [ToolCallExecutionState.IncompleteTruncated] and routes to
 * [truncatedToolResult], a clean re-issue request — never the raw JSON parse
 * error. Truncation requires BOTH not-finished AND un-parseable input, so a
 * complete-but-malformed tool (finished=true) still routes to the existing JSON
 * parse-error path, and a complete tool whose finished flag was lost across
 * copy/persist (finished=false but parseable input) still executes.
 */
class IncompleteToolCallExecutionTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private fun tool(input: String, finished: Boolean): UIMessagePart.Tool =
        UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "search",
            input = input,
            output = emptyList()
        ).also { it.finished = finished }

    @Test
    fun `truncated tool yields IncompleteTruncated and a clean retry message`() {
        // Stream cut off mid-arguments: finished never set, input is partial JSON.
        val truncated = tool(input = "{\"city\":\"S", finished = false)

        assertEquals(
            ToolCallExecutionState.IncompleteTruncated,
            truncated.toolCallExecutionState()
        )

        val result = truncatedToolResult(json)
        val text = (result.single() as UIMessagePart.Text).text
        // The model must get an actionable retry message, NOT a raw parser error.
        assertFalse(text.contains("Unexpected EOF"))
        assertFalse(text.contains("Invalid tool arguments JSON"))
        assertEquals(true, text.contains("re-issue"))
    }

    @Test
    fun `tool that succeeds with empty output is recorded as executed with an honest success placeholder`() {
        // Regression for the data-loss trigger: a tool whose execute() returns
        // emptyList() left output empty, so isExecuted stayed false. The agentic
        // loop (line-184 filter) then re-ran it and sanitizeForUpload dropped the
        // branch as an orphan tool_use, discarding the assistant's text/reasoning.
        val result = emptyList<UIMessagePart>()
        val recorded = tool(input = "{}", finished = true)
            .copy(output = result.ifEmpty { emptyToolResultPlaceholder(json) })

        // BEFORE the fix: tool.copy(output = emptyList()).isExecuted is false and
        // emptyToolResultPlaceholder did not exist.
        assertTrue("a successfully-executed empty-output tool must be executed", recorded.isExecuted)

        val text = (recorded.output.single() as UIMessagePart.Text).text
        // Honest success marker — not blank, not claiming failure.
        assertFalse("placeholder must not be blank", text.isBlank())
        assertTrue("placeholder must mark success", text.contains("ok"))
        assertFalse("placeholder must not claim an error", text.contains("error"))
        assertFalse("placeholder must not claim cancellation", text.contains("cancelled"))
    }

    @Test
    fun `complete valid tool yields Complete`() {
        val complete = tool(input = "{\"city\":\"SF\"}", finished = true)
        assertEquals(ToolCallExecutionState.Complete, complete.toolCallExecutionState())
    }

    @Test
    fun `complete malformed tool yields Complete - truncation guard does not swallow structural errors`() {
        // finished=true but arguments are structurally invalid: this is NOT a
        // truncation, so it must still flow to the existing JSON parse-error path.
        val malformed = tool(input = "{not valid json}", finished = true)
        assertEquals(ToolCallExecutionState.Complete, malformed.toolCallExecutionState())
    }

    @Test
    fun `finished-false tool with lenient-only input yields Complete - matches executor leniency`() {
        // ChatCompletions / Google have no per-tool terminating SSE event, and
        // copy()/DB round-trip drops `finished`, so a fully-received tool often
        // arrives finished=false. Its input is the relaxed JSON LLMs emit
        // (unquoted key {action:"create"}) — lenient-parseable, hence executable.
        // The classifier must agree with the executor's lenient parseToolArguments
        // and route this to execution, NOT short-circuit to a truncated retry.
        //
        // Before the fix, isInputParseable() used strict JSON, classified this
        // IncompleteTruncated, and the salvage in parseToolArguments never fired.
        val lenientOnly = tool(input = "{action:\"create\"}", finished = false)
        assertEquals(ToolCallExecutionState.Complete, lenientOnly.toolCallExecutionState())

        // And the executor's parser does in fact accept the same input, so the
        // classifier's verdict is sound (the two agree on "parseable").
        parseToolArguments(lenientOnly.input)
    }
}
