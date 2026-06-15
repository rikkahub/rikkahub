package me.rerere.rikkahub.service

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STOP_IS_DETACH_NOT_KILL finalizer guard (issue #291). A user stop during a `workspace_shell`
 * foreground wait BACKGROUNDS the run — the coordinator persisted DETACHED under NonCancellable and
 * launched a detached awaiter on AppScope, and the completion arrives later as a synthetic #290
 * event. So the turn finalizer ([ChatService.cancelToolByUser]) must NOT stamp `{status:cancelled}`
 * over that still-pending shell tool part.
 *
 * The predicate is TRUE only when ALL hold:
 *   1. !isExecuted (no inline output yet — coordinator re-threw CancellationException)
 *   2. !isPending  (already approved — a process was actually started)
 *   3. toolName == "workspace_shell"
 *   4. input.detachAfterSeconds > 0 (the call explicitly opted into background mode)
 *
 * Conditions 2 & 4 were absent in the initial implementation and are covered here.
 */
class ShellBackgroundOnStopTest {

    private fun tool(
        toolName: String,
        executed: Boolean,
        approvalState: ToolApprovalState = ToolApprovalState.Auto,
        input: String = "{}",
    ) = UIMessagePart.Tool(
        toolCallId = "call_1",
        toolName = toolName,
        input = input,
        output = if (executed) listOf(UIMessagePart.Text("{}")) else emptyList(),
        approvalState = approvalState,
    )

    // ── true cases ──────────────────────────────────────────────────────────────

    @Test
    fun `not-executed workspace_shell with detachAfterSeconds is backgrounded on stop`() {
        assertTrue(
            shouldBackgroundShellOnStop(
                tool("workspace_shell", executed = false, input = """{"command":"echo hi","detachAfterSeconds":30}""")
            )
        )
    }

    // ── false cases ─────────────────────────────────────────────────────────────

    @Test
    fun `executed workspace_shell is not a background case`() {
        assertFalse(
            shouldBackgroundShellOnStop(
                tool("workspace_shell", executed = true, input = """{"command":"echo hi","detachAfterSeconds":30}""")
            )
        )
    }

    @Test
    fun `approval-pending workspace_shell is NOT backgrounded — cancel normally`() {
        // No process was ever started for an approval-pending call; leaving it pending would strand it.
        assertFalse(
            shouldBackgroundShellOnStop(
                tool(
                    "workspace_shell",
                    executed = false,
                    approvalState = ToolApprovalState.Pending,
                    input = """{"command":"rm -rf /","detachAfterSeconds":60}""",
                )
            )
        )
    }

    @Test
    fun `workspace_shell without detachAfterSeconds is NOT backgrounded`() {
        // Default-kill shells: the coordinator uses the blocking executeCommand path, so no completion
        // event ever arrives. Leaving the tool part pending would strand it permanently.
        assertFalse(
            shouldBackgroundShellOnStop(
                tool("workspace_shell", executed = false, input = """{"command":"echo hi"}""")
            )
        )
    }

    @Test
    fun `workspace_shell with detachAfterSeconds 0 is NOT backgrounded`() {
        assertFalse(
            shouldBackgroundShellOnStop(
                tool("workspace_shell", executed = false, input = """{"command":"echo hi","detachAfterSeconds":0}""")
            )
        )
    }

    @Test
    fun `other interrupted tools are still cancelled`() {
        assertFalse(shouldBackgroundShellOnStop(tool("ui_set_text", executed = false)))
        assertFalse(shouldBackgroundShellOnStop(tool("workspace_write_file", executed = false)))
        assertFalse(shouldBackgroundShellOnStop(tool("web_search", executed = false)))
    }
}
