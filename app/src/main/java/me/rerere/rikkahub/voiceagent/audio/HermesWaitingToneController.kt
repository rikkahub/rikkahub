package me.rerere.rikkahub.voiceagent.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Base64
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
) {
    private val lock = Any()
    private var waiting = false
    private var loopJob: Job? = null

    fun setWaiting(active: Boolean) {
        if (active) {
            start()
        } else {
            stop()
        }
    }

    fun stop() {
        val job = synchronized(lock) {
            waiting = false
            loopJob.also {
                loopJob = null
            }
        }
        job?.cancel()
    }

    private fun start() {
        synchronized(lock) {
            waiting = true
            if (loopJob?.isActive == true) return
            loopJob = scope.launch(dispatcher) {
                runLoop()
            }
        }
    }

    private suspend fun runLoop() {
        try {
            delay(graceDelayMs)
            while (isWaiting()) {
                playCue()
                delay(repeatIntervalMs)
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

    private fun playCue() {
        val accepted = runCatching {
            audio.playLocalCuePcm16(base64Pcm16 = toneBase64Pcm16, sessionId = null)
        }.getOrElse { error ->
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

    private fun isWaiting(): Boolean = synchronized(lock) { waiting }

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
