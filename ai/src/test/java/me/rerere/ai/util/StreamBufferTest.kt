package me.rerere.ai.util

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [bufferStreamChunks].
 *
 * Root cause it guards: SSE providers emit chunks from a non-suspending OkHttp callback
 * via `trySend`. A bare `callbackFlow` is backed by a 64-capacity BUFFERED channel, so
 * when the producer outruns the collector (large tool call → hundreds of `input_json_delta`
 * frames) `trySend` fails and chunks are SILENTLY DROPPED — the reassembled tool-call JSON
 * loses characters mid-content and fails to parse. [bufferStreamChunks] fuses an unbounded
 * buffer onto the upstream channel so `trySend` never fails.
 */
class StreamBufferTest {

    /** Producer sends synchronously to completion before the collector drains — forces overflow. */
    private fun overflowingFlow(n: Int) = callbackFlow {
        repeat(n) { trySend(it) }
        close()
        awaitClose { }
    }

    @Test
    fun `bufferStreamChunks delivers every chunk when the producer outruns the collector`() =
        runBlocking {
            val n = 1000
            val received = overflowingFlow(n).bufferStreamChunks().toList()

            assertEquals(n, received.size)
            assertEquals((0 until n).toList(), received)
        }

    @Test
    fun `a bounded callbackFlow drops chunks under the same backpressure (documents the bug)`() =
        runBlocking {
            val n = 1000
            val capacity = 16
            // Pin the fused channel capacity explicitly (instead of relying on the
            // BUFFERED default, which is tunable via the kotlinx defaultBuffer system
            // property) so this stays deterministic across environments. A bounded
            // channel + non-suspending trySend silently drops the overflow — exactly
            // the corruption bufferStreamChunks fixes by switching to UNLIMITED.
            val received = overflowingFlow(n).buffer(capacity).toList()

            assertTrue(
                "bounded channel must drop under synchronous overflow (got ${received.size})",
                received.size < n
            )
        }
}
