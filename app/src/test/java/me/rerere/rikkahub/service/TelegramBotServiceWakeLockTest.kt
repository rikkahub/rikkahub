package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [TelegramBotService.shouldHoldPollWakeLock].
 *
 * The long-poll loop used to acquire a partial wake lock unconditionally on every cycle,
 * which on-device profiling showed kept the lock held ~100% of the time - the app's entire
 * wake-lock budget. The gate now only holds the lock while the screen is off, where it is
 * genuinely needed to keep the poll alive through Doze. This pins that decision logic.
 */
class TelegramBotServiceWakeLockTest {

    @Test
    fun `screen on - interactive - does not hold the wake lock`() {
        assertFalse(
            "Interactive device keeps the CPU awake on its own; the lock is pure waste",
            TelegramBotService.shouldHoldPollWakeLock(isInteractive = true),
        )
    }

    @Test
    fun `screen off - not interactive - holds the wake lock`() {
        assertTrue(
            "Screen-off long-poll needs the CPU kept alive through Doze",
            TelegramBotService.shouldHoldPollWakeLock(isInteractive = false),
        )
    }

    @Test
    fun `no PowerManager - null - defaults to holding the wake lock`() {
        // Null means we could not read the interactive state. Defaulting to "hold" keeps
        // behaviour never weaker than the old unconditional acquire.
        assertTrue(
            "A missing PowerManager must not silently disable Doze survival",
            TelegramBotService.shouldHoldPollWakeLock(isInteractive = null),
        )
    }
}
