package me.rerere.rikkahub.ui.pages.imggen

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * Regression guard for issue #95 (image gen/edit errors must route through a cancellation-first
 * classifier covering the whole Throwable hierarchy).
 *
 * [ImgGenVM.generateImage] / [editImage] previously caught with a bespoke `catch (e: Exception)` arm.
 * A non-Exception Throwable (e.g. OutOfMemoryError) is NOT an Exception, so it slipped past that arm,
 * escaped the root coroutine to viewModelScope's uncaught-exception handler, and CrashHandler.install
 * turned it into markCrashed -> safe-mode. The pure [imgGenErrorMessage] now decides the
 * rethrow-vs-report outcome at that catch site: cancellation returns null so the caller rethrows it
 * (cancelling generation resets state without surfacing an error), every other throwable — Exception
 * OR Error — maps to a non-null message that is reported to local `_error`.
 *
 * This test FAILS on the pre-fix `catch (e: Exception)` seam: under that seam an OutOfMemoryError could
 * not be mapped to a reported message at all (it was never caught), so the contract asserted here —
 * a non-Exception Throwable yields a non-null message — did not hold. It PASSES after the fix.
 */
class ImgGenErrorMessageTest {

    private class JobCancelled : CancellationException("job cancelled")

    @Test
    fun `cancellation returns null so the caller rethrows instead of reporting`() {
        assertNull(imgGenErrorMessage(CancellationException()))
        // Subclass (e.g. a job/timeout cancellation) must also be rethrown, not reported.
        assertNull(imgGenErrorMessage(JobCancelled()))
    }

    @Test
    fun `non-exception throwables map to their message and are reported`() {
        assertEquals("oom", imgGenErrorMessage(OutOfMemoryError("oom")))
        assertEquals("stack", imgGenErrorMessage(StackOverflowError("stack")))
    }

    @Test
    fun `recoverable exceptions map to their message`() {
        assertEquals("network down", imgGenErrorMessage(IOException("network down")))
        assertEquals("boom", imgGenErrorMessage(RuntimeException("boom")))
    }

    @Test
    fun `messageless throwable falls back to a generic message`() {
        assertEquals("Unknown error occurred", imgGenErrorMessage(RuntimeException()))
    }
}
