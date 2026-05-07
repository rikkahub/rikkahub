package me.rerere.rikkahub.workflow.execution

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.workflow.model.WorkflowAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 12 — workflow action runner ALWAYS routes through HardlineCommandGuard, regardless
 * of headless mode. The classic `rm -rf /` smoke test, plus a couple of nearby surface
 * checks (unknown tool, plain success path, per-action timeout).
 */
class HardlineInWorkflowTest {

    private val toastTool = Tool(
        name = "show_toast",
        description = "show",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    private val termuxTool = Tool(
        name = "termux_run_command",
        description = "shell",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = { listOf(UIMessagePart.Text("ran")) },
    )

    @Test fun `rm -rf root blocked by hardline`() = runBlocking {
        val runner = WorkflowActionRunner()
        val actions = listOf(
            WorkflowAction(
                tool = "termux_run_command",
                args = buildJsonObject { put("command", "rm -rf /") },
                timeoutSeconds = 10,
            ),
        )
        val result = runner.run(actions, listOf(termuxTool))
        assertFalse("rm -rf / must not succeed", result.success)
        assertTrue(
            "expected hardline error, got ${result.error}",
            result.error?.contains("hardline", ignoreCase = true) == true,
        )
    }

    @Test fun `unknown tool short-circuits`() = runBlocking {
        val runner = WorkflowActionRunner()
        val actions = listOf(
            WorkflowAction(tool = "format_disk", args = buildJsonObject {}, timeoutSeconds = 10),
        )
        val result = runner.run(actions, listOf(toastTool))
        assertFalse(result.success)
        assertTrue(result.error?.contains("unknown_tool") == true)
    }

    @Test fun `clean toast action succeeds`() = runBlocking {
        val runner = WorkflowActionRunner()
        val actions = listOf(
            WorkflowAction(tool = "show_toast", args = buildJsonObject { put("text", "hi") }, timeoutSeconds = 10),
        )
        val result = runner.run(actions, listOf(toastTool))
        assertTrue("expected success: ${result.error}", result.success)
    }

    @Test fun `aborts on first failure leaves later actions un-run`() = runBlocking {
        var lateExecuted = false
        val lateTool = Tool(
            name = "late_tool",
            description = "",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = { lateExecuted = true; listOf(UIMessagePart.Text("late")) },
        )
        val runner = WorkflowActionRunner()
        val actions = listOf(
            WorkflowAction(tool = "format_disk", args = buildJsonObject {}, timeoutSeconds = 10),
            WorkflowAction(tool = "late_tool", args = buildJsonObject {}, timeoutSeconds = 10),
        )
        val result = runner.run(actions, listOf(lateTool))
        assertFalse(result.success)
        assertFalse("late tool must NOT have executed after the early failure", lateExecuted)
    }

    @Test fun `non-hardline ssh command runs`() = runBlocking {
        val ssh = Tool(
            name = "ssh_exec",
            description = "",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = { listOf(UIMessagePart.Text("ok")) },
        )
        val runner = WorkflowActionRunner()
        val actions = listOf(
            WorkflowAction(
                tool = "ssh_exec",
                args = buildJsonObject { put("command", "uname -a") },
                timeoutSeconds = 10,
            ),
        )
        val result = runner.run(actions, listOf(ssh))
        assertTrue("uname -a must not be hardline-blocked", result.success)
    }
}
