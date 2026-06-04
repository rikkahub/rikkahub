package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.McpException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression test for the closed-but-non-null SSE transport bug.
 *
 * Liveness in callTool was checked by reference-nullness (`client.transport == null`).
 * But a transport can be non-null and already closed — the long-lived SSE keepalive GET
 * drops while the Client still holds the (now non-Operational) transport. The next send
 * then rejects the dead transport; the unfixed callTool had no catch for that and the
 * exception escaped, killing the tool call instead of self-healing.
 *
 * The exact exceptions the pinned kotlin-sdk 0.12.0 throws on this path were verified by
 * decompiling the jars (see isMcpTransportClosed for the full inventory):
 *   - SseClientTransport.performSend: IllegalStateException("Not connected!")        — dropped keepalive,
 *                                     IllegalStateException("SseClientTransport is closed!") — closed,
 *                                     IllegalStateException("...Error POSTing to endpoint (HTTP n)...").
 *   - Protocol.request:               McpException(-32000, "Connection closed")       — onClose mid-call.
 *   - a dead socket:                  IOException from OkHttp/ktor.
 * The strings "Transport is not ready" / "Error while sending message" do NOT exist in 0.12.0,
 * so the predicate must NOT be anchored to them; these tests use the real signals only.
 *
 * The fix (callToolWithHeal + isMcpTransportClosed) detects the closed signal, heals the
 * connection once via the existing reconnect machinery, re-resolves the replaced client,
 * and retries exactly once — while leaving genuine tool-execution errors untouched.
 *
 * FAIL-BEFORE rationale: property 1 reproduces the exact bug — the genuine dropped-keepalive
 * IllegalStateException("Not connected!") at call time. The unfixed callTool (null-guard only,
 * no heal/retry) lets it escape; a test asserting the healed success is returned fails before
 * the fix and passes after. Properties 2–4 pin the discipline constraints: clean error on
 * heal failure (no uncaught throw), no double-connect when a reconnect is already in
 * flight, and pass-through (rethrow) of non-transient/real tool errors.
 */
class McpCallToolHealTest {

    // The genuine SDK signal for the dropped-keepalive case (SseClientTransport.performSend).
    private val closedError = IllegalStateException("Not connected!")
    private val success = listOf<UIMessagePart>(UIMessagePart.Text("ok"))

    // ---- isMcpTransportClosed predicate (anchored to VERIFIED kotlin-sdk 0.12.0 signals) ----

    @Test
    fun isMcpTransportClosed_matchesSseNotConnected() {
        // SseClientTransport.performSend when the endpoint deferred is uncompleted (dropped keepalive).
        assertTrue(isMcpTransportClosed(IllegalStateException("Not connected!")))
    }

    @Test
    fun isMcpTransportClosed_matchesProtocolConnectionClosed() {
        // Protocol.request when onClose fires mid-call.
        assertTrue(isMcpTransportClosed(McpException(-32000, "Connection closed")))
    }

    @Test
    fun isMcpTransportClosed_matchesSseClosed() {
        // SseClientTransport.performSend when the job is null/inactive.
        assertTrue(isMcpTransportClosed(IllegalStateException("SseClientTransport is closed!")))
    }

    @Test
    fun isMcpTransportClosed_matchesPostToEndpointError() {
        // SseClientTransport.performSend on a failed POST.
        assertTrue(isMcpTransportClosed(IllegalStateException("Error POSTing to endpoint (HTTP 502): Bad Gateway")))
    }

    @Test
    fun isMcpTransportClosed_matchesDeadSocketIOException() {
        // OkHttp/ktor surfaces a dead underlying socket as an IOException.
        assertTrue(isMcpTransportClosed(IOException("unexpected end of stream")))
    }

    @Test
    fun isMcpTransportClosed_rejectsRequestTimeout() {
        // A slow tool (Protocol -32001) must NOT be treated as a dead transport.
        assertFalse(isMcpTransportClosed(McpException(-32001, "Request timed out")))
    }

    @Test
    fun isMcpTransportClosed_rejectsRealToolError() {
        assertFalse(isMcpTransportClosed(McpException(-32602, "tool 'foo' threw: bad arg")))
    }

    @Test
    fun isMcpTransportClosed_rejectsCancellation() {
        assertFalse(isMcpTransportClosed(CancellationException("scope cancelled")))
    }

