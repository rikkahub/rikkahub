package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.telegram.TelegramPollStallTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 24 — JVM unit tests for [TelegramPollStallChecker].
 *
 * The pure [TelegramPollStallChecker.decide] function carries the load-bearing logic
 * (healthy / restart / escalate), so it is tested directly. A short integration test then
 * drives the [TelegramPollStallChecker.monitor] loop with a tiny interval to confirm a
 * stall actually triggers the restart callback.
 */
class TelegramPollStallCheckerTest {

    private fun checker(
        tracker: TelegramPollStallTracker,
        onRestart: () -> Unit = {},
        onEscalate: () -> Unit = {},
        intervalMs: Long = TelegramPollStallChecker.CHECK_INTERVAL_MS,
        thresholdMs: Long = TelegramPollStallTracker.DEFAULT_STALL_THRESHOLD_MS,
    ) = TelegramPollStallChecker(tracker, onRestart, onEscalate, intervalMs, thresholdMs)

    @Test
    fun `decide returns NONE when not stalled`() {
        val c = checker(TelegramPollStallTracker())
        assertEquals(TelegramPollStallChecker.Action.NONE, c.decide(stalled = false, restartCountInWindow = 0))
        assertEquals(
            "even a high restart count is irrelevant when healthy",
            TelegramPollStallChecker.Action.NONE,
            c.decide(stalled = false, restartCountInWindow = 99),
        )
    }

    @Test
    fun `decide restarts the poll loop when stalled under the flap ceiling`() {
        val c = checker(TelegramPollStallTracker())
        assertEquals(
            TelegramPollStallChecker.Action.RESTART_POLL_LOOP,
            c.decide(stalled = true, restartCountInWindow = 0),
        )
        assertEquals(
            TelegramPollStallChecker.Action.RESTART_POLL_LOOP,
            c.decide(stalled = true, restartCountInWindow = TelegramPollStallTracker.FLAP_RESTART_CEILING - 1),
        )
    }

    @Test
    fun `decide escalates when stalled and at or over the flap ceiling`() {
        val c = checker(TelegramPollStallTracker())
        assertEquals(
            TelegramPollStallChecker.Action.ESCALATE,
            c.decide(stalled = true, restartCountInWindow = TelegramPollStallTracker.FLAP_RESTART_CEILING),
        )
        assertEquals(
            TelegramPollStallChecker.Action.ESCALATE,
            c.decide(stalled = true, restartCountInWindow = TelegramPollStallTracker.FLAP_RESTART_CEILING + 10),
        )
    }

    @Test
    fun `monitor restarts the poll loop on a real stall`() = runBlocking {
        // Tracker is never markUpdate()'d after construction, so with a near-zero threshold
        // it reads as stalled on the very first tick.
        val tracker = TelegramPollStallTracker()
        var restarts = 0
        val c = checker(
            tracker = tracker,
            onRestart = { restarts++ },
            intervalMs = 20L,
            thresholdMs = 1L,
        )
        // Let a couple of ticks run, then bail — monitor() loops forever by design.
        withTimeoutOrNull(200L) { c.monitor() }
        assertTrue("a stalled tracker should have triggered at least one poll-loop restart", restarts >= 1)
        assertTrue("tracker.restartCount should track the restarts", tracker.restartCount >= 1)
    }

    @Test
    fun `monitor does nothing while the tracker stays fresh`() = runBlocking {
        val tracker = TelegramPollStallTracker()
        var restarts = 0
        var escalations = 0
        val c = checker(
            tracker = tracker,
            onRestart = { restarts++ },
            onEscalate = { escalations++ },
            intervalMs = 20L,
            thresholdMs = 10_000L,  // generous — the tracker is freshly stamped, never stalls
        )
        withTimeoutOrNull(150L) { c.monitor() }
        assertEquals(0, restarts)
        assertEquals(0, escalations)
    }
}
