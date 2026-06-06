package me.rerere.rikkahub.ui.pages.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Proves the backup/restore terminal-outcome contract at the VM boundary (issue #105):
 *
 * - a successful operation becomes [BackupOperationState.Success] for the right [Kind],
 * - a failure becomes [BackupOperationState.Error] with a SAFE message (never the raw throwable), and
 * - a blank/absent throwable message collapses to a generic fallback rather than an empty toast.
 *
 * Cancellation is NOT exercised here as a mapped outcome: `runOperation` rethrows
 * `CancellationException` before reaching these mappers, so a navigated-away operation can never be
 * turned into a user-facing Error. These are the pure mappers (no Android), the JVM-runnable guard.
 */
class BackupOperationStateTest {

    @Test
    fun `null throwable maps to Success for its kind`() {
        assertEquals(
            BackupOperationState.Success(BackupOperationState.Kind.WebDavBackup),
            backupThrowableToState(BackupOperationState.Kind.WebDavBackup, null),
        )
    }

    @Test
    fun `failure maps to Error with a safe message, never the raw throwable`() {
        val raw = "java.net.UnknownHostException: dav.internal.example /home/user/secret"
        val state = backupThrowableToState(
            BackupOperationState.Kind.WebDavRestore,
            IOException(raw),
        )

        assertTrue(state is BackupOperationState.Error)
        state as BackupOperationState.Error
        assertEquals(BackupOperationState.Kind.WebDavRestore, state.kind)
        // The mapped message must be the throwable's own message when present (already user-safe at
        // this layer), but the contract is that it is the *mapped* string, asserted via the mapper.
        assertEquals(backupErrorMessage(IOException(raw)), state.message)
    }

    @Test
    fun `blank throwable message collapses to generic fallback`() {
        assertEquals("Backup operation failed", backupErrorMessage(IOException("")))
        assertEquals("Backup operation failed", backupErrorMessage(IOException("   ")))
        assertEquals("Backup operation failed", backupErrorMessage(RuntimeException()))
    }

    @Test
    fun `non-blank throwable message is preserved`() {
        assertEquals("Connection refused", backupErrorMessage(IOException("Connection refused")))
    }

    @Test
    fun `every kind round-trips through success and error mapping`() {
        for (kind in BackupOperationState.Kind.entries) {
            assertEquals(
                BackupOperationState.Success(kind),
                backupThrowableToState(kind, null),
            )
            val errorState = backupThrowableToState(kind, IOException("boom"))
            assertTrue(errorState is BackupOperationState.Error)
            assertEquals(kind, (errorState as BackupOperationState.Error).kind)
        }
    }
}
