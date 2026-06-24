package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the #108 streaming-UI coalescing. The whole UI-publish lifecycle — per-chunk
 * window throttle AND the mandatory terminal flush on every termination path — lives in
 * [StreamingUiCoalescer.coalesce], the exact same Flow-decorating seam
 * [ChatService.handleMessageComplete] uses. The test drives that production operator with a fake chunk
 * Flow and an injected clock + recording publish lambda, so it exercises the production wiring, NOT a
 * hand-rolled copy of the collect/onCompletion loop.
 *
 * The invariant this guards (issue #108): for a fast SSE token stream, the whole-conversation UI
 * StateFlow must NOT be rewritten on every chunk (each rewrite triggers full recomposition + derived
 * work), yet the final merged state must NEVER be dropped. Because the terminal-flush CALL lives inside
 * [StreamingUiCoalescer.coalesce] (via its `onCompletion { finish() }`), deleting that call — the #108
 * regression — drops the final value and reddens [final value is always flushed...]. Likewise removing
 * the throttle gate inside [StreamingUiCoalescer.onChunk] reddens [coalesces a fast burst...]. The
 * finish-before-finalize ordering production relies on is guarded by [terminal flush runs upstream...].
 */
class StreamingUiCoalesceTest {

    private val interval = STREAMING_UI_COALESCE_INTERVAL_MS

    /**
     * Drives the PRODUCTION [StreamingUiCoalescer.coalesce] operator over a fake chunk stream and
     * records every publish. [times] is the per-chunk arrival clock (epoch-style millis); [values] is
     * the merged value the chunk carries (an Int standing in for the merged conversation). The clock is
     * advanced deterministically per emitted chunk via the injected `now` lambda.
     */
    private fun publishesViaCoalescer(
        times: List<Long>,
        values: List<Int>,
        forced: Set<Int> = emptySet(),
    ): List<Int> = runBlocking {
        require(times.size == values.size)
        val published = mutableListOf<Int>()
        val coalescer = StreamingUiCoalescer<Int>(publish = { published.add(it) })
        var i = 0
        coalescer.coalesce(
            source = values.asFlow(),
            now = { times[i++] },
            force = { it in forced },
        ).collect { /* drain */ }
        published
    }

    /**
     * Drives only the per-chunk throttle gate ([StreamingUiCoalescer.onChunk]) WITHOUT the terminal
     * flush — i.e. the issue's named anti-pattern (naive `sample()`-style gate that can drop the tail).
     * Used to establish the precondition that the gate alone drops a final chunk landing in the window.
     */
    private fun publishesViaGateOnly(times: List<Long>, values: List<Int>): List<Int> {
        require(times.size == values.size)
        val published = mutableListOf<Int>()
        val coalescer = StreamingUiCoalescer<Int>(publish = { published.add(it) })
        times.forEachIndexed { i, now -> coalescer.onChunk(now, values[i]) }
        return published
    }

    @Test
    fun `first chunk publishes immediately (lastPublishAt = 0)`() {
        // lastPublishAt == 0 means "never published". In production `now` is System.currentTimeMillis()
        // (epoch millis, far larger than the window), so the first token shows with no startup latency.
        val epochNow = System.currentTimeMillis()
        assertTrue(shouldPublishStreamingUpdate(lastPublishAt = 0L, now = epochNow))
    }

    @Test
    fun `within window does not publish (drops intermediate UI writes)`() {
        val last = 1_000_000L
        assertFalse(shouldPublishStreamingUpdate(last, last + 1))
        assertFalse(shouldPublishStreamingUpdate(last, last + interval - 1))
    }

    @Test
    fun `at or past window publishes`() {
        val last = 1_000_000L
        assertTrue(shouldPublishStreamingUpdate(last, last + interval))
        assertTrue(shouldPublishStreamingUpdate(last, last + interval + 1))
    }

    @Test
    fun `coalesces a fast burst into far fewer UI publishes`() {
        // 50 chunks arriving 1 ms apart, all inside a few coalesce windows. The unfixed per-chunk
        // publish writes the StateFlow 50 times; the coalescer must publish far fewer.
        val base = 1_000_000L
        val n = 50
        val times = (0 until n).map { base + it.toLong() } // +1ms spacing
        val values = (0 until n).toList()
        val published = publishesViaCoalescer(times, values)
        assertTrue(
            "expected far fewer than $n publishes, got ${published.size}",
            published.size < n / 2
        )
    }

    @Test
    fun `final value is always flushed even when it lands inside the throttle window`() {
        // Burst whose last chunk lands well inside the window after the previous publish, so the gate
        // alone would not publish it. The throttle drops the tail, then collectCoalescing's
        // onCompletion { finish() } MUST publish the LAST merged value. Deleting that finish() call —
        // the #108 regression — drops the tail and reddens this assertion.
        val base = 1_000_000L
        val n = 50
        val times = (0 until n).map { base + it.toLong() }
        val values = (0 until n).toList()

        // The last chunk was throttled (its time is 1 ms after the previous), so without the terminal
        // flush it would NOT appear; the gate alone never publishes the final value here.
        val gateOnly = publishesViaGateOnly(times, values)
        assertFalse(
            "precondition: the gate alone must NOT publish the final value (it lands in the window)",
            gateOnly.lastOrNull() == values.last()
        )

        val published = publishesViaCoalescer(times, values)
        assertEquals("final merged value must be the last published", values.last(), published.last())
    }

    @Test
    fun `published values are monotonic - never stale`() {
        // Each merged value is strictly newer than the previous (here a monotonically increasing Int).
        // No publish may carry an older value than a previously published one.
        val base = 1_000_000L
        val times = (0 until 200).map { base + it.toLong() }
        val values = (0 until 200).toList()
        val published = publishesViaCoalescer(times, values)
        for (i in 1 until published.size) {
            assertTrue(
                "publish must never go backwards: ${published[i]} after ${published[i - 1]}",
                published[i] > published[i - 1]
            )
        }
    }

    @Test
    fun `slow stream publishes every chunk (no coalescing when spacing exceeds the window)`() {
        // When chunks are spaced wider than the window, every chunk publishes — coalescing only kicks
        // in for fast bursts, so a slow stream is unaffected and the flush adds no duplicate.
        val base = 1_000_000L
        val n = 10
        val times = (0 until n).map { base + it.toLong() * (interval + 5L) }
        val values = (0 until n).toList()
        val published = publishesViaCoalescer(times, values)
        assertEquals(values, published)
    }

    @Test
    fun `empty stream publishes nothing even with terminal flush`() {
        // No chunks received (immediate cancel / no tokens): collectCoalescing's terminal flush must
        // not publish anything (nothing was ever remembered).
        val published = runBlocking {
            val out = mutableListOf<Int>()
            StreamingUiCoalescer<Int>(publish = { out.add(it) })
                .coalesce(source = emptyFlow())
                .collect { /* drain */ }
            out
        }
        assertTrue("empty stream must publish nothing, got $published", published.isEmpty())
    }

    @Test
    fun `terminal flush runs upstream of a downstream onCompletion (finish before finalize)`() {
        // Production composes coalesce(source).onCompletion { persist-and-notify }.collect{} and relies
        // on the terminal flush firing BEFORE the downstream finalize reads the live state (#108: the
        // final-flushed state must be the one persisted). coalesce's onCompletion sits upstream, so its
        // finish() must run before any downstream onCompletion. Guard that ordering here.
        val order = mutableListOf<String>()
        runBlocking {
            val coalescer = StreamingUiCoalescer<Int>(publish = { order.add("publish-$it") })
            var i = 0
            val times = listOf(1_000_000L, 1_000_001L) // 2nd chunk throttled -> only finish() publishes it
            coalescer.coalesce(source = listOf(10, 20).asFlow(), now = { times[i++] })
                .onCompletion { order.add("downstream-finalize") }
                .collect { /* drain */ }
        }
        // finish() publishes the throttled tail (20) before the downstream finalize sees completion.
        assertEquals(listOf("publish-10", "publish-20", "downstream-finalize"), order)
    }

    // ---- force-publish: a semantic tool-boundary frame must survive the throttle window ----

    @Test
    fun `a forced semantic frame publishes even inside the throttle window`() {
        // Models the tool boundary: 10 = text-before-tool (published as the first chunk), 20 = the
        // IN-PROGRESS tool frame (tool assembled, output empty) landing 1 ms later — inside the window —
        // but FORCED, then 30 = the executed tool frame 1 ms after that. Without the force flag the
        // in-progress frame is held and overwritten by the executed frame before the terminal flush
        // (exactly the runtime's :254 → executeTool → :350 sequence). Force must make 20 reach the UI.
        // Removing the `force ||` gate in onChunk reddens this assertion.
        val base = 1_000_000L
        val times = listOf(base, base + 1, base + 2)
        val values = listOf(10, 20, 30)
        val published = publishesViaCoalescer(times, values, forced = setOf(20))
        assertTrue(
            "the forced in-progress frame (20) must be published, got $published",
            published.contains(20)
        )
    }

    @Test
    fun `without force the in-progress frame is coalesced away (the bug being fixed)`() {
        // The SAME sequence with NOTHING forced reproduces the pre-fix behavior: 20 lands in the window,
        // is held, and the next held frame (30) overwrites it before the terminal flush, so 20 never
        // publishes. Pins precisely what the force flag fixes.
        val base = 1_000_000L
        val times = listOf(base, base + 1, base + 2)
        val values = listOf(10, 20, 30)
        val published = publishesViaCoalescer(times, values, forced = emptySet())
        assertFalse(
            "without force, the in-progress frame (20) is coalesced away, got $published",
            published.contains(20)
        )
    }

    @Test
    fun `a forced frame resets the throttle window for subsequent chunks`() {
        // After a forced publish, lastPublishAt advances to that frame's time, so an immediately
        // following non-forced chunk inside the window is still throttled (force does not disable the
        // throttle for later frames — it only bypasses it for the frame that carries it).
        val base = 1_000_000L
        val times = listOf(base, base + 1, base + 2)
        val values = listOf(10, 20, 30)
        val published = publishesViaCoalescer(times, values, forced = setOf(20))
        // 30 lands 1 ms after the forced 20, inside the window → not published by the gate; it only
        // appears via the terminal flush, so it is the LAST published value exactly once.
        assertEquals("30 must arrive via the terminal flush as the final value", 30, published.last())
        assertEquals("30 must not be double-published", 1, published.count { it == 30 })
    }

    @Test
    fun `coalesce window sits in the issue's suggested 16-50 ms band`() {
        // The window must be small enough to feel real-time yet large enough to cut per-token writes.
        assertTrue(STREAMING_UI_COALESCE_INTERVAL_MS in 16L..50L)
    }

    // ---- Terminal flush decision: directly covers the #108 fix (onCompletion force-flush) ----

    @Test
    fun `flushes when the last chunk was throttled (not yet published)`() {
        // The bug to guard: a final chunk that landed inside the throttle window was remembered but
        // never written to the StateFlow. The terminal flush MUST fire so the final state is not lost.
        assertTrue(shouldFlushFinalStreamingUpdate(hasLastMessages = true, lastChunkPublished = false))
    }

    @Test
    fun `does not flush when the last chunk was already published`() {
        // Slow stream / wide spacing: the final chunk crossed the window and was already published.
        // Re-flushing would be a redundant duplicate StateFlow write — must not fire.
        assertFalse(shouldFlushFinalStreamingUpdate(hasLastMessages = true, lastChunkPublished = true))
    }

    @Test
    fun `does not flush when no chunk was ever received`() {
        // Empty stream (immediate cancel / no tokens): nothing remembered, nothing to flush.
        assertFalse(shouldFlushFinalStreamingUpdate(hasLastMessages = false, lastChunkPublished = false))
        assertFalse(shouldFlushFinalStreamingUpdate(hasLastMessages = false, lastChunkPublished = true))
    }
}