    // ---- callToolWithHeal control flow ----

    /** Property 1 (INVARIANT): closed on first call -> heal -> retry success is returned, never the raw closed error. */
    @Test
    fun closedThenHealSucceeds_returnsRetryResult() = runBlocking {
        var healed = false
        var calls = 0
        val result = callToolWithHeal(
            initialCall = { calls++; throw closedError },
            isTransportClosed = ::isMcpTransportClosed,
            reconnectInFlight = { null },
            heal = { healed = true },
            retryCall = { success },
            onHealFailed = { fail("should not reach onHealFailed: ${it.message}"); emptyList() },
        )
        assertTrue("heal must run when transport is closed", healed)
        assertEquals("first call should run exactly once", 1, calls)
        assertEquals(success, result)
    }

    /** Property 2 (BOUNDARY): closed on first call, heal fails -> clean onHealFailed result, not an uncaught throw. */
    @Test
    fun closedThenHealFails_returnsCleanError() = runBlocking {
        val result = callToolWithHeal(
            initialCall = { throw closedError },
            isTransportClosed = ::isMcpTransportClosed,
            reconnectInFlight = { null },
            heal = { throw RuntimeException("max reconnect attempts reached") },
            retryCall = { fail("retry must not run when heal fails"); emptyList() },
            onHealFailed = { listOf(UIMessagePart.Text("Failed to execute tool: ${it.message}")) },
        )
        assertEquals(1, result.size)
        val text = (result.single() as UIMessagePart.Text).text
        // The reported error is the ORIGINAL closed error, not the heal failure.
        assertTrue("clean error must surface the original closed cause", text.contains("Not connected!"))
    }

    /** Property 3 (NO-DOUBLE-CONNECT): an in-flight reconnect job is join()ed; synchronous heal is NOT invoked. */
    @Test
    fun closedWithReconnectInFlight_joinsInsteadOfHealing() = runBlocking {
        var healInvocations = 0
        val gate = CompletableDeferred<Unit>()
        val inFlight: Job = launch { gate.await() }

        var retried = false
        val deferred = async {
            callToolWithHeal(
                initialCall = { throw closedError },
                isTransportClosed = ::isMcpTransportClosed,
                reconnectInFlight = { inFlight },
                heal = { healInvocations++ },
                retryCall = { retried = true; success },
                onHealFailed = { fail("should not reach onHealFailed"); emptyList() },
            )
        }

        // Until the in-flight reconnect completes, retry must not have run (we joined).
        assertFalse("retry must wait for the in-flight reconnect to finish", retried)
        gate.complete(Unit)
        val result = deferred.await()

        assertEquals("synchronous heal must NOT run when a reconnect is already in flight", 0, healInvocations)
        assertTrue("retry runs after joining the in-flight reconnect", retried)
        assertEquals(success, result)
    }

    /** Property 4 (NON-TRANSIENT PASS-THROUGH): a non-closed (real tool) error is rethrown, no heal attempted. */
    @Test
    fun nonClosedError_isRethrownWithoutHealing() = runBlocking {
        var healed = false
        val realError = McpException(-32602, "tool 'foo' threw: bad arg")
        try {
            callToolWithHeal(
                initialCall = { throw realError },
                isTransportClosed = ::isMcpTransportClosed,
                reconnectInFlight = { fail("must not inspect reconnect state for a real error"); null },
                heal = { healed = true },
                retryCall = { fail("must not retry a real tool error"); emptyList() },
                onHealFailed = { fail("must not reach onHealFailed for a real error"); emptyList() },
            )
            fail("a non-closed error must be rethrown")
        } catch (e: McpException) {
            assertEquals(realError, e)
        }
        assertFalse("a real tool error must not trigger a heal", healed)
    }

    /** Cancellation is never swallowed: a CancellationException from initialCall propagates. */
    @Test
    fun cancellation_isRethrown() = runBlocking {
        try {
            callToolWithHeal(
                initialCall = { throw CancellationException("cancelled") },
                isTransportClosed = ::isMcpTransportClosed,
                reconnectInFlight = { fail("must not heal on cancellation"); null },
                heal = { fail("must not heal on cancellation") },
                retryCall = { fail("must not retry on cancellation"); emptyList() },
                onHealFailed = { fail("must not reach onHealFailed on cancellation"); emptyList() },
            )
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            // expected
        }
        Unit
    }
}
