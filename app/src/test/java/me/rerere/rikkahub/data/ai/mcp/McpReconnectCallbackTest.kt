package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Regression test for issue #28.
 *
 * The reconnect/close invariant: every transport McpManager connects must carry
 * onClose/onError handlers that drive scheduleReconnect, so a dropped SSE/HTTP
 * stream is rescheduled and the connection is never orphaned. The invariant has to
 * hold at ALL connect sites, but it was registered only inside addClient and
 * reconnectClient. The two lazy connect sites — callTool (`if (client.transport ==
 * null) client.connect(getTransport(config))`) and sync (`client.connect(
 * getTransport(config))`) — went straight through getTransport, which returned a
 * BARE transport with no callbacks. A stream opened on those paths therefore had no
 * onClose handler: when it dropped it was never scheduled for reconnect, and when the
 * clients map entry was later swapped/removed the live connection leaked.
 *
 * The fix relocates the callback registration into the single getTransport factory
 * (via attachReconnectCallbacks), the one place all four connect sites share, so a
 * transport cannot be produced without the callbacks.
 *
 * This test pins the attachment behavior of attachReconnectCallbacks directly via the
 * onClose hook (the onError branch is identical code):
 *  - firing onClose while status == Connected schedules a reconnect;
 *  - firing it while NOT Connected does not (deliberate-shutdown gate preserved).
 *
 * FAIL-BEFORE rationale: before the fix the lazy path produced a transport whose
 * onClose/onError were unset, so firing them was a no-op and no reconnect was ever
 * scheduled. The pre-fix getTransport had no shared attach step for those callers,
 * which is exactly the leak this test guards against.
 */
class McpReconnectCallbackTest {

    private val config = McpServerConfig.SseTransportServer(
        id = Uuid.random(),
        commonOptions = McpCommonOptions(name = "test"),
        url = "http://localhost/sse",
    )

    /** Minimal AbstractTransport that lets the test fire the SDK close/error hooks. */
    private class FakeTransport : AbstractTransport() {
        override suspend fun start() {}
        override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {}
        override suspend fun close() {}

        fun fireClose() = invokeOnCloseCallback()
    }

    @Test
    fun onClose_whileConnected_schedulesReconnect() {
        var reconnects = 0
        val transport = FakeTransport()
        attachReconnectCallbacks(
            transport = transport,
            config = config,
            currentStatus = { McpStatus.Connected },
            scheduleReconnect = { reconnects++ },
        )

        transport.fireClose()

        assertEquals("dropped transport while Connected must schedule a reconnect", 1, reconnects)
    }

    @Test
    fun onClose_whileNotConnected_doesNotReconnect() {
        var reconnects = 0
        val transport = FakeTransport()
        attachReconnectCallbacks(
            transport = transport,
            config = config,
            currentStatus = { McpStatus.Connecting },
            scheduleReconnect = { reconnects++ },
        )

        transport.fireClose()

        assertEquals("deliberate shutdown (not Connected) must not reconnect", 0, reconnects)
    }
}
