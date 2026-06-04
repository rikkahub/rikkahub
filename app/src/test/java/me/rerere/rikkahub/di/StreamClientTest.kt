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
 * 15s pingInterval so dead sockets fail fast instead of hanging for ~120s. The
 * readTimeout is the 600s (10-min) OpenAI-SDK-aligned value (see below), not 120s:
 * a healthy reasoning stream can go transport-idle for tens of seconds with no SSE
 * bytes, so a short read timeout would false-time-out a quiet stream.
 *
 * Caveat (documented in buildStreamClient): pingInterval probes only HTTP/2
 * connections — it is a no-op on HTTP/1.1, which the shared client still negotiates
 * for plain-http/HTTP/1.1-only custom endpoints. On those, a mid-stream socket death
 * waits out the full 600s readTimeout. That is an accepted tradeoff vs. mis-killing
 * quiet reasoning streams; #63 root-cause #1 (stale pool reuse) is fixed
 * protocol-independently by the dedicated keepAlive=15s pool below.
 */
class StreamClientTest {

    @Test
    fun `stream client pings, owns its pool, and keeps the 600s read timeout`() {
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

        // Read timeout is 600s (10 min): a reasoning model can go transport-idle
        // with no SSE bytes for tens of seconds, so a short read timeout would
        // false-time-out a healthy-but-quiet stream. On HTTP/2 the 15s pings above
        // still fail a genuinely dead socket in ~30s; on HTTP/1.1 pings are a no-op
        // and a dead socket waits out this 600s cap (accepted tradeoff — see KDoc).
        assertEquals(600_000, stream.readTimeoutMillis)

        // callTimeout must remain unlimited so a full generation is not killed by
        // an overall wall-clock cap.
        assertEquals(0, stream.callTimeoutMillis)
    }
}
