package me.rerere.rikkahub.utils

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Pure-logic test for [shouldRethrowVmError], the rethrow-vs-report classifier that backs [launchVm].
 * Keeping the decision pure lets it run on the JVM without coroutines-test / Android, mirroring
 * [appendChatError] and [shouldAutoCompact].
 *
 * The invariant this guards: a ViewModel coroutine wrapped by [launchVm] must RETHROW cancellation
 * (so structured-concurrency teardown is never swallowed) and REPORT every other throwable to local
 * UI state (so a recoverable operation failure does not escape to the scope's uncaught handler —
 * which CrashHandler.install turns into markCrashed -> safe-mode). Before this change there was no
 * such classifier; a throwable escaping a bare `viewModelScope.launch { }` propagated to the default
 * uncaught-exception handler and crashed the app.
 */
class VmSafeLaunchTest {

    private class JobCancelled : CancellationException("job cancelled")

    @Test
    fun `cancellation is rethrown`() {
        assertTrue(shouldRethrowVmError(CancellationException()))
        // Subclass (e.g. a job/timeout cancellation) must also propagate, not be reported as an error.
        assertTrue(shouldRethrowVmError(JobCancelled()))
    }

    @Test
    fun `recoverable throwables are reported not rethrown`() {
        assertFalse(shouldRethrowVmError(RuntimeException("boom")))
        assertFalse(shouldRethrowVmError(IllegalStateException("bad state")))
        assertFalse(shouldRethrowVmError(IOException("disk")))
    }
}
