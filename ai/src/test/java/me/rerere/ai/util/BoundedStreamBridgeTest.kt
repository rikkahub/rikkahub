package me.rerere.ai.util

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.take
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BoundedStreamBridgeTest {
    @Test
    fun `slow consumer receives all ordered chunks after DONE`() = runBlocking {
        val diagnostics = mutableListOf<ProviderStreamDiagnostics>()
        val bridge = BoundedStreamBridge<String>(capacity = 1_024, onTerminated = diagnostics::add)
        val expected = (0 until 1_000).map { index ->
            if (index % 2 == 0) "token-$index" else "tool-delta-$index"
        }
        val producer = async {
            expected.forEach { assertTrue(bridge.emit(it)) }
            bridge.complete()
        }

        val received = mutableListOf<String>()
        repeat(expected.size) {
            delay(1)
            received += bridge.receive()
        }
        producer.await()

        assertEquals(expected, received)
        try {
            bridge.receive()
            error("Expected DONE to close the queue")
        } catch (_: ClosedReceiveChannelException) {
            // Expected after the queue has drained.
        }
        assertEquals(ProviderStreamTerminationReason.DONE, diagnostics.single().terminationReason)
        assertTrue(diagnostics.single().peakQueueSize <= 1_024)
    }

    @Test
    fun `overflow cancels upstream and retains accepted partial chunks`() = runBlocking {
        val cancelled = AtomicInteger()
        val diagnostics = mutableListOf<ProviderStreamDiagnostics>()
        val bridge = BoundedStreamBridge<Int>(capacity = 2, onTerminated = diagnostics::add)
        bridge.attachUpstreamCanceller { cancelled.incrementAndGet() }

        assertTrue(bridge.emit(1))
        assertTrue(bridge.emit(2))
        assertFalse(bridge.emit(3))

        assertEquals(1, bridge.receive())
        assertEquals(2, bridge.receive())
        val error = try {
            bridge.receive()
            error("Expected backpressure failure")
        } catch (error: ProviderStreamException) {
            error
        }

        assertEquals(ProviderStreamErrorCode.STREAM_BACKPRESSURE_EXCEEDED, error.code)
        assertEquals(1, cancelled.get())
        assertEquals(ProviderStreamTerminationReason.BACKPRESSURE_EXCEEDED, diagnostics.single().terminationReason)
        assertEquals(2, diagnostics.single().peakQueueSize)
    }

    @Test
    fun `cancellation closes queue cancels upstream and rejects later events`() = runBlocking {
        val cancelled = AtomicInteger()
        val bridge = BoundedStreamBridge<Int>(capacity = 2)
        bridge.attachUpstreamCanceller { cancelled.incrementAndGet() }
        assertTrue(bridge.emit(1))

        bridge.cancel()

        assertFalse(bridge.emit(2))
        assertEquals(1, cancelled.get())
    }

    @Test
    fun `malformed stream failure is delivered after partial chunks`() = runBlocking {
        val bridge = BoundedStreamBridge<Int>(capacity = 2)
        assertTrue(bridge.emit(1))
        bridge.fail(
            ProviderStreamException(
                ProviderStreamErrorCode.STREAM_MALFORMED_EVENT,
                "Malformed provider event",
            ),
            ProviderStreamTerminationReason.MALFORMED_EVENT,
        )

        assertEquals(1, bridge.receive())
        val error = try {
            bridge.receive()
            error("Expected malformed event failure")
        } catch (error: ProviderStreamException) {
            error
        }
        assertEquals(ProviderStreamErrorCode.STREAM_MALFORMED_EVENT, error.code)
    }

    @Test
    fun `incomplete close preserves partial chunks and reports a structured error`() = runBlocking {
        val bridge = BoundedStreamBridge<Int>(capacity = 2)
        assertTrue(bridge.emit(1))
        bridge.failIncomplete()

        assertEquals(1, bridge.receive())
        val error = try {
            bridge.receive()
            error("Expected incomplete stream failure")
        } catch (error: ProviderStreamException) {
            error
        }
        assertEquals(ProviderStreamErrorCode.STREAM_INCOMPLETE, error.code)
    }

    @Test
    fun `flow cancellation cancels upstream`() = runBlocking {
        val cancelled = AtomicInteger()
        val stream = boundedStreamFlow<Int>(capacity = 1) { bridge ->
            bridge.attachUpstreamCanceller { cancelled.incrementAndGet() }
            bridge.emit(1)
        }

        withTimeout(1_000) {
            stream.take(1).collect { }
        }
        assertEquals(1, cancelled.get())
    }
}
