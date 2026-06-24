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

    // (A) Unknown tools fail CLOSED: no override AND no default entry now requires approval (issue
    // #356 finding #6), so a future `workspace_*` tool whose default entry is forgotten can never
    // silently become no-approval. (Was: fell through to the relaxed `?: false`.)
    @Test
    fun `unknown tool fails closed and requires approval`() {
        assertTrue(resolveWorkspaceToolApproval("unknown_tool", emptyMap()))
        // The issue's concrete example: a plausible future tool not yet registered in the defaults map.
        assertTrue(resolveWorkspaceToolApproval("workspace_chmod_file", emptyMap()))
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

    // (F) COVERAGE (issue #356 finding #6): every workspace tool the factories create must have an
    // explicit [WorkspaceToolDefaultApprovals] entry, so the fail-closed fallback only ever fires for
    // a genuinely-new, not-yet-registered tool — never for a shipped tool whose entry was forgotten.
    // The factories (`createWorkspaceTools` + `sideloadWorkspaceTools`) are WorkspaceRepository-coupled
    // and can't be instantiated in pure JVM (the same constraint this file documents above), so this
    // pins the canonical name set they produce against the defaults map: adding a `workspace_*` factory
    // tool without a default-approval entry breaks this test — update the factory list AND the map
    // together. The map is the single source of truth the resolver fails closed against.
    @Test
    fun `every workspace factory tool has an explicit default-approval entry`() {
        val factoryToolNames = setOf(
            "workspace_list_files",
            "workspace_read_file",
            "workspace_glob",
            "workspace_grep",
            "workspace_write_file",
            "workspace_edit_file",
            "workspace_delete_file",
            "workspace_move_file",
            "workspace_shell",
            "workspace_shell_tail",
        )
        assertEquals(
            "WorkspaceToolDefaultApprovals must have exactly one entry per factory-created workspace tool",
            factoryToolNames,
            WorkspaceToolDefaultApprovals.keys,
        )
        WorkspaceToolDefaultApprovals.keys.forEach { name ->
            assertTrue("default-approval key `$name` must be in the workspace_ namespace", name.startsWith("workspace_"))
        }
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

    // (E) workspace_read_file line windowing (the fix that stops the tool from dumping a whole large
    // file). windowTextByLines is the pure seam; these pin its boundary/invariant/metamorphic behavior.
    private fun linesText(n: Int): String = (1..n).joinToString("\n") { "line$it" }

    // INVARIANT: a file at or under the default limit comes back whole, with no hasMore and the full
    // [1, total] range reported. This is the unchanged-behavior case for ordinary files.
    @Test
    fun `read window returns the whole file when within the default limit`() {
        val window = windowTextByLines(linesText(10), offset = null, limit = null)
        assertEquals(linesText(10), window.text)
        assertEquals(10, window.totalLines)
        assertEquals(1, window.startLine)
        assertEquals(10, window.endLine)
        assertFalse(window.hasMore)
    }

    // BOUNDARY: an explicit small limit truncates from the top and flags hasMore so the model pages on.
    @Test
    fun `read window truncates to limit and flags hasMore`() {
        val window = windowTextByLines(linesText(100), offset = null, limit = 30)
        assertEquals(linesText(30), window.text)
        assertEquals(100, window.totalLines)
        assertEquals(1, window.startLine)
        assertEquals(30, window.endLine)
        assertTrue(window.hasMore)
    }

    // BOUNDARY: with no explicit limit a file larger than the default is windowed to the default, not
    // dumped whole — the exact behavior that fixes the UI lag on 1000+ line files.
    @Test
    fun `read window caps an oversized file at the default limit`() {
        val window = windowTextByLines(linesText(DEFAULT_READ_FILE_LINE_LIMIT + 50), offset = null, limit = null)
        assertEquals(DEFAULT_READ_FILE_LINE_LIMIT, window.endLine)
        assertEquals(DEFAULT_READ_FILE_LINE_LIMIT + 50, window.totalLines)
        assertTrue(window.hasMore)
    }

    // BOUNDARY: a mid-file window honors offset; the last window of a file reports hasMore=false.
    @Test
    fun `read window honors offset and clears hasMore on the last window`() {
        val mid = windowTextByLines(linesText(100), offset = 41, limit = 10)
        assertEquals(linesText(100).split("\n").subList(40, 50).joinToString("\n"), mid.text)
        assertEquals(41, mid.startLine)
        assertEquals(50, mid.endLine)
        assertTrue(mid.hasMore)

        val tail = windowTextByLines(linesText(100), offset = 91, limit = 50)
        assertEquals(91, tail.startLine)
        assertEquals(100, tail.endLine)
        assertFalse(tail.hasMore)
    }

    // BOUNDARY: an empty file is zero lines (cat -n style), not a phantom single empty line.
    @Test
    fun `read window of an empty file reports zero lines`() {
        val window = windowTextByLines("", offset = null, limit = null)
        assertEquals("", window.text)
        assertEquals(0, window.totalLines)
        assertEquals(0, window.startLine)
        assertEquals(0, window.endLine)
        assertFalse(window.hasMore)
    }

    // BOUNDARY: an offset past EOF yields an empty window (no throw), with 0/0 range and no hasMore.
    @Test
    fun `read window past end of file is empty`() {
        val window = windowTextByLines(linesText(10), offset = 999, limit = 10)
        assertEquals("", window.text)
        assertEquals(10, window.totalLines)
        assertEquals(0, window.startLine)
        assertEquals(0, window.endLine)
        assertFalse(window.hasMore)
    }

    // BOUNDARY: non-positive offset/limit are clamped to 1 rather than producing an empty/negative slice.
    @Test
    fun `read window clamps non-positive offset and limit`() {
        val window = windowTextByLines(linesText(5), offset = 0, limit = 0)
        assertEquals(1, window.startLine)
        assertEquals(1, window.endLine)
        assertEquals("line1", window.text)
    }

    // REGRESSION (trailing newline): a file saved with a final newline must not report a phantom extra
    // empty line. A 2000-real-line file + trailing "\n" must read whole (totalLines=2000, no hasMore),
    // not come back as 2001 lines truncated. The EOF newline must still be PRESERVED in the returned
    // text (byte-exact) so an edit touching the file end is derivable from the read output.
    @Test
    fun `read window does not count a trailing newline as an extra line but preserves it`() {
        val window = windowTextByLines(linesText(3) + "\n", offset = null, limit = null)
        assertEquals(3, window.totalLines)
        assertEquals("EOF newline preserved (byte-exact)", linesText(3) + "\n", window.text)
        assertEquals(3, window.endLine)
        assertFalse(window.hasMore)

        val full = windowTextByLines(linesText(DEFAULT_READ_FILE_LINE_LIMIT) + "\n", offset = null, limit = null)
        assertEquals(DEFAULT_READ_FILE_LINE_LIMIT, full.totalLines)
        assertFalse("a 2000-line file with a final newline is not truncated", full.hasMore)

        // A non-EOF window must NOT gain a trailing newline (only the EOF window carries it).
        val firstPage = windowTextByLines(linesText(10) + "\n", offset = 1, limit = 4)
        assertEquals(linesText(4), firstPage.text)
        assertTrue(firstPage.hasMore)
    }

    // REGRESSION (overflow): a limit near Int.MAX_VALUE must not overflow startIdx + count into an
    // invalid subList range — it just returns everything from offset to EOF.
    @Test
    fun `read window with a huge limit returns to end of file without overflow`() {
        val window = windowTextByLines(linesText(100), offset = 2, limit = Int.MAX_VALUE)
        assertEquals(2, window.startLine)
        assertEquals(100, window.endLine)
        assertEquals(99, window.text.split("\n").size)
        assertFalse(window.hasMore)
    }

    // METAMORPHIC: paging through a file in fixed windows and concatenating reconstructs it exactly —
    // no line dropped or duplicated at a window seam.
    @Test
    fun `sequential read windows reconstruct the whole file`() {
        val full = linesText(57)
        val page = 10
        val rebuilt = StringBuilder()
        var offset = 1
        while (true) {
            val window = windowTextByLines(full, offset, page)
            if (window.text.isEmpty()) break
            if (rebuilt.isNotEmpty()) rebuilt.append('\n')
            rebuilt.append(window.text)
            if (!window.hasMore) break
            offset = window.endLine + 1
        }
        assertEquals(full, rebuilt.toString())
    }
}
