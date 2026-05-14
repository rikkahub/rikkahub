package me.rerere.rikkahub.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 24 — JVM unit tests for [TelegramPollStallTracker]. Pure time/counter logic, no
 * Android, no coroutines.
 */
class TelegramPollStallTrackerTest {

    @Test
    fun `a fresh tracker is not stalled`() {
        val tracker = TelegramPollStallTracker()
        assertFalse(tracker.isStalled())
        assertTrue("freshly created — millisSinceLastUpdate should be tiny", tracker.millisSinceLastUpdate() < 5_000L)
    }

    @Test
    fun `isStalled is true once the threshold is exceeded`() {
        val tracker = TelegramPollStallTracker()
        // A tiny threshold plus a short sleep crosses it deterministically.
        Thread.sleep(30L)
        assertTrue("30ms of silence exceeds a 10ms threshold", tracker.isStalled(thresholdMs = 10L))
    }

    @Test
    fun `markUpdate resets the stall clock`() {
        val tracker = TelegramPollStallTracker()
        Thread.sleep(30L)
        assertTrue(tracker.isStalled(thresholdMs = 10L))
        tracker.markUpdate()
        assertFalse("markUpdate should reset the silence window", tracker.isStalled(thresholdMs = 10L))
    }

    @Test
    fun `default threshold is 90 seconds`() {
        assertEquals(90_000L, TelegramPollStallTracker.DEFAULT_STALL_THRESHOLD_MS)
    }

    @Test
    fun `restartCount starts at zero and increments on onPollLoopRestarted`() {
        val tracker = TelegramPollStallTracker()
        assertEquals(0, tracker.restartCount)
        tracker.onPollLoopRestarted()
        tracker.onPollLoopRestarted()
        assertEquals(2, tracker.restartCount)
    }
}
