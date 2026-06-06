package me.rerere.rikkahub.service.generation

import me.rerere.rikkahub.service.WAKE_LOCK_RENEW_INTERVAL_MS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic test for [GenerationForegroundCoordinator], the active-generation -> foreground-service
 * state machine extracted out of ChatService. A fake [GenerationForegroundController] records call
 * counts; the clock is injectable so the WakeLock throttle is deterministic. No Android/Robolectric.
 *
 * The invariants this pins (mirrors the design proposal): start only on the 0 -> 1 generation edge,
 * stop only on the 1 -> 0 edge, renew throttled by [WAKE_LOCK_RENEW_INTERVAL_MS] AND gated on the
 * foreground service actually running (start succeeded).
 */
class GenerationForegroundCoordinatorTest {

    private class FakeController(
        private val throwOnStart: Boolean = false,
    ) : GenerationForegroundController {
        var starts = 0
        var stops = 0
        var renews = 0
        override fun start() {
            starts++
            if (throwOnStart) throw IllegalStateException("FGS start not allowed")
        }
        override fun stop() { stops++ }
        override fun renew() { renews++ }
    }

    @Test
    fun `first generation start calls controller start exactly once`() {
        val controller = FakeController()
        val coordinator = GenerationForegroundCoordinator(controller, clock = { 0L })

        coordinator.onGenerationStart()

        assertEquals(1, controller.starts)
    }

    @Test
    fun `concurrent generations only start on the 0 to 1 edge`() {
        val controller = FakeController()
        val coordinator = GenerationForegroundCoordinator(controller, clock = { 0L })

        coordinator.onGenerationStart()
        coordinator.onGenerationStart()

        assertEquals(1, controller.starts)
    }

    @Test
    fun `stop only fires on the 1 to 0 edge`() {
        val controller = FakeController()
        val coordinator = GenerationForegroundCoordinator(controller, clock = { 0L })

        coordinator.onGenerationStart()
        coordinator.onGenerationStart()

        coordinator.onGenerationStop()
        assertEquals(0, controller.stops) // 2 -> 1, no edge

        coordinator.onGenerationStop()
        assertEquals(1, controller.stops) // 1 -> 0 edge
    }

    @Test
    fun `streaming progress does nothing when foreground is not running`() {
        val controller = FakeController()
        var now = 0L
        val coordinator = GenerationForegroundCoordinator(controller, clock = { now })

        now = 10_000_000L
        coordinator.onStreamingProgress()

        assertEquals(0, controller.renews)
    }

    @Test
    fun `streaming progress renews once after start then throttles within interval and renews after it`() {
        val controller = FakeController()
        var now = 0L
        val coordinator = GenerationForegroundCoordinator(controller, clock = { now })

        coordinator.onGenerationStart()

        // First progress after start: lastRenewAt == 0, renews immediately.
        now = 1_000_000L
        coordinator.onStreamingProgress()
        assertEquals(1, controller.renews)

        // Within the interval: throttled, no extra renew.
        now += WAKE_LOCK_RENEW_INTERVAL_MS - 1
        coordinator.onStreamingProgress()
        assertEquals(1, controller.renews)

        // At/after the interval relative to the last renew: renews again.
        now += 1 // now exactly WAKE_LOCK_RENEW_INTERVAL_MS past the last renew
        coordinator.onStreamingProgress()
        assertEquals(2, controller.renews)
    }

    @Test
    fun `failed start degrades and leaves foreground not running so no renew is issued`() {
        val controller = FakeController(throwOnStart = true)
        var now = 0L
        val coordinator = GenerationForegroundCoordinator(controller, clock = { now })

        coordinator.onGenerationStart() // start() throws, swallowed by runCatching
        assertEquals(1, controller.starts)

        now = 10_000_000L
        coordinator.onStreamingProgress()
        assertEquals(0, controller.renews) // foregroundServiceRunning never set -> no renew
    }
}
