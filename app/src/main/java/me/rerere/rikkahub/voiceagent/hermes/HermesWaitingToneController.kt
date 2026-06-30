package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.PI
import kotlin.math.sin

class HermesWaitingToneController(
    private val audio: VoiceAudioEngine,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val graceDelayMs: Long = DEFAULT_GRACE_DELAY_MS,
    private val repeatIntervalMs: Long = DEFAULT_REPEAT_INTERVAL_MS,
    private val toneBase64Pcm16: String = defaultToneBase64Pcm16(),
    private val recordDiagnostic: (String, String) -> Unit = { _, _ -> },
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val activePlaybackFinished = lock.newCondition()
    private var waiting = false
    private var generation = 0L
    private var loopJob: Job? = null
    private var activePlaybackCalls = 0

    init {
        audio.setLocalCueErrorHandler { detail ->
            safeRecordDiagnostic("hermes_waiting_tone_failed", detail)
        }
    }

    fun setWaiting(active: Boolean) {
        if (active) {
            start()
        } else {
            stop()
        }
    }

    fun stop() {
        var stoppedGeneration: Long? = null
        val job = locked {
            waiting = false
            stoppedGeneration = generation
            generation += 1
            loopJob.also {
                loopJob = null
            }
        }
        job?.cancel()
        val invalidationError = runCatching {
            audio.invalidateLocalCuePlayback(cueToken = stoppedGeneration)
        }.exceptionOrNull()
        waitForActivePlaybackCalls()
        invalidationError?.let { error ->
            safeRecordDiagnostic(
                "hermes_waiting_tone_failed",
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

    override fun close() {
        stop()
        audio.setLocalCueErrorHandler(null)
    }

    private fun start() {
        locked {
            waiting = true
            if (loopJob?.isActive == true) return
            generation += 1
            val loopGeneration = generation
            loopJob = scope.launch(dispatcher) {
                runLoop(loopGeneration)
            }
        }
    }

    private suspend fun runLoop(loopGeneration: Long) {
        try {
            delayFn(graceDelayMs)
            while (isWaiting(loopGeneration)) {
                playCue(loopGeneration)
                delayFn(repeatIntervalMs)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            safeRecordDiagnostic(
                "hermes_waiting_tone_failed",
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private suspend fun playCue(loopGeneration: Long) {
        if (!beginPlayback(loopGeneration)) {
            return
        }
        val playbackResult = try {
            runCatching {
                audio.playLocalCuePcm16(base64Pcm16 = toneBase64Pcm16, cueToken = loopGeneration)
            }
        } finally {
            finishPlayback()
        }
        val accepted = playbackResult.getOrElse { error ->
            safeRecordDiagnostic(
                "hermes_waiting_tone_failed",
                error.message ?: error.javaClass.simpleName,
            )
            return
        }
        if (!accepted) {
            safeRecordDiagnostic("hermes_waiting_tone_failed", "playback rejected")
        }
    }

    private fun safeRecordDiagnostic(name: String, detail: String) {
        runCatching {
            recordDiagnostic(name, detail)
        }
    }

    private fun isWaiting(loopGeneration: Long): Boolean = locked {
        isWaitingLocked(loopGeneration)
    }

    private fun beginPlayback(loopGeneration: Long): Boolean = locked {
        if (!isWaitingLocked(loopGeneration)) {
            false
        } else {
            activePlaybackCalls += 1
            true
        }
    }

    private fun finishPlayback() {
        locked {
            activePlaybackCalls -= 1
            activePlaybackFinished.signalAll()
        }
    }

    private fun waitForActivePlaybackCalls() {
        locked {
            while (activePlaybackCalls > 0) {
                try {
                    activePlaybackFinished.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun isWaitingLocked(loopGeneration: Long): Boolean =
        waiting && generation == loopGeneration

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        const val DEFAULT_GRACE_DELAY_MS = 2_000L
        const val DEFAULT_REPEAT_INTERVAL_MS = 4_000L
        private const val TONE_SAMPLE_RATE = 24_000
        private const val TONE_DURATION_MS = 160
        private const val TONE_FREQUENCY_HZ = 660.0
        private const val TONE_VOLUME = 0.18
        private const val FADE_MS = 12

        fun defaultToneBase64Pcm16(): String {
            val sampleCount = TONE_SAMPLE_RATE * TONE_DURATION_MS / 1_000
            val fadeSamples = TONE_SAMPLE_RATE * FADE_MS / 1_000
            val pcm = ByteArray(sampleCount * 2)
            for (index in 0 until sampleCount) {
                val fadeIn = if (fadeSamples == 0) {
                    1.0
                } else {
                    (index.toDouble() / fadeSamples).coerceIn(0.0, 1.0)
                }
                val fadeOut = if (fadeSamples == 0) {
                    1.0
                } else {
                    ((sampleCount - index - 1).toDouble() / fadeSamples).coerceIn(0.0, 1.0)
                }
                val envelope = minOf(fadeIn, fadeOut)
                val sample = (
                    sin(2.0 * PI * TONE_FREQUENCY_HZ * index / TONE_SAMPLE_RATE) *
                        Short.MAX_VALUE *
                        TONE_VOLUME *
                        envelope
                    ).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                pcm[index * 2] = (sample and 0xff).toByte()
                pcm[index * 2 + 1] = ((sample shr 8) and 0xff).toByte()
            }
            return Base64.getEncoder().encodeToString(pcm)
        }
    }
}
