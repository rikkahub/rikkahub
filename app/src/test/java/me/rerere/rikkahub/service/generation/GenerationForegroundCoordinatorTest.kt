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
        throwOnStart: Boolean = false,
    ) : GenerationForegroundController {
        var starts = 0
        var stops = 0
        var renews = 0
        // Mutable so a test can fail the first start then let a retry succeed.
        var failStart = throwOnStart
        override fun start() {
            starts++
            if (failStart) throw IllegalStateException("FGS start not allowed")
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

    // ---- C22: a transient start failure must not suppress later start attempts ----

    @Test
    fun `a failed start does not suppress the next generation's start attempt while active`() {
        val controller = FakeController(throwOnStart = true)
        val coordinator = GenerationForegroundCoordinator(controller, clock = { 0L })

        coordinator.onGenerationStart() // 0 -> 1, start() throws, service still not running
        coordinator.onGenerationStart() // 1 -> 2: previously suppressed; must now re-attempt start

        assertEquals(2, controller.starts)
    }

    @Test
    fun `a retry that succeeds restores renew and the final stop`() {
        val controller = FakeController(throwOnStart = true)
        var now = 0L
        val coordinator = GenerationForegroundCoordinator(controller, clock = { now })

        coordinator.onGenerationStart() // fails, not running
        controller.failStart = false
        coordinator.onGenerationStart() // retry succeeds, now running
        assertEquals(2, controller.starts)

        now = 1_000_000L
        coordinator.onStreamingProgress()
        assertEquals(1, controller.renews) // running -> renew now works

        coordinator.onGenerationStop() // 2 -> 1, no edge
        assertEquals(0, controller.stops)
        coordinator.onGenerationStop() // 1 -> 0 edge, service was running -> stop
        assertEquals(1, controller.stops)
    }

    @Test
    fun `an all-failed active window never sends stop`() {
        val controller = FakeController(throwOnStart = true)
        val coordinator = GenerationForegroundCoordinator(controller, clock = { 0L })

        coordinator.onGenerationStart() // fails
        coordinator.onGenerationStart() // retry fails
        coordinator.onGenerationStop()
        coordinator.onGenerationStop() // 1 -> 0, but service never ran -> no stop

        assertEquals(0, controller.stops)
    }

    @Test
    fun `once running a later failing-configured start is suppressed and cannot clobber running`() {
        // After a successful start, a concurrent/later start that WOULD fail must be suppressed by the
        // running flag before it ever calls controller.start() — so it cannot run onFailure and clear
        // the running state established by the successful start. (Serialized via @Synchronized.)
        val controller = FakeController()
        var now = 0L
        val coordinator = GenerationForegroundCoordinator(controller, clock = { now })

        coordinator.onGenerationStart() // 0 -> 1 succeeds, running = true
        controller.failStart = true
        coordinator.onGenerationStart() // 1 -> 2: running already true -> suppressed, start() NOT called
        assertEquals(1, controller.starts)

        now = 1_000_000L
        coordinator.onStreamingProgress()
        assertEquals("running not clobbered -> renew still works", 1, controller.renews)

        coordinator.onGenerationStop()
        coordinator.onGenerationStop() // 1 -> 0 -> running was true -> stop fires once
        assertEquals(1, controller.stops)
    }

    @Test
    fun `a new active window retries start after a previous window failed`() {
        val controller = FakeController(throwOnStart = true)
        val coordinator = GenerationForegroundCoordinator(controller, clock = { 0L })

        coordinator.onGenerationStart() // 0 -> 1 fails
        coordinator.onGenerationStop()  // 1 -> 0, never ran
        assertEquals(1, controller.starts)

        controller.failStart = false
        coordinator.onGenerationStart() // new 0 -> 1 window, retries and succeeds
        assertEquals(2, controller.starts)
    }
}
