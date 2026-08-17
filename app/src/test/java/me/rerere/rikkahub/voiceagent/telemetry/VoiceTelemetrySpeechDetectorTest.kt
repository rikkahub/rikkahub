package me.rerere.rikkahub.voiceagent.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTelemetrySpeechDetectorTest {
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
    fun `speech start detects 60ms speech onset accurately inside mixed-signal stereo chunk`() {
        var monotonicNanos = 1_000_000_000L
        val detector = VoiceTelemetrySpeechDetector(monotonicNanos = { monotonicNanos })

        // 100ms stereo chunk delivered at t=1000ms (chunk start is t=900ms)
        // first 40ms silent (640 samples/ch: 900-940ms), next 60ms active (960 samples/ch: 940-1000ms)
        val silentPart = createPcm16Chunk(640, rms = 100, channels = 2)
        val activePart = createPcm16Chunk(960, rms = 600, channels = 2)
        val mixedChunk = silentPart + activePart

        val events = mutableListOf<SpeechDetectionEvent>()
        detector.onPcm16Chunk(mixedChunk, sampleRateHz = 16000, channelCount = 2) { event ->
            events.add(event)
        }
        assertEquals(1, events.size)
        // Speech onset begins exactly 40ms into the chunk: 900ms + 40ms = 940ms
        assertEquals(SpeechDetectionEvent.SpeechStart(onsetNanos = 940_000_000L), events[0])
    }

    @Test
    fun `interrupted silence window emits SilenceCandidateCancelled`() {
        var monotonicNanos = 1_060_000_000L
        val detector = VoiceTelemetrySpeechDetector(monotonicNanos = { monotonicNanos })
        val activeChunk60ms = createPcm16Chunk(960, rms = 600) // 1000-1060ms
        val silentChunk20ms = createPcm16Chunk(320, rms = 100)

        detector.onPcm16Chunk(activeChunk60ms, sampleRateHz = 16000, channelCount = 1) {}

        for (i in 0 until 10) {
            monotonicNanos += 20_000_000L
            detector.onPcm16Chunk(silentChunk20ms, sampleRateHz = 16000, channelCount = 1) {}
        }

        monotonicNanos += 20_000_000L
        val activeChunk20ms = createPcm16Chunk(320, rms = 600)
        val events = mutableListOf<SpeechDetectionEvent>()
        detector.onPcm16Chunk(activeChunk20ms, sampleRateHz = 16000, channelCount = 1) { events.add(it) }
        assertEquals(1, events.size)
        assertEquals(SpeechDetectionEvent.SilenceCandidateCancelled(resumedNanos = 1_260_000_000L), events[0])
    }

    @Test
    fun `speech end emits after 400ms continuous silence`() {
        var monotonicNanos = 1_060_000_000L
        val detector = VoiceTelemetrySpeechDetector(monotonicNanos = { monotonicNanos })
        val activeChunk60ms = createPcm16Chunk(960, rms = 600) // 1000-1060ms
        val silentChunk20ms = createPcm16Chunk(320, rms = 100)

        val startEvents = mutableListOf<SpeechDetectionEvent>()
        detector.onPcm16Chunk(activeChunk60ms, sampleRateHz = 16000, channelCount = 1) { startEvents.add(it) }
        assertEquals(1, startEvents.size)

        val events = mutableListOf<SpeechDetectionEvent>()
        // 20 chunks of 20ms = 400ms silence
        for (i in 0 until 20) {
            monotonicNanos += 20_000_000L
            detector.onPcm16Chunk(silentChunk20ms, sampleRateHz = 16000, channelCount = 1) { events.add(it) }
        }
        assertEquals(1, events.size)
        // Silence started at 1060ms
        assertEquals(SpeechDetectionEvent.SpeechEnd(onsetNanos = 1_060_000_000L), events[0])
    }

    @Test
    fun `residual bytes are carried across calls until a full 20ms sub-window is formed`() {
        var monotonicNanos = 1_010_000_000L
        val detector = VoiceTelemetrySpeechDetector(monotonicNanos = { monotonicNanos })
        // 10ms chunk (160 samples at 16kHz)
        val chunk10ms = createPcm16Chunk(160, rms = 600)
        val events = mutableListOf<SpeechDetectionEvent>()

        // Send 10ms: should not form a sub-window yet
        detector.onPcm16Chunk(chunk10ms, sampleRateHz = 16000, channelCount = 1) { events.add(it) }
        assertEquals(0, events.size)

        // Send 50ms (800 samples): 10ms + 50ms = 60ms = 3 full 20ms subwindows of active audio
        monotonicNanos += 50_000_000L
        val chunk50ms = createPcm16Chunk(800, rms = 600)
        detector.onPcm16Chunk(chunk50ms, sampleRateHz = 16000, channelCount = 1) { events.add(it) }

        assertEquals(1, events.size)
        // Onset should start at 1000ms (1010ms - 10ms)
        assertEquals(SpeechDetectionEvent.SpeechStart(onsetNanos = 1_000_000_000L), events[0])
    }
}
