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
        suspend fun delayFn(ms: Long) {
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
        scheduler.onBridgeAvailable(false)

        val sentAt = scheduler.withAnnouncementSlot("deadline") { clock.nowMs }!!

        assertEquals(HermesAnnouncementScheduler.DEFAULT_MAX_HOLD_MS, sentAt)
    }

    @Test
    fun `second announcement waits for generation complete`() = runTest {
        val clock = VirtualClock()
        val scheduler = scheduler(clock)
        scheduler.onBridgeAvailable(true)
        scheduler.withAnnouncementSlot("first") { }

        val second = async { scheduler.withAnnouncementSlot("second") { clock.nowMs } }
        scheduler.onGenerationComplete()
        val sentAt = second.await()!!

        assertTrue(sentAt < HermesAnnouncementScheduler.DEFAULT_MAX_HOLD_MS)
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
