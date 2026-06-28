package me.rerere.rikkahub.voiceagent.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.voiceagent.FakeVoiceAudioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class HermesWaitingToneControllerTest {
    @Test
    fun `waiting plays local cue after grace and repeats`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val delays = ManualDelays()
        val controller = HermesWaitingToneController(
            audio = audio,
            scope = this,
            graceDelayMs = 2_000L,
            repeatIntervalMs = 4_000L,
            delayFn = delays::delay,
        )

        controller.setWaiting(true)
        delays.awaitDelay(2_000L)
        delays.releaseNext()
        delays.awaitDelay(4_000L)
        delays.releaseNext()
        delays.awaitDelay(4_000L)

        assertEquals(emptyList<String>(), audio.playedPcm16)
        assertEquals(2, audio.playedLocalCuePcm16.size)
        assertTrue(audio.playedLocalCuePcm16.all { it.isNotBlank() })
        Base64.getDecoder().decode(audio.playedLocalCuePcm16.first())

        controller.stop()
    }

    @Test
    fun `stop before grace keeps cue silent`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val delays = ManualDelays()
        val controller = HermesWaitingToneController(
            audio = audio,
            scope = this,
            graceDelayMs = 2_000L,
            repeatIntervalMs = 4_000L,
            delayFn = delays::delay,
        )

        controller.setWaiting(true)
        delays.awaitDelay(2_000L)
        controller.stop()
        delays.releaseNext()

        assertEquals(emptyList<String>(), audio.playedLocalCuePcm16)
    }

    @Test
    fun `stop after grace delay but before playback keeps cue silent`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val beforePlay = ManualDelays()
        val controller = HermesWaitingToneController(
            audio = audio,
            scope = this,
            graceDelayMs = 2_000L,
            repeatIntervalMs = 4_000L,
            delayFn = beforePlay::delay,
            beforePlayFn = beforePlay::delayBeforePlay,
        )

        controller.setWaiting(true)
        beforePlay.awaitDelay(2_000L)
        beforePlay.releaseNext()
        beforePlay.awaitBeforePlay()

        controller.stop()
        beforePlay.releaseBeforePlay()

        assertEquals(emptyList<String>(), audio.playedLocalCuePcm16)
    }

    @Test
    fun `set waiting true twice starts only one repeat loop`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val delays = ManualDelays()
        val controller = HermesWaitingToneController(
            audio = audio,
            scope = this,
            graceDelayMs = 2_000L,
            repeatIntervalMs = 4_000L,
            delayFn = delays::delay,
        )

        controller.setWaiting(true)
        controller.setWaiting(true)
        delays.awaitDelay(2_000L)
        delays.releaseNext()
        delays.awaitDelay(4_000L)

        assertEquals(1, audio.playedLocalCuePcm16.size)

        controller.stop()
    }

    @Test
    fun `local cue playback failure records diagnostic and loop continues`() = runTest {
        val diagnostics = mutableListOf<Pair<String, String>>()
        val audio = FakeVoiceAudioEngine().apply {
            failLocalCuePlayback = true
        }
        val delays = ManualDelays()
        val controller = HermesWaitingToneController(
            audio = audio,
            scope = this,
            graceDelayMs = 2_000L,
            repeatIntervalMs = 4_000L,
            delayFn = delays::delay,
            recordDiagnostic = { name, detail -> diagnostics += name to detail },
        )

        controller.setWaiting(true)
        delays.awaitDelay(2_000L)
        delays.releaseNext()
        delays.awaitDelay(4_000L)
        delays.releaseNext()
        delays.awaitDelay(4_000L)

        assertTrue(diagnostics.all { it.second.contains("playback rejected") })

        controller.stop()
    }

    @Test
    fun `throwing diagnostic during local cue failure does not stop repeat loop`() = runTest {
        val diagnosticAttempts = AtomicInteger()
        val audio = FakeVoiceAudioEngine().apply {
            failLocalCuePlayback = true
        }
        val delays = ManualDelays()
        val controller = HermesWaitingToneController(
            audio = audio,
            scope = this,
            graceDelayMs = 2_000L,
            repeatIntervalMs = 4_000L,
            delayFn = delays::delay,
            recordDiagnostic = { _, _ ->
                diagnosticAttempts.incrementAndGet()
                throw IllegalStateException("diagnostic sink failed")
            },
        )

        controller.setWaiting(true)
        delays.awaitDelay(2_000L)
        delays.releaseNext()
        delays.awaitDelay(4_000L)
        delays.releaseNext()
        delays.awaitDelay(4_000L)

        assertTrue(audio.localCuePlaybackAttempts >= 2)

        assertTrue(diagnosticAttempts.get() >= 2)

        controller.stop()
    }

    @Test
    fun `throwing local cue playback records exception detail and loop continues`() = runTest {
        val diagnostics = mutableListOf<Pair<String, String>>()
        val audio = FakeVoiceAudioEngine().apply {
            localCuePlaybackError = IllegalStateException("cue sink exploded")
        }
        val delays = ManualDelays()
        val controller = HermesWaitingToneController(
            audio = audio,
            scope = this,
            graceDelayMs = 2_000L,
            repeatIntervalMs = 4_000L,
            delayFn = delays::delay,
            recordDiagnostic = { name, detail -> diagnostics += name to detail },
        )

        controller.setWaiting(true)
        delays.awaitDelay(2_000L)
        delays.releaseNext()
        delays.awaitDelay(4_000L)
        delays.releaseNext()
        delays.awaitDelay(4_000L)

        assertEquals(2, audio.localCuePlaybackAttempts)
        assertTrue(diagnostics.all { it.first == "hermes_waiting_tone_failed" })
        assertTrue(diagnostics.all { it.second == "cue sink exploded" })

        controller.stop()
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    private class ManualDelays {
        private val delays = ConcurrentLinkedQueue<DelayRequest>()
        private val beforePlay = DelayRequest(0L)

        suspend fun delay(ms: Long) {
            val request = DelayRequest(ms)
            delays += request
            request.awaitRelease()
        }

        suspend fun delayBeforePlay() {
            beforePlay.started.incrementAndGet()
            beforePlay.awaitRelease()
        }

        suspend fun awaitDelay(ms: Long) {
            withTimeout(500) {
                while (delays.peek()?.ms != ms) {
                    kotlinx.coroutines.delay(1)
                }
            }
        }

        suspend fun awaitBeforePlay() {
            withTimeout(500) {
                while (beforePlay.started.get() == 0) {
                    kotlinx.coroutines.delay(1)
                }
            }
        }

        fun releaseNext() {
            delays.remove().release()
        }

        fun releaseBeforePlay() {
            beforePlay.release()
        }
    }

    private class DelayRequest(val ms: Long) {
        val started = AtomicInteger()
        private val released = AtomicInteger()

        suspend fun awaitRelease() {
            started.incrementAndGet()
            withTimeout(500) {
                while (released.get() == 0) {
                    kotlinx.coroutines.delay(1)
                }
            }
        }

        fun release() {
            released.incrementAndGet()
        }
    }
}
