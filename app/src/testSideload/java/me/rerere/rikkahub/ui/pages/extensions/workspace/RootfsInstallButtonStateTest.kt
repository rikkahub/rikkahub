package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the install-button state machine extracted from [SideloadWorkspaceControls]
 * ([rootfsInstallButtonState]) — the only CI-runnable surface of slice 6b (the PTY/Compose terminal
 * UI is Android-only). The button must read INSTALLING from EITHER the persisted shellStatus OR a
 * live in-flight progress object, and READY only when the row says READY and no install is running,
 * mirroring upstream's `installing = installProgress != null || status == INSTALLING; rootfsReady =
 * status == READY`. This test lives in the sideload test source set because the function under test
 * is sideload-only (physically absent from the Play APK, I-FLAVOR).
 */
class RootfsInstallButtonStateTest {

    @Test
    fun `installing status reports installing even with no progress object`() {
        val state = rootfsInstallButtonState(
            shellStatus = WorkspaceShellStatus.INSTALLING.name,
            installProgressActive = false,
        )
        assertTrue(state.installing)
        assertFalse(state.rootfsReady)
    }

    @Test
    fun `active install progress reports installing regardless of status`() {
        WorkspaceShellStatus.entries.forEach { status ->
            val state = rootfsInstallButtonState(
                shellStatus = status.name,
                installProgressActive = true,
            )
            assertTrue(
                "installProgressActive must force installing for status=${status.name}",
                state.installing,
            )
        }
    }

    @Test
    fun `ready status with no progress is rootfsReady and not installing`() {
        val state = rootfsInstallButtonState(
            shellStatus = WorkspaceShellStatus.READY.name,
            installProgressActive = false,
        )
        assertFalse(state.installing)
        assertTrue(state.rootfsReady)
    }

    @Test
    fun `disabled broken and null status with no progress are neither installing nor ready`() {
        listOf(
            WorkspaceShellStatus.DISABLED.name,
            WorkspaceShellStatus.BROKEN.name,
            null,
        ).forEach { status ->
            val state = rootfsInstallButtonState(
                shellStatus = status,
                installProgressActive = false,
            )
            assertFalse("status=$status must not be installing", state.installing)
            assertFalse("status=$status must not be rootfsReady", state.rootfsReady)
        }
    }

    // Metamorphic: holding the persisted status constant, flipping the live progress flag
    // false -> true must flip `installing` false -> true (the in-flight install overrides a
    // not-yet-committed status). READY is the discriminating case: without the progress override it
    // is the only non-installing-yet-relevant status, so this proves the progress term, not the
    // status term, drives the flip.
    @Test
    fun `toggling install progress active flips installing with status held constant`() {
        val before = rootfsInstallButtonState(
            shellStatus = WorkspaceShellStatus.READY.name,
            installProgressActive = false,
        )
        val after = rootfsInstallButtonState(
            shellStatus = WorkspaceShellStatus.READY.name,
            installProgressActive = true,
        )
        assertFalse(before.installing)
        assertTrue(after.installing)
        assertEquals(before.rootfsReady, after.rootfsReady)
    }
}
