package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesAnnouncementSchedulerTest {

    private class VirtualClock {
        var nowMs = 0L
        val delays = mutableListOf<Long>()
        var onDelay: (() -> Unit)? = null
        suspend fun delayFn(ms: Long) {
            onDelay?.invoke()
            delays += ms
            nowMs += ms
        }
    }

    private fun scheduler(clock: VirtualClock) = HermesAnnouncementScheduler(
        nowMs = { clock.nowMs },
        delayFn = clock::delayFn,
    )

    @Test
    fun `releases immediately when bridge available and conversation quiet`() = runTest {
        val clock = VirtualClock()
        val scheduler = scheduler(clock)
        scheduler.onBridgeAvailable(true)

        val result = scheduler.withAnnouncementSlot("first") { "sent" }

        assertEquals("sent", result)
        assertTrue(clock.delays.isEmpty())
    }

    @Test
    fun `holds while assistant audio is active`() = runTest {
        val clock = VirtualClock()
        val scheduler = scheduler(clock)
        scheduler.onBridgeAvailable(true)
        scheduler.onAssistantAudioActive(true)

        val slot = async { scheduler.withAnnouncementSlot("held") { clock.nowMs } }
        // Virtual delayFn advances the clock; the slot must not release before audio stops.
        val sentAt = slot.await()!!

        assertEquals(HermesAnnouncementScheduler.DEFAULT_MAX_HOLD_MS, sentAt)
    }

    @Test
    fun `holds during input transcript quiet window then releases`() = runTest {
        val clock = VirtualClock()
        val scheduler = scheduler(clock)
        scheduler.onBridgeAvailable(true)
        scheduler.onInputTranscriptDelta()

        val sentAt = scheduler.withAnnouncementSlot("quiet") { clock.nowMs }!!

        assertEquals(HermesAnnouncementScheduler.DEFAULT_QUIET_WINDOW_MS, sentAt)
    }

    @Test
    fun `max hold deadline releases even when never quiet`() = runTest {
        val clock = VirtualClock()
        val scheduler = scheduler(clock)
        scheduler.onBridgeAvailable(true)
        scheduler.onAssistantAudioActive(true)

        val sentAt = scheduler.withAnnouncementSlot("deadline") { clock.nowMs }!!

        assertEquals(HermesAnnouncementScheduler.DEFAULT_MAX_HOLD_MS, sentAt)
    }

    @Test
    fun `deadline with bridge unavailable returns null without sending`() = runTest {
        val clock = VirtualClock()
        val scheduler = scheduler(clock)
        scheduler.onBridgeAvailable(false)

        var sent = false
        val result = scheduler.withAnnouncementSlot("no-bridge") { sent = true }

        assertNull(result)
        assertFalse(sent)
        assertEquals(HermesAnnouncementScheduler.DEFAULT_MAX_HOLD_MS, clock.nowMs)
    }

    @Test
    fun `send failure is recorded and rethrown and scheduler stays usable`() = runTest {
        val clock = VirtualClock()
        val diagnostics = mutableListOf<String>()
        val scheduler = HermesAnnouncementScheduler(
            nowMs = { clock.nowMs },
            delayFn = clock::delayFn,
            recordDiagnostic = { name, _ -> diagnostics += name },
        )
        scheduler.onBridgeAvailable(true)

        val error = runCatching {
            scheduler.withAnnouncementSlot("boom") { throw IllegalStateException("ws down") }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(diagnostics.contains("hermes_announcement_send_failed"))
        assertEquals("ok", scheduler.withAnnouncementSlot("after") { "ok" })
    }

    @Test
    fun `second announcement waits for generation complete`() = runTest {
        val clock = VirtualClock()
        val scheduler = scheduler(clock)
        scheduler.onBridgeAvailable(true)
        scheduler.withAnnouncementSlot("first") { }

        var ticks = 0
        clock.onDelay = { if (++ticks == 3) scheduler.onGenerationComplete() }
        val sentAt = scheduler.withAnnouncementSlot("second") { clock.nowMs }!!

        assertEquals(3 * HermesAnnouncementScheduler.DEFAULT_POLL_TICK_MS, sentAt)
    }

    @Test
    fun `closed scheduler returns null without sending`() = runTest {
        val clock = VirtualClock()
        val scheduler = scheduler(clock)
        scheduler.onBridgeAvailable(true)
        scheduler.close()

        var sent = false
        val result = scheduler.withAnnouncementSlot("closed") { sent = true }

        assertNull(result)
        assertFalse(sent)
    }
}
