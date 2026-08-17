package me.rerere.rikkahub.voiceagent.telemetry

import android.os.SystemClock
import me.rerere.rikkahub.voiceagent.audio.voicePcm16Level

const val NORMALIZED_RMS_THRESHOLD = 0.015
const val SPEECH_START_CONFIRMATION_MICROS = 60_000L // 60ms
const val SPEECH_END_CONFIRMATION_MICROS = 400_000L // 400ms
private const val SUB_WINDOW_DURATION_MICROS = 20_000L // 20ms

sealed interface SpeechDetectionEvent {
    data class SpeechStart(val onsetNanos: Long) : SpeechDetectionEvent
    data class SpeechEnd(val onsetNanos: Long) : SpeechDetectionEvent
    data class SilenceCandidateCancelled(val resumedNanos: Long) : SpeechDetectionEvent
}

internal fun defaultMonotonicNanos(): Long =
    runCatching { SystemClock.elapsedRealtimeNanos() }.getOrElse { System.nanoTime() }

class VoiceTelemetrySpeechDetector(
    private val monotonicNanos: () -> Long = ::defaultMonotonicNanos,
) {
    private var isSpeaking = false
    private var activeDurationMicros = 0L
    private var activeRunOnsetNanos: Long? = null
    private var silenceDurationMicros = 0L
    private var silenceRunOnsetNanos: Long? = null
    private var residualBytes = ByteArray(0)
    private var residualOnsetNanos: Long? = null

    fun onPcm16Chunk(
        pcm16: ByteArray,
        sampleRateHz: Int = 16000,
        channelCount: Int = 1,
        onEvent: (SpeechDetectionEvent) -> Unit,
    ) {
        if (pcm16.isEmpty() || sampleRateHz <= 0 || channelCount <= 0) return
        val bytesPerFrame = channelCount * 2
        val framesPerSubWindow = (sampleRateHz * SUB_WINDOW_DURATION_MICROS / 1_000_000L).toInt()
        val bytesPerSubWindow = framesPerSubWindow * bytesPerFrame

        val totalBytes = residualBytes + pcm16
        val subWindowCount = totalBytes.size / bytesPerSubWindow
        val remainingBytes = totalBytes.size % bytesPerSubWindow

        val chunkDurationNanos = (pcm16.size / bytesPerFrame) * 1_000_000_000L / sampleRateHz
        val chunkEndNanos = monotonicNanos()
        val chunkStartNanos = chunkEndNanos - chunkDurationNanos
        val baseStartNanos = residualOnsetNanos ?: chunkStartNanos

        residualBytes = if (remainingBytes > 0) {
            residualOnsetNanos = baseStartNanos + (subWindowCount * SUB_WINDOW_DURATION_MICROS * 1000L)
            totalBytes.copyOfRange(subWindowCount * bytesPerSubWindow, totalBytes.size)
        } else {
            residualOnsetNanos = null
            ByteArray(0)
        }

        for (i in 0 until subWindowCount) {
            val subWindowOffsetNanos = baseStartNanos + (i * SUB_WINDOW_DURATION_MICROS * 1000L)
            val subWindow = totalBytes.copyOfRange(i * bytesPerSubWindow, (i + 1) * bytesPerSubWindow)
            val monoPcm16 = if (channelCount == 1) subWindow else downmixToMono(subWindow, channelCount)

            val level = voicePcm16Level(monoPcm16)
            val normalizedRms = level.rms.toDouble() / 32768.0
            val isActive = normalizedRms >= NORMALIZED_RMS_THRESHOLD

            if (isActive) {
                val wasSilencePending = silenceDurationMicros > 0
                silenceDurationMicros = 0L
                silenceRunOnsetNanos = null

                if (!isSpeaking) {
                    if (activeRunOnsetNanos == null) {
                        activeRunOnsetNanos = subWindowOffsetNanos
                    }
                    activeDurationMicros += SUB_WINDOW_DURATION_MICROS
                    if (activeDurationMicros >= SPEECH_START_CONFIRMATION_MICROS) {
                        isSpeaking = true
                        val onset = requireNotNull(activeRunOnsetNanos)
                        activeRunOnsetNanos = null
                        onEvent(SpeechDetectionEvent.SpeechStart(onset))
                    }
                } else if (wasSilencePending) {
                    onEvent(SpeechDetectionEvent.SilenceCandidateCancelled(resumedNanos = subWindowOffsetNanos))
                }
            } else {
                activeDurationMicros = 0L
                activeRunOnsetNanos = null
                if (isSpeaking) {
                    if (silenceRunOnsetNanos == null) {
                        silenceRunOnsetNanos = subWindowOffsetNanos
                    }
                    silenceDurationMicros += SUB_WINDOW_DURATION_MICROS
                    if (silenceDurationMicros >= SPEECH_END_CONFIRMATION_MICROS) {
                        isSpeaking = false
                        val onset = requireNotNull(silenceRunOnsetNanos)
                        silenceRunOnsetNanos = null
                        onEvent(SpeechDetectionEvent.SpeechEnd(onset))
                    }
                }
            }
        }
    }

    private fun downmixToMono(multiChannelPcm16: ByteArray, channelCount: Int): ByteArray {
        val frameCount = multiChannelPcm16.size / (channelCount * 2)
        val mono = ByteArray(frameCount * 2)
        for (f in 0 until frameCount) {
            var sum = 0
            for (c in 0 until channelCount) {
                val byteIdx = (f * channelCount + c) * 2
                val sample = ((multiChannelPcm16[byteIdx + 1].toInt() shl 8) or (multiChannelPcm16[byteIdx].toInt() and 0xFF)).toShort()
                sum += sample
            }
            val avg = (sum / channelCount).coerceIn(-32768, 32767).toShort()
            mono[f * 2] = (avg.toInt() and 0xFF).toByte()
            mono[f * 2 + 1] = ((avg.toInt() shr 8) and 0xFF).toByte()
        }
        return mono
    }

    fun reset() {
        isSpeaking = false
        activeDurationMicros = 0L
        activeRunOnsetNanos = null
        silenceDurationMicros = 0L
        silenceRunOnsetNanos = null
        residualBytes = ByteArray(0)
        residualOnsetNanos = null
    }
}
