package me.rerere.rikkahub.voiceagent.livekit

import java.nio.ByteBuffer
import livekit.org.webrtc.AudioTrackSink
import me.rerere.rikkahub.voiceagent.audio.voicePcm16Level
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationAudioProbe
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationAudioProbes
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationMediaOwner

internal class LiveKitRemoteAudioProbe(
    private val automationAudioProbe: VoiceAutomationAudioProbe = VoiceAutomationAudioProbes.shared,
    private val monotonicMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) : AudioTrackSink, AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var pendingBytes = 0
    private var pendingNonSilent = false
    private var pendingRmsActive = false
    private var pendingAudioWindowMicros = 0L
    private var lastNonSilent: Boolean? = null
    private var lastProgressMs: Long? = null
    private var trackAttached = false
    private var trackDetached = false
    private val mediaOwner: VoiceAutomationMediaOwner? =
        automationAudioProbe.captureLiveKitMediaOwner()

    override fun onData(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        absoluteCaptureTimestampMs: Long,
    ) {
        val byteCount = audioData.remaining()
        if (
            bitsPerSample != PCM16_BITS_PER_SAMPLE ||
            sampleRate <= 0 ||
            numberOfChannels <= 0 ||
            numberOfFrames <= 0
        ) return
        val expectedByteCount =
            numberOfFrames.toLong() * numberOfChannels * PCM16_BYTES_PER_SAMPLE
        if (expectedByteCount != byteCount.toLong()) return
        val durationMicros = numberOfFrames.toLong() * MICROS_PER_SECOND / sampleRate
        if (durationMicros <= 0) return
        val frame = ByteArray(byteCount).also { bytes ->
            audioData.duplicate().get(bytes)
        }
        val nonSilent = frame.any { byte -> byte.toInt() != 0 }
        val rmsActive = voicePcm16Level(frame).rms >= RMS_ACTIVE_THRESHOLD
        val nowMs = monotonicMs()
        synchronized(lock) {
            if (closed) return
            val owner = mediaOwner ?: return
            val previousState = lastNonSilent
            if (previousState != null && previousState != nonSilent) {
                flushProgress(owner, nowMs)
            }
            if (
                pendingBytes > Int.MAX_VALUE - byteCount ||
                pendingAudioWindowMicros > Long.MAX_VALUE - durationMicros
            ) {
                flushProgress(owner, nowMs)
            }
            pendingBytes += byteCount
            pendingNonSilent = pendingNonSilent || nonSilent
            pendingRmsActive = pendingRmsActive || rmsActive
            pendingAudioWindowMicros += durationMicros
            val stateChanged = previousState == null || previousState != nonSilent
            lastNonSilent = nonSilent
            if (
                stateChanged ||
                lastProgressMs == null ||
                nowMs - checkNotNull(lastProgressMs) >= PROGRESS_INTERVAL_MS
            ) {
                flushProgress(owner, nowMs)
            }
            if (!nonSilent) {
                automationAudioProbe.onLiveKitOutputSilenceConfirmed(owner)
            }
        }
    }

    override fun close() {
        val nowMs = monotonicMs()
        synchronized(lock) {
            if (closed) return
            closed = true
            val owner = mediaOwner ?: return
            flushProgress(owner, nowMs)
        }
    }

    fun onTrackAttached() {
        synchronized(lock) {
            if (trackAttached) return
            trackAttached = true
            mediaOwner?.let(automationAudioProbe::onLiveKitRemoteTrackAttached)
        }
    }

    fun onTrackDetached() {
        synchronized(lock) {
            if (trackDetached) return
            trackDetached = true
            mediaOwner?.let(automationAudioProbe::onLiveKitRemoteTrackDetached)
        }
    }

    private fun flushProgress(
        owner: VoiceAutomationMediaOwner,
        nowMs: Long,
    ) {
        if (pendingBytes <= 0) return
        automationAudioProbe.onLiveKitOutputWritten(
            owner = owner,
            byteCount = pendingBytes,
            nonSilent = pendingNonSilent,
            rmsActive = pendingRmsActive,
            audioWindowMicros = pendingAudioWindowMicros,
        )
        pendingBytes = 0
        pendingNonSilent = false
        pendingRmsActive = false
        pendingAudioWindowMicros = 0L
        lastProgressMs = nowMs
    }

    private companion object {
        const val PCM16_BITS_PER_SAMPLE = 16
        const val PCM16_BYTES_PER_SAMPLE = 2L
        const val RMS_ACTIVE_THRESHOLD = 256
        const val MICROS_PER_SECOND = 1_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val PROGRESS_INTERVAL_MS = 50L
    }
}
