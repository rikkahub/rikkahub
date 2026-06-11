package me.rerere.rikkahub.ui.pages.backup

import kotlinx.coroutines.CancellationException
import me.rerere.common.state.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Regression guard for issue #87 (swallowed cancellation in VM list loads).
 *
 * [BackupVM.loadBackupFileItems] / [loadS3BackupFileItems] previously wrapped the load in
 * `runCatching { }.onFailure { emit(UiState.Error(it)) }`, which turned a CancellationException
 * (e.g. the VM being cleared mid-load) into a user-facing [UiState.Error]. The pure
 * [backupListThrowableToState] now decides the rethrow-vs-report outcome: cancellation returns null
 * so the caller rethrows it (structured-concurrency teardown is never swallowed), every other
 * throwable maps to [UiState.Error].
 *
 * This test FAILS on the pre-fix code (which had no such function and unconditionally emitted Error)
 * and PASSES after. It also documents the contract that the translator errorFlow, imggen `_error`,
 * and skills onResult sites (which route through [me.rerere.rikkahub.utils.shouldRethrowVmError],
 * covered by VmSafeLaunchTest) must likewise NOT receive cancellation.
 */
class BackupListCancellationTest {

    private class JobCancelled : CancellationException("job cancelled")

    @Test
    fun `cancellation returns null so the caller rethrows instead of emitting Error`() {
        assertNull(backupListThrowableToState<List<String>>(CancellationException()))
        // Subclass (e.g. a job/timeout cancellation) must also be rethrown, not reported.
        assertNull(backupListThrowableToState<List<String>>(JobCancelled()))
    }

    @Test
    fun `recoverable throwables map to UiState Error carrying the throwable`() {
        val io = IOException("network down")
        val state = backupListThrowableToState<List<String>>(io)
        assertTrue(state is UiState.Error)
        assertEquals(io, (state as UiState.Error).error)

        val runtime = RuntimeException("boom")
        assertEquals(UiState.Error(runtime), backupListThrowableToState<List<String>>(runtime))
    }
}
