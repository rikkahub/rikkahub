package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.ToolCallExecutionState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toolCallExecutionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
