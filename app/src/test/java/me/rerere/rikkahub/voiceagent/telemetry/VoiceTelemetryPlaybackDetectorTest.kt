package me.rerere.rikkahub.voiceagent.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTelemetryPlaybackDetectorTest {
    private fun createPcm16Chunk(sampleCount: Int, rms: Int, channels: Int = 1): ByteArray {
        val bytes = ByteArray(sampleCount * channels * 2)
        val sampleVal = rms.coerceIn(-32768, 32767).toShort()
        for (i in 0 until sampleCount * channels) {
            bytes[i * 2] = (sampleVal.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun `playback start detects 20ms active onset immediately`() {
        var monotonicNanos = 1_000_000_000L
        val detector = VoiceTelemetryPlaybackDetector(monotonicNanos = { monotonicNanos })

        // 40ms chunk at 24kHz: 20ms silent (480 samples), 20ms active (480 samples)
        // delivered at t=1000ms (chunk start is t=960ms)
        val silentPart = createPcm16Chunk(480, rms = 100)
        val activePart = createPcm16Chunk(480, rms = 600)
        val chunk = silentPart + activePart

        val events = mutableListOf<PlaybackDetectionEvent>()
        detector.onDecodedChunk(chunk, sampleRateHz = 24000, channelCount = 1) { events.add(it) }

        assertEquals(1, events.size)
        // Subwindow 0 (0-20ms): 960ms (silent)
        // Subwindow 1 (20-40ms): 980ms (active) -> PlaybackStart(980ms)
        assertEquals(PlaybackDetectionEvent.PlaybackStart(onsetNanos = 980_000_000L), events[0])
    }

    @Test
    fun `playback stop emits after 200ms continuous silence`() {
        var monotonicNanos = 1_020_000_000L
        val detector = VoiceTelemetryPlaybackDetector(monotonicNanos = { monotonicNanos })
        val activeChunk20ms = createPcm16Chunk(320, rms = 600)
        val silentChunk20ms = createPcm16Chunk(320, rms = 100)

        val startEvents = mutableListOf<PlaybackDetectionEvent>()
        detector.onDecodedChunk(activeChunk20ms, sampleRateHz = 16000, channelCount = 1) { startEvents.add(it) }
        assertEquals(1, startEvents.size)

        val events = mutableListOf<PlaybackDetectionEvent>()
        // 10 chunks of 20ms = 200ms silence
        for (i in 0 until 10) {
            monotonicNanos += 20_000_000L
            detector.onDecodedChunk(silentChunk20ms, sampleRateHz = 16000, channelCount = 1) { events.add(it) }
        }
        assertEquals(1, events.size)
        // Silence started at 1020ms
        assertEquals(PlaybackDetectionEvent.PlaybackStop(onsetNanos = 1_020_000_000L), events[0])
    }

    @Test
    fun `explicit drain emits PlaybackStop immediately when playing`() {
        var monotonicNanos = 1_020_000_000L
        val detector = VoiceTelemetryPlaybackDetector(monotonicNanos = { monotonicNanos })
        val activeChunk20ms = createPcm16Chunk(320, rms = 600)

        detector.onDecodedChunk(activeChunk20ms, sampleRateHz = 16000, channelCount = 1) {}

        monotonicNanos = 1_050_000_000L
        val events = mutableListOf<PlaybackDetectionEvent>()
        detector.onExplicitDrain { events.add(it) }

        assertEquals(1, events.size)
        assertEquals(PlaybackDetectionEvent.PlaybackStop(onsetNanos = 1_050_000_000L), events[0])

        // Second drain does nothing
        val secondEvents = mutableListOf<PlaybackDetectionEvent>()
        detector.onExplicitDrain { secondEvents.add(it) }
        assertEquals(0, secondEvents.size)
    }
}
