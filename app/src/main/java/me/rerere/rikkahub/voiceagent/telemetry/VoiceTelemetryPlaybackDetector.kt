package me.rerere.rikkahub.voiceagent.telemetry

import android.os.SystemClock
import me.rerere.rikkahub.voiceagent.audio.voicePcm16Level

const val PLAYBACK_STOP_CONFIRMATION_MICROS = 200_000L // 200ms
private const val PLAYBACK_SUB_WINDOW_DURATION_MICROS = 20_000L // 20ms

sealed interface PlaybackDetectionEvent {
    val onsetNanos: Long
    data class PlaybackStart(override val onsetNanos: Long) : PlaybackDetectionEvent
    data class PlaybackStop(override val onsetNanos: Long) : PlaybackDetectionEvent
}

class VoiceTelemetryPlaybackDetector(
    private val monotonicNanos: () -> Long = ::defaultMonotonicNanos,
) {
    private var isPlaying = false
    private var silenceDurationMicros = 0L
    private var silenceRunOnsetNanos: Long? = null
    private var residualBytes = ByteArray(0)
    private var residualOnsetNanos: Long? = null

    fun onDecodedChunk(
        pcm16: ByteArray,
        sampleRateHz: Int = 16000,
        channelCount: Int = 1,
        onEvent: (PlaybackDetectionEvent) -> Unit,
    ) {
        if (pcm16.isEmpty() || sampleRateHz <= 0 || channelCount <= 0) return
        val bytesPerFrame = channelCount * 2
        val framesPerSubWindow = (sampleRateHz * PLAYBACK_SUB_WINDOW_DURATION_MICROS / 1_000_000L).toInt()
        val bytesPerSubWindow = framesPerSubWindow * bytesPerFrame

        val totalBytes = residualBytes + pcm16
        val subWindowCount = totalBytes.size / bytesPerSubWindow
        val remainingBytes = totalBytes.size % bytesPerSubWindow

        val chunkDurationNanos = (pcm16.size / bytesPerFrame) * 1_000_000_000L / sampleRateHz
        val chunkEndNanos = monotonicNanos()
        val chunkStartNanos = chunkEndNanos - chunkDurationNanos
        val baseStartNanos = residualOnsetNanos ?: chunkStartNanos

        residualBytes = if (remainingBytes > 0) {
            residualOnsetNanos = baseStartNanos + (subWindowCount * PLAYBACK_SUB_WINDOW_DURATION_MICROS * 1000L)
            totalBytes.copyOfRange(subWindowCount * bytesPerSubWindow, totalBytes.size)
        } else {
            residualOnsetNanos = null
            ByteArray(0)
        }

        for (i in 0 until subWindowCount) {
            val subWindowOffsetNanos = baseStartNanos + (i * PLAYBACK_SUB_WINDOW_DURATION_MICROS * 1000L)
            val subWindow = totalBytes.copyOfRange(i * bytesPerSubWindow, (i + 1) * bytesPerSubWindow)
            val monoPcm16 = if (channelCount == 1) subWindow else downmixToMono(subWindow, channelCount)

            val level = voicePcm16Level(monoPcm16)
            val normalizedRms = level.rms.toDouble() / 32768.0
            val isActive = normalizedRms >= NORMALIZED_RMS_THRESHOLD

            if (isActive) {
                silenceDurationMicros = 0L
                silenceRunOnsetNanos = null
                if (!isPlaying) {
                    isPlaying = true
                    onEvent(PlaybackDetectionEvent.PlaybackStart(onsetNanos = subWindowOffsetNanos))
                }
            } else {
                if (isPlaying) {
                    if (silenceRunOnsetNanos == null) {
                        silenceRunOnsetNanos = subWindowOffsetNanos
                    }
                    silenceDurationMicros += PLAYBACK_SUB_WINDOW_DURATION_MICROS
                    if (silenceDurationMicros >= PLAYBACK_STOP_CONFIRMATION_MICROS) {
                        isPlaying = false
                        val onset = requireNotNull(silenceRunOnsetNanos)
                        silenceRunOnsetNanos = null
                        onEvent(PlaybackDetectionEvent.PlaybackStop(onsetNanos = onset))
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

    fun onExplicitDrain(onEvent: (PlaybackDetectionEvent) -> Unit) {
        if (isPlaying) {
            isPlaying = false
            silenceDurationMicros = 0L
            silenceRunOnsetNanos = null
            residualBytes = ByteArray(0)
            residualOnsetNanos = null
            onEvent(PlaybackDetectionEvent.PlaybackStop(onsetNanos = monotonicNanos()))
        }
    }

    fun reset() {
        isPlaying = false
        silenceDurationMicros = 0L
        silenceRunOnsetNanos = null
        residualBytes = ByteArray(0)
        residualOnsetNanos = null
    }
}
