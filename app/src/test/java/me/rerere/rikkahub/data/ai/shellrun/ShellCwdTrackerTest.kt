package me.rerere.rikkahub.data.ai.shellrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Unit coverage for the PURE pieces of the project-jailed drifting shell cwd (the workspace_shell cwd
 * feature). The orchestration (`WorkspaceRepository.executeTrackedCommand`) is Android/manager-coupled
 * and not JVM-instantiable here (same constraint as WorkspaceCwdResolutionTest), so the capture/parse
 * footer, the jail state machine, and the per-conversation tracker are pinned directly.
 */
class ShellCwdTrackerTest {

    // --- wrap + extract round-trip --------------------------------------------------------------

    @Test
    fun `extract recovers the final cwd and leaves the user stdout byte-exact`() {
        val token = "__rikkahub_cwd_TESTTOKEN__"
        val userStdout = "hello\nworld\n"
        // What the process actually emits: user stdout, then the footer's `printf token+pwd\n`.
        val captured = userStdout + token + "/workspace/test/subdir" + "\n"

        val (clean, pwd) = extractFinalCwd(captured, token)

        assertEquals("user stdout is preserved up to the token", userStdout, clean)
        assertEquals("/workspace/test/subdir", pwd)
    }

    @Test
    fun `extract strips only the marker line, preserving bytes a background writer wrote after it`() {
        val token = "__rikkahub_cwd_TESTTOKEN__"
        // A background child wrote after the EXIT-trap marker.
        val captured = "user out\n" + token + "/workspace/test\n" + "late background line\n"

        val (clean, pwd) = extractFinalCwd(captured, token)

        assertEquals("user out\nlate background line\n", clean)
        assertEquals("/workspace/test", pwd)
    }

    @Test
    fun `extract returns null and preserves stdout when the token is absent`() {
        // Footer never ran (exec/exit/syntax error) or was truncated past the 128 KiB cap.
        val (clean, pwd) = extractFinalCwd("just output, no footer", "__rikkahub_cwd_X__")

        assertEquals("just output, no footer", clean)
        assertNull(pwd)
    }

    @Test
    fun `capture token is random per call`() {
        assertNotEquals(newCwdCaptureToken(), newCwdCaptureToken())
    }

    // --- decideTrackedCwd: the project-jail state machine ---------------------------------------

    @Test
    fun `decide persists an in-jail descendant`() {
        assertEquals(
            ShellCwdDecision("test/subdir", ShellCwdStatus.PERSISTED),
            decideTrackedCwd(floor = "test", base = "test", finalCwd = "test/subdir"),
        )
    }

    @Test
    fun `decide reports unchanged when the final cwd equals base`() {
        assertEquals(
            ShellCwdDecision("test", ShellCwdStatus.UNCHANGED),
            decideTrackedCwd(floor = "test", base = "test", finalCwd = "test"),
        )
    }

    @Test
    fun `decide reverts a sibling escape to the latest in-jail dir`() {
        // base is a drifted-in subdir: a sibling escape reverts to it (the "latest project_dir"), not the floor.
        assertEquals(
            ShellCwdDecision("test/sub", ShellCwdStatus.REVERTED),
            decideTrackedCwd(floor = "test", base = "test/sub", finalCwd = "non-subdir"),
        )
    }

    @Test
    fun `decide reverts an ancestor escape (files root) to base`() {
        assertEquals(
            ShellCwdDecision("test", ShellCwdStatus.REVERTED),
            decideTrackedCwd(floor = "test", base = "test", finalCwd = ""),
        )
    }

    @Test
    fun `decide reverts a non-workspace path (null) to base`() {
        // parseShellPath returns null for a rootfs path like /etc => treated as outside the jail => revert.
        assertEquals(
            ShellCwdDecision("test/sub", ShellCwdStatus.REVERTED),
            decideTrackedCwd(floor = "test", base = "test/sub", finalCwd = null),
        )
    }

    @Test
    fun `decide persists deeper drift from a subdir base`() {
        assertEquals(
            ShellCwdDecision("test/sub/deep", ShellCwdStatus.PERSISTED),
            decideTrackedCwd(floor = "test", base = "test/sub", finalCwd = "test/sub/deep"),
        )
    }

    @Test
    fun `decide allows returning to the floor from a subdir`() {
        assertEquals(
            ShellCwdDecision("test", ShellCwdStatus.PERSISTED),
            decideTrackedCwd(floor = "test", base = "test/sub", finalCwd = "test"),
        )
    }

    // --- ShellCwdTracker: per (workspace, conversation) in-memory state -------------------------

    @Test
    fun `tracker stores cwd per workspace and conversation, isolating conversations`() {
        val tracker = ShellCwdTracker()
        val ws = "ws1"
        val c1 = Uuid.random()
        val c2 = Uuid.random()

        assertNull("unset is null", tracker.get(ws, c1))
        tracker.set(ws, c1, "test/sub")
        assertEquals("test/sub", tracker.get(ws, c1))
        assertNull("a different conversation is isolated", tracker.get(ws, c2))
    }
}
