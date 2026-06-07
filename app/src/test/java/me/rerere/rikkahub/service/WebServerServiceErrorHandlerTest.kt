package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Pure-logic test for [shouldLogServiceError], the predicate the WebServerService
 * CoroutineExceptionHandler consults before logging. Keeping the decision pure lets it run on the
 * JVM without an Android Service / android.util.Log dependency — the handler body (Log.e) is an
 * Android stub and is intentionally not unit-tested; the only real logic is this predicate.
 *
 * Invariants guarded:
 *  - Recoverable failures escaping a root serviceScope.launch (settings update on ACTION_STOP,
 *    updateNotification / foreground-service calls in the state observer) must be logged so the job
 *    does not silently die and so the failure never reaches the default uncaught-exception handler
 *    (which, with CrashHandler installed, escalates to a process crash / safe-mode).
 *  - CancellationException is normal structured-concurrency teardown (e.g. onDestroy() ->
 *    serviceScope.cancel()) and must NOT be logged as an error.
 */
class WebServerServiceErrorHandlerTest {

    @Test
    fun `recoverable throwables are logged`() {
        assertTrue(shouldLogServiceError(RuntimeException("settings update failed")))
        assertTrue(shouldLogServiceError(IllegalStateException("updateNotification failed")))
        assertTrue(shouldLogServiceError(IOException("foreground service call failed")))
    }

    @Test
    fun `cancellation is not logged as an error`() {
        assertFalse(shouldLogServiceError(CancellationException("scope cancelled in onDestroy")))
        // Subclass of CancellationException (mirrors structured-concurrency teardown variants).
        class ScopeCancelled : CancellationException("child cancelled")
        assertFalse(shouldLogServiceError(ScopeCancelled()))
    }
}
