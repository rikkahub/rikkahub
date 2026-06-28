package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.voiceagent.FakeVoiceAudioEngine
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    fun `stop waits for in flight playback before returning`() = runTest {
        val audio = BlockingLocalCueAudioEngine()
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
        assertTrue(audio.awaitPlaybackEntered())

        val stopReturned = CountDownLatch(1)
        val stopThread = Thread {
            controller.stop()
            stopReturned.countDown()
        }
        stopThread.start()

        try {
            assertEquals(false, stopReturned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            audio.releasePlayback()
            assertTrue(stopReturned.await(2, TimeUnit.SECONDS))
            stopThread.join(2_000L)
        }
        val cueCountWhenStopReturned = audio.playedLocalCuePcm16.size
        kotlinx.coroutines.delay(50)

        assertEquals(cueCountWhenStopReturned, audio.playedLocalCuePcm16.size)
    }

    @Test
    fun `stop invalidates accepted local cue before queued writer processes it`() = runTest {
        val audio = QueuedLocalCueAudioEngine()
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
        audio.awaitQueuedCue()

        controller.stop()
        audio.processQueuedCues()

        assertEquals(emptyList<String>(), audio.playedLocalCuePcm16.toList())
    }

    @Test
    fun `quick stop start does not let old loop play inside new grace period`() = runTest {
        val audio = QueuedLocalCueAudioEngine()
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
        audio.awaitQueuedCue()

        controller.stop()
        controller.setWaiting(true)
        audio.processQueuedCues()
        kotlinx.coroutines.delay(50)

        assertEquals(emptyList<String>(), audio.playedLocalCuePcm16.toList())

        controller.stop()
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

        assertEquals(2, diagnostics.size)
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
        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics.all { it.first == "hermes_waiting_tone_failed" })
        assertTrue(diagnostics.all { it.second == "cue sink exploded" })

        controller.stop()
    }

    @Test
    fun `throwing local cue invalidation records diagnostic and stop returns`() = runTest {
        val diagnostics = mutableListOf<Pair<String, String>>()
        val audio = FakeVoiceAudioEngine().apply {
            localCueInvalidationError = IllegalStateException("invalidation failed")
        }
        val controller = HermesWaitingToneController(
            audio = audio,
            scope = this,
            graceDelayMs = 2_000L,
            repeatIntervalMs = 4_000L,
            recordDiagnostic = { name, detail -> diagnostics += name to detail },
        )

        controller.setWaiting(true)

        withTimeout(500) {
            controller.stop()
        }

        assertEquals(listOf("hermes_waiting_tone_failed" to "invalidation failed"), diagnostics)
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    private class ManualDelays {
        private val delays = ConcurrentLinkedQueue<DelayRequest>()

        suspend fun delay(ms: Long) {
            val request = DelayRequest(ms)
            delays += request
            request.awaitRelease()
        }

        suspend fun awaitDelay(ms: Long) {
            withTimeout(500) {
                while (delays.peek()?.ms != ms) {
                    kotlinx.coroutines.delay(1)
                }
            }
        }

        fun releaseNext() {
            delays.remove().release()
        }
    }

    private class BlockingLocalCueAudioEngine : VoiceAudioEngine {
        val playedLocalCuePcm16 = ConcurrentLinkedQueue<String>()
        private val playbackEntered = CountDownLatch(1)
        private val releasePlayback = CountDownLatch(1)
        private val released = AtomicBoolean(false)

        override fun setErrorHandler(onError: ((String) -> Unit)?) = Unit

        override fun startCapture(onPcm16: (ByteArray) -> Unit, onDebugInjectionComplete: () -> Unit) = Unit

        override fun stopCapture() = Unit

        override fun playPcm16(base64Pcm16: String) = Unit

        override fun playPcm16(base64Pcm16: String, sessionId: Long?) = Unit

        override fun playLocalCuePcm16(base64Pcm16: String, sessionId: Long?): Boolean {
            playbackEntered.countDown()
            releasePlayback.await(2, TimeUnit.SECONDS)
            if (!released.get()) {
                playedLocalCuePcm16 += base64Pcm16
            }
            return true
        }

        override fun activatePlaybackSession(sessionId: Long) = Unit

        override fun invalidatePlaybackSession() = Unit

        override fun suppressPlayback() = Unit

        override fun release() {
            released.set(true)
        }

        fun awaitPlaybackEntered(): Boolean = playbackEntered.await(2, TimeUnit.SECONDS)

        fun releasePlayback() {
            releasePlayback.countDown()
        }
    }

    private class QueuedLocalCueAudioEngine : VoiceAudioEngine {
        val playedLocalCuePcm16 = ConcurrentLinkedQueue<String>()
        private val queuedCue = ConcurrentLinkedQueue<QueuedCue>()
        private val queuedCueLatch = CountDownLatch(1)
        private var localCueGeneration = 0L

        override fun setErrorHandler(onError: ((String) -> Unit)?) = Unit

        override fun startCapture(onPcm16: (ByteArray) -> Unit, onDebugInjectionComplete: () -> Unit) = Unit

        override fun stopCapture() = Unit

        override fun playPcm16(base64Pcm16: String) = Unit

        override fun playPcm16(base64Pcm16: String, sessionId: Long?) = Unit

        override fun playLocalCuePcm16(base64Pcm16: String, sessionId: Long?): Boolean {
            queuedCue += QueuedCue(base64Pcm16, localCueGeneration)
            queuedCueLatch.countDown()
            return true
        }

        override fun invalidateLocalCuePlayback() {
            localCueGeneration += 1
        }

        override fun activatePlaybackSession(sessionId: Long) = Unit

        override fun invalidatePlaybackSession() = Unit

        override fun suppressPlayback() = Unit

        override fun release() = Unit

        fun awaitQueuedCue(): Boolean = queuedCueLatch.await(2, TimeUnit.SECONDS)

        fun processQueuedCues() {
            while (true) {
                val cue = queuedCue.poll() ?: return
                if (cue.localCueGeneration == localCueGeneration) {
                    playedLocalCuePcm16 += cue.base64Pcm16
                }
            }
        }

        private data class QueuedCue(
            val base64Pcm16: String,
            val localCueGeneration: Long,
        )
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
