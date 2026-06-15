package me.rerere.rikkahub.data.repository

import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the workspace-shell execution gate (issue #197 HP-1, I-ENABLE / design note
 * security-model-design:197 §4.3).
 *
 * The invariant: a shell command may run ONLY when the owning workspace has the user toggle
 * [WorkspaceEntity.shellEnabled] set AND its rootfs is [WorkspaceShellStatus.READY]. Before this
 * fix the toggle/status pair was display-only — [WorkspaceRepository.executeCommand] dispatched to
 * the PRoot manager regardless of either field, so a disabled (or BROKEN/INSTALLING) workspace
 * would still execute. [isShellRunnable] is the pure predicate the sink evaluates BEFORE entering
 * `runInterruptible`/touching the manager; pinning it pins "the manager is unreachable when the
 * shell is not runnable", because the early-return precedes any `manager.*` call.
 *
 * FAIL-BEFORE rationale: on the unfixed code this test does not even compile — [isShellRunnable]
 * does not exist, and the sink had no gate at all. After the fix every case below holds.
 *
 * NOTE on the live surface (mirrors WorkspaceToolsTest's "NOTE on the live surface"): a full
 * `WorkspaceRepository.executeCommand` integration test is NOT in the JVM unit source set because
 * the repository ctor needs `SettingsStore` (a final class requiring an Android Context + AppScope)
 * and the :app unit-test classpath has no Robolectric/mockk — the same constraint and in-repo
 * precedent as WorkspaceToolsTest (testing `resolveWorkspaceToolApproval`) and
 * MessageFtsTransactionTest (testing the extracted `reindexConversationFts`). The pure predicate IS
 * the chokepoint, so unit-testing it directly is the strongest CI-runnable guarantee.
 */
class WorkspaceRepositoryShellEnableTest {

    private val ready = WorkspaceShellStatus.READY.name

    // The ONLY permit case: toggle on AND rootfs ready.
    @Test
    fun `runnable only when enabled and ready`() {
        assertTrue(isShellRunnable(shellEnabled = true, shellStatus = ready))
    }

    // The display-only-toggle regression: rootfs is READY but the user toggle is off -> must reject.
    @Test
    fun `disabled toggle rejects even when rootfs ready`() {
        assertFalse(isShellRunnable(shellEnabled = false, shellStatus = ready))
    }

    // Toggle on, but rootfs not ready (every non-READY status, plus an unknown/garbage string) -> reject.
    @Test
    fun `enabled but rootfs not ready rejects`() {
        assertFalse(isShellRunnable(shellEnabled = true, shellStatus = WorkspaceShellStatus.DISABLED.name))
        assertFalse(isShellRunnable(shellEnabled = true, shellStatus = WorkspaceShellStatus.INSTALLING.name))
        assertFalse(isShellRunnable(shellEnabled = true, shellStatus = WorkspaceShellStatus.BROKEN.name))
        // A corrupt/unknown status string must NOT be treated as runnable.
        assertFalse(isShellRunnable(shellEnabled = true, shellStatus = "garbage"))
        assertFalse(isShellRunnable(shellEnabled = true, shellStatus = ""))
    }

    // Both off — the trivially-rejected case.
    @Test
    fun `disabled and not ready rejects`() {
        assertFalse(isShellRunnable(shellEnabled = false, shellStatus = WorkspaceShellStatus.DISABLED.name))
    }

    // Metamorphic: with status pinned READY, flipping shellEnabled false->true is the ONLY thing that
    // flips reject->permit. No other status value can permit, and the toggle alone cannot permit a
    // non-ready rootfs.
    @Test
    fun `flipping enabled at ready flips reject to permit and nothing else`() {
        assertFalse(isShellRunnable(shellEnabled = false, shellStatus = ready))
        assertTrue(isShellRunnable(shellEnabled = true, shellStatus = ready))
        // The flip does not generalize to a non-ready status.
        assertFalse(isShellRunnable(shellEnabled = false, shellStatus = WorkspaceShellStatus.BROKEN.name))
        assertFalse(isShellRunnable(shellEnabled = true, shellStatus = WorkspaceShellStatus.BROKEN.name))
    }

    // NO_PROCESS_WHEN_DISABLED for the auto-background entry point (issue #291): the new
    // [WorkspaceRepository.startBackgroundCommand] re-runs the SAME [isShellRunnable] guard BEFORE the
    // coordinator can start a process (it returns the byte-identical "Shell is not enabled" inline
    // result on the disabled/not-ready path). So the disabled cases below also pin "the coordinator's
    // startHandle is unreachable", exactly as they pin it for the blocking executeCommand sink — the
    // predicate is the single chokepoint both shell entry points share, so duplicating it across a
    // (Android-ctor-coupled) integration test would add no CI-runnable coverage the predicate lacks.
    @Test
    fun `background command obeys the same disabled guard`() {
        // Disabled or not-ready -> the background entry must short-circuit before any process.
        assertFalse(isShellRunnable(shellEnabled = false, shellStatus = ready))
        assertFalse(isShellRunnable(shellEnabled = true, shellStatus = WorkspaceShellStatus.INSTALLING.name))
        // Only the fully-runnable workspace admits a backgrounded process.
        assertTrue(isShellRunnable(shellEnabled = true, shellStatus = ready))
    }
}
