package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic test for [shouldRenewWakeLock], the throttle predicate that decides whether a streaming
 * chunk should re-arm the foreground service's WakeLock timeout. Keeping the decision pure lets it run
 * on the JVM without any Android service / PowerManager dependency.
 *
 * The invariant this guards: a long agentic job (tool/MCP/search loop running well past the 15-min
 * WakeLock timeout across many sub-120s SSE streams) must keep renewing the lock so the CPU never
 * sleeps mid-job — while NOT issuing an IPC per token.
 */
class WakeLockRenewTest {

    private val interval = WAKE_LOCK_RENEW_INTERVAL_MS

    @Test
    fun `first chunk renews immediately (lastRenewAt = 0)`() {
        // lastRenewAt == 0 means "never renewed". In production `now` is System.currentTimeMillis()
        // (epoch millis, far larger than the interval), so the very first streaming chunk renews — the
        // timeout clock for a long job starts ticking from the first frame, not from service start.
        val epochNow = System.currentTimeMillis()
        assertTrue(shouldRenewWakeLock(lastRenewAt = 0L, now = epochNow))
    }

    @Test
    fun `within interval does not renew (avoids per-token IPC)`() {
        val last = 10_000L
        assertFalse(shouldRenewWakeLock(lastRenewAt = last, now = last + 1))
        assertFalse(shouldRenewWakeLock(lastRenewAt = last, now = last + interval - 1))
    }

    @Test
    fun `at or past interval renews`() {
        val last = 10_000L
        assertTrue(shouldRenewWakeLock(lastRenewAt = last, now = last + interval))
        assertTrue(shouldRenewWakeLock(lastRenewAt = last, now = last + interval + 1))
    }

    @Test
    fun `renew interval is well below the wake lock timeout`() {
        // Renewal must fire several times before the 15-min safety timeout, or a live job would still
        // stall. Encodes the relationship the two constants must keep: interval << timeout.
        val wakeLockTimeoutMs = 15L * 60L * 1000L
        assertTrue(interval < wakeLockTimeoutMs / 2)
    }

    @Test
    fun `a long job renews repeatedly across the timeout window`() {
        // Simulate a 30-min agentic loop emitting a chunk every 5s; assert the lock is re-armed many
        // times (so it never reaches the 15-min timeout) without renewing on every chunk.
        val chunkEvery = 5_000L
        val totalMs = 30L * 60L * 1000L
        var last = 0L
        var renews = 0
        var chunks = 0
        var now = 0L
        while (now <= totalMs) {
            chunks++
            if (shouldRenewWakeLock(last, now)) {
                last = now
                renews++
            }
            now += chunkEvery
        }
        val maxGapBetweenRenews = interval + chunkEvery
        assertTrue("renews at least once per interval", renews >= totalMs / (interval + chunkEvery))
        assertTrue("renew gap stays under the 15-min timeout", maxGapBetweenRenews < 15L * 60L * 1000L)
        assertTrue("throttled: far fewer renews than chunks", renews < chunks)
    }
}
