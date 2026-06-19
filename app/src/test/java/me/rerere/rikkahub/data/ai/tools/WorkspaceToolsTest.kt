package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the workspace-tools approval gate (issue #197 slice 5, ws-tools).
 *
 * The load-bearing security control is [resolveWorkspaceToolApproval]: it decides whether a given
 * workspace tool must be confirmed by the user before it runs. The write-capable and destructive
 * tools (shell, delete, move, write, edit) MUST default to approval-required; only the pure
 * read/inspect tools (list, read) default to no-approval. Arbitrary write/edit was flipped to
 * approval-required by issue #197 HP-1 (I-APPROVE, design note security-model-design:197 §4.2): an
 * LLM-driven arbitrary write must break the auto-loop exactly like shell/delete/move. A per-workspace
 * override always wins over the default — in either direction. When the stored policy blob is corrupt
 * the map is null and the resolver fails CLOSED (approval required for everything) — (C).
 *
 * NOTE on the live surface: per the issue #197 design-gate (section C), this slice exposes only the
 * read-only verbs (list/read). The write/edit/delete/move/shell factories are kept built-but-unwired
 * and re-enabled behind a sideload/security flavor by the workspace hardening pass. The default
 * approval map and the resolver are tested for all seven tool names regardless, because those names
 * are still the canonical defaults the hardening pass re-enables against.
 *
 * (D) additionally pins [countNonOverlappingOccurrences], the edit-file occurrence counter, to
 * replace/replaceFirst (non-overlapping) semantics.
 *
 * This pins the pure resolution function directly. The 7 tools' execute bodies and
 * [createWorkspaceTools]' Koin/Android wiring are SettingsStore/WorkspaceManager-coupled and not
 * unit-testable in pure JVM (no Robolectric/mockk on the :app unit-test classpath) — the same
 * constraint and precedent as McpToolsByAssistantTest, which tests its pure seam
 * (selectMcpToolsForAssistant) rather than the Android-coupled manager. The null/blank short-circuit
 * in [createWorkspaceTools] (an unbound assistant exposes zero tools) is a trivial early-return
 * verified by compile + the integration path; it is not exercised here because it cannot be without
 * either an unchecked null cast (forbidden) or a real WorkspaceRepository (unconstructible in CI).
 *
 * FAIL-BEFORE rationale: each assertion pins a specific invariant. If write/edit were ever relaxed
 * back to no-approval (the auto-write hole I-APPROVE closes), or if shell/delete/move were flipped
 * to false, (A) fails. If override precedence were inverted, (B) fails. These are the controls that
 * keep an LLM-driven write/exec surface gated behind a human confirmation.
 */
class WorkspaceToolsTest {

    // (A) Defaults: write-capable / destructive tools require approval; pure read/inspect tools do not.
    @Test
    fun `default approval requires confirmation for destructive tools only`() {
        // Write-capable / destructive — must default to approval-required. write/edit join this group
        // per I-APPROVE (#197 HP-1): arbitrary write must break the auto-loop like shell/delete/move.
        assertTrue(resolveWorkspaceToolApproval("workspace_shell", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_delete_file", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_move_file", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_write_file", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_edit_file", emptyMap()))

        // Pure read / inspect — default to no approval.
        assertFalse(resolveWorkspaceToolApproval("workspace_list_files", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_read_file", emptyMap()))
    }

    // (A) Unknown tools fall through to the final `?: false` (no approval, no crash).
    @Test
    fun `unknown tool defaults to no approval`() {
        assertFalse(resolveWorkspaceToolApproval("unknown_tool", emptyMap()))
    }

    // (B) A per-workspace override wins over the default — in both directions.
    @Test
    fun `override wins over default in both directions`() {
        // Override relaxes a dangerous default.
        assertFalse(
            resolveWorkspaceToolApproval("workspace_shell", mapOf("workspace_shell" to false))
        )
        // Override tightens a safe default.
        assertTrue(
            resolveWorkspaceToolApproval("workspace_read_file", mapOf("workspace_read_file" to true))
        )
    }

    // (B) An override for one tool does not leak to another.
    @Test
    fun `override does not leak across tools`() {
        assertTrue(
            resolveWorkspaceToolApproval("workspace_delete_file", mapOf("workspace_shell" to false))
        )
    }

    // (C) FAIL-CLOSED: a null overrides map (corrupt/unparseable policy blob) forces approval for
    // every tool, including ones whose relaxed DEFAULT is no-approval. This is the regression for the
    // "approval-override decode fails OPEN" finding: previously a corrupt blob collapsed to an empty
    // map and silently reverted user-tightened tools to no-approval.
    @Test
    fun `null overrides fail closed and require approval for every tool`() {
        // A tool that defaults to no-approval must STILL require approval when the policy is corrupt.
        assertTrue(resolveWorkspaceToolApproval("workspace_read_file", null))
        assertTrue(resolveWorkspaceToolApproval("workspace_write_file", null))
        assertTrue(resolveWorkspaceToolApproval("workspace_list_files", null))
        // Destructive tools stay approval-required (the safe direction is unchanged).
        assertTrue(resolveWorkspaceToolApproval("workspace_shell", null))
        // Even an unknown tool fails closed rather than falling through to `?: false`.
        assertTrue(resolveWorkspaceToolApproval("unknown_tool", null))
    }

    // (E) The workspace context note names the resolved project dir + the /workspace absolute escape,
    // so the agent knows WHERE relative paths land (the awareness gap absolute-path fallback exposed).
    @Test
    fun `workspace context note states the project dir and the absolute escape`() {
        // Files-root default (unset working_dir).
        val rootNote = workspaceContextPrompt("/workspace")
        assertTrue("names the project dir value", rootNote.contains("/workspace"))
        assertTrue("explains relative resolution", rootNote.contains("relative to this project directory"))

        // A set project dir is surfaced verbatim.
        val repoNote = workspaceContextPrompt("/workspace/rikkahub")
        assertTrue("names the set project dir", repoNote.contains("/workspace/rikkahub"))
        assertTrue("offers the /workspace absolute escape", repoNote.contains("begin a path with /workspace"))
    }

    // (E) SECURITY: a control char in the project dir (an attacker-influenceable directory name) must
    // NOT reach the system prompt — it is stripped at the boundary, so it cannot break the prompt
    // framing or inject instructions. Regression for the prompt-injection finding.
    @Test
    fun `workspace context note strips control characters from the project dir`() {
        val note = workspaceContextPrompt("/workspace/ev\nil\tx\u0000y")
        assertFalse("no newline reaches the prompt", note.contains('\n'))
        assertFalse("no tab reaches the prompt", note.contains('\t'))
        assertFalse("no NUL reaches the prompt", note.contains('\u0000'))
        // The visible characters survive (only the control chars are removed).
        assertTrue("non-control path text is preserved", note.contains("/workspace/evilxy"))
    }

    // (D) Edit occurrence counting matches replace/replaceFirst (NON-overlapping). Regression for the
    // overlapping-window bug: old_text="aa" in "aaa" must count as 1 (one non-overlapping match),
    // not 2 — otherwise replace_all=false wrongly rejects and replace_all=true over-reports.
    @Test
    fun `occurrence count is non-overlapping like replace`() {
        // Self-overlapping needle — the exact bug case.
        assertEquals(1, countNonOverlappingOccurrences("aaa", "aa"))
        assertEquals(2, countNonOverlappingOccurrences("aaaa", "aa"))
        // Reported count must equal what replace actually does.
        assertEquals(2, "aaaa".split("aa").size - 1)

        // Ordinary (non-overlapping) cases are unchanged.
        assertEquals(0, countNonOverlappingOccurrences("abc", "x"))
        assertEquals(1, countNonOverlappingOccurrences("abc", "b"))
        assertEquals(3, countNonOverlappingOccurrences("a.a.a.", "a."))
        // Empty needle never matches (the execute body separately rejects empty old_text).
        assertEquals(0, countNonOverlappingOccurrences("abc", ""))
    }
}
