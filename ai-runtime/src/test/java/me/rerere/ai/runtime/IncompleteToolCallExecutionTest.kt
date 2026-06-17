package me.rerere.ai.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.ToolCallExecutionState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toolCallExecutionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun `toolExecutionErrorResult redacts the stack trace and package-qualified class name`() {
        // A throwable whose stack trace and FQCN carry internal file paths / package
        // layout. The model-facing payload must keep only the actionable summary.
        val boom = IllegalStateException("disk at /home/user/secret/path failed")
        val text = (toolExecutionErrorResult(json, boom).single() as UIMessagePart.Text).text

        assertTrue("summary keeps the simple class name", text.contains("IllegalStateException"))
        assertTrue("summary keeps the message", text.contains("disk at"))
        assertFalse("must not leak the FQCN", text.contains(IllegalStateException::class.java.name))
        assertFalse("must not leak stack frames", text.contains("\tat "))
        assertFalse("must not leak the runtime package path", text.contains("at me.rerere"))
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

    // --- executeTool branch coverage (issue #244, god-function split #3) ---
    //
    // executeTool was extracted from generateText's per-tool `when (tool.approvalState)` block. These
    // pin one byte-identical-output case per branch, so an extraction that drops/reorders a branch or
    // swallows the CancellationException re-throw reddens here.

    private fun toolPart(
        input: String,
        finished: Boolean,
        approvalState: ToolApprovalState,
        toolName: String = "search",
    ): UIMessagePart.Tool =
        UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = toolName,
            input = input,
            output = emptyList(),
            approvalState = approvalState,
        ).also { it.finished = finished }

    private fun toolDef(name: String, execute: suspend () -> List<UIMessagePart>): Tool =
        Tool(name = name, description = "", execute = { execute() })

    @Test
    fun `executeTool Denied with blank reason yields the no-reason-provided denied JSON`() = runBlocking {
        val denied = toolPart(input = "{}", finished = true, approvalState = ToolApprovalState.Denied(reason = ""))
        val result = executeTool(denied, emptyList(), json, RecordingLogSink())
        val text = (result.output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("denied by user"))
        assertTrue(text.contains("No reason provided"))
    }

    @Test
    fun `executeTool Denied with a reason carries that reason into the denied JSON`() = runBlocking {
        val denied = toolPart(input = "{}", finished = true, approvalState = ToolApprovalState.Denied(reason = "too risky"))
        val result = executeTool(denied, emptyList(), json, RecordingLogSink())
        val text = (result.output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("too risky"))
        assertFalse(text.contains("No reason provided"))
    }

    @Test
    fun `executeTool Answered yields a single Text part with the answer verbatim`() = runBlocking {
        val answered = toolPart(input = "{}", finished = true, approvalState = ToolApprovalState.Answered(answer = "the answer"))
        val result = executeTool(answered, emptyList(), json, RecordingLogSink())
        assertEquals("the answer", (result.output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `executeTool happy path records the tool definition's output`() = runBlocking {
        val executed = toolPart(input = "{\"city\":\"SF\"}", finished = true, approvalState = ToolApprovalState.Approved)
        val out = listOf<UIMessagePart>(UIMessagePart.Text("done"))
        val result = executeTool(executed, listOf(toolDef("search") { out }), json, RecordingLogSink())
        assertEquals("done", (result.output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `executeTool IncompleteTruncated routes to the clean retry message`() = runBlocking {
        val truncated = toolPart(input = "{\"city\":\"S", finished = false, approvalState = ToolApprovalState.Auto)
        // Sanity: this input IS classified truncated, so the branch under test is the one exercised.
        assertEquals(ToolCallExecutionState.IncompleteTruncated, truncated.toolCallExecutionState())
        val result = executeTool(truncated, emptyList(), json, RecordingLogSink())
        val text = (result.output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("re-issue"))
        assertFalse(text.contains("Unexpected EOF"))
    }

    @Test
    fun `executeTool re-throws CancellationException from the tool body - not swallowed into error JSON`() {
        val executed = toolPart(input = "{}", finished = true, approvalState = ToolApprovalState.Approved)
        val tools = listOf<Tool>(toolDef("search") { throw CancellationException("stopped") })
        // Stop-generation must surface as cancellation, NEVER be reported as a tool execution error.
        assertThrows(CancellationException::class.java) {
            runBlocking { executeTool(executed, tools, json, RecordingLogSink()) }
        }
    }

    @Test
    fun `executeTool turns a plain exception into a redacted error JSON without a stack trace`() = runBlocking {
        val executed = toolPart(input = "{}", finished = true, approvalState = ToolApprovalState.Approved)
        val boom = IllegalStateException("boom")
        val tools = listOf<Tool>(toolDef("search") { throw boom })
        val result = executeTool(executed, tools, json, RecordingLogSink())
        val text = (result.output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("error"))
        // Actionable summary: simple class name + message, so the model can react.
        assertTrue(text.contains(IllegalStateException::class.java.simpleName))
        assertTrue(text.contains("boom"))
        // Redaction: the model-facing payload must NOT leak the package-qualified
        // class name or any stack frame (internal file paths / package layout).
        assertFalse(text.contains(IllegalStateException::class.java.name))
        assertFalse(text.contains("at me.rerere"))
        assertFalse(text.contains("\tat "))
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
