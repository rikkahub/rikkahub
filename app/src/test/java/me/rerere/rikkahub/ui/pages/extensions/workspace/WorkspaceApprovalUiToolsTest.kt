package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.rikkahub.data.ai.tools.WorkspaceToolDefaultApprovals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The per-workspace Tool-approval UI used a hand-maintained tool list that drifted out of sync with the
 * actual tool set: workspace_glob / workspace_grep (and workspace_shell_tail) were created and had
 * default-approval entries, yet never appeared in the UI, so the user couldn't toggle their approval.
 *
 * This pins [WORKSPACE_TOOL_APPROVAL_UI_ORDER] to cover EXACTLY [WorkspaceToolDefaultApprovals] — every
 * tool the resolver knows about is listed, and nothing extra — so a newly added workspace tool reddens
 * here until it is also surfaced in the approval UI.
 */
class WorkspaceApprovalUiToolsTest {

    @Test
    fun `approval UI lists exactly the tools with a default-approval entry`() {
        assertEquals(
            "WORKSPACE_TOOL_APPROVAL_UI_ORDER must match WorkspaceToolDefaultApprovals (no omitted or stray tool)",
            WorkspaceToolDefaultApprovals.keys,
            WORKSPACE_TOOL_APPROVAL_UI_ORDER.toSet(),
        )
    }

    @Test
    fun `approval UI order has no duplicate entries`() {
        assertEquals(
            WORKSPACE_TOOL_APPROVAL_UI_ORDER.size,
            WORKSPACE_TOOL_APPROVAL_UI_ORDER.toSet().size,
        )
    }
}
