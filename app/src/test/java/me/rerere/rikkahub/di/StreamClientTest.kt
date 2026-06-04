package me.rerere.rikkahub.di

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Regression test for the intermittent ~120s SSE stall.
 *
 * Root cause: the streaming OkHttpClient was cloned from the shared client with
 * `newBuilder()`, which inherits the shared [okhttp3.ConnectionPool] and carries no
 * HTTP/2 ping keepalive. A pooled connection that an intermediary (NAT/proxy/LB)
 * silently idle-killed could be reused for an SSE EventSource; with no pings to
 * detect the dead socket, the stall was only broken by the 120s readTimeout firing.
 *
 * The fix gives the stream client a dedicated short-lived connection pool and a
 * 15s pingInterval so dead sockets fail fast instead of hanging for ~120s.
 */
class StreamClientTest {

    @Test
    fun `stream client pings, owns its pool, and keeps the 120s read timeout`() {
        val base = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.MINUTES)
            .build()

        val stream = buildStreamClient(base)

        // HTTP/2 ping keepalive must be enabled so a dead socket is detected
        // within ~2 ping intervals instead of waiting out the read timeout.
        assertEquals(15_000, stream.pingIntervalMillis)

        // The stream client must not share the base client's connection pool;
        // reusing a stale pooled connection is what produced the intermittent stall.
        assertNotSame(base.connectionPool, stream.connectionPool)

        // Read timeout stays at 120s (transport-liveness watchdog).
        assertEquals(120_000, stream.readTimeoutMillis)

        // callTimeout must remain unlimited so a full generation is not killed by
        // an overall wall-clock cap.
        assertEquals(0, stream.callTimeoutMillis)
    }
}
