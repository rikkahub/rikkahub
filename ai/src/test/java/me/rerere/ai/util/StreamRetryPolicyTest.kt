package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class StreamRetryPolicyTest {

    @Test
    fun `IOException is retryable regardless of http code`() {
        assertTrue(isRetryableStreamFailure(IOException("boom"), null))
    }

    @Test
    fun `SocketTimeoutException is retryable`() {
        assertTrue(isRetryableStreamFailure(SocketTimeoutException("timeout"), null))
    }

    @Test
    fun `generic IOException subclass stands in for StreamResetException`() {
        // StreamResetException (okhttp) extends IOException; a plain IOException subclass
        // is a JVM-pure stand-in so the test needs no okhttp on the classpath.
        class FakeStreamReset(message: String) : IOException(message)
        assertTrue(isRetryableStreamFailure(FakeStreamReset("stream reset"), null))
    }

    @Test
    fun `transient http codes are retryable when no throwable`() {
        assertTrue(isRetryableStreamFailure(null, 408))
        assertTrue(isRetryableStreamFailure(null, 409))
        assertTrue(isRetryableStreamFailure(null, 429))
        assertTrue(isRetryableStreamFailure(null, 500))
        assertTrue(isRetryableStreamFailure(null, 502))
        assertTrue(isRetryableStreamFailure(null, 503))
        assertTrue(isRetryableStreamFailure(null, 504))
        assertTrue(isRetryableStreamFailure(null, 529))
    }

    @Test
    fun `client errors are not retryable`() {
        assertFalse(isRetryableStreamFailure(null, 400))
        assertFalse(isRetryableStreamFailure(null, 401))
        assertFalse(isRetryableStreamFailure(null, 404))
    }

    @Test
    fun `null throwable and null code is not retryable`() {
        assertFalse(isRetryableStreamFailure(null, null))
    }

    @Test
    fun `non IOException throwable is not retryable - guards against blanket catch`() {
        assertFalse(isRetryableStreamFailure(IllegalStateException("not transient"), null))
    }

    @Test
    fun `backoff base doubles per attempt`() {
        assertEquals(500L, retryBackoffMillis(0, null))
        assertEquals(1000L, retryBackoffMillis(1, null))
        assertEquals(2000L, retryBackoffMillis(2, null))
    }

    @Test
    fun `backoff base is clamped at 8 seconds`() {
        assertEquals(8000L, retryBackoffMillis(4, null))
        assertEquals(8000L, retryBackoffMillis(10, null))
    }

    @Test
    fun `retry after within bound is honored as base`() {
        assertEquals(3000L, retryBackoffMillis(0, 3000L))
    }

    @Test
    fun `retry after over 60 seconds is ignored`() {
        assertEquals(500L, retryBackoffMillis(0, 70_000L))
    }

    @Test
    fun `retry after zero or negative falls back to computed backoff`() {
        assertEquals(500L, retryBackoffMillis(0, 0L))
        assertEquals(500L, retryBackoffMillis(0, -5L))
    }

    @Test
    fun `retry after ms header is parsed in milliseconds`() {
        assertEquals(3000L, retryAfterMillisFromHeaders("3000", null))
    }

    @Test
    fun `retry after header is parsed in seconds`() {
        assertEquals(3000L, retryAfterMillisFromHeaders(null, "3"))
    }

    @Test
    fun `retry after ms header takes precedence over retry after`() {
        assertEquals(1500L, retryAfterMillisFromHeaders("1500", "3"))
    }

    @Test
    fun `absent retry after headers yield null`() {
        assertNull(retryAfterMillisFromHeaders(null, null))
    }

    @Test
    fun `non positive or unparseable retry after yields null`() {
        assertNull(retryAfterMillisFromHeaders("0", null))
        assertNull(retryAfterMillisFromHeaders("-1", null))
        assertNull(retryAfterMillisFromHeaders("not-a-number", null))
        assertNull(retryAfterMillisFromHeaders(null, "0"))
    }
}
