package me.rerere.rikkahub.voiceagent.livekit

import io.livekit.android.audio.NoAudioHandler
import java.nio.ByteBuffer
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixture
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixtureArming
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixtureSource
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureSource
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunState
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationStatus
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveKitInjectedPcmProcessorTest {
    @After
    fun tearDown() {
        VoiceCaptureFixtureArming.clearForTest()
    }

    @Test
    fun `16 kHz fixture keeps real time pacing in a 48 kHz capture buffer`() = runTest {
        val fixture = fixtureSource(pcm16(0x0102, 0x0304))
        val source = activeSource()
        val processor = LiveKitInjectedPcmProcessor(source)
        val activation = source.activate(RUN_HASH, fixture, this)
        processor.initializeAudioProcessing(sampleRateHz = 48_000, numChannels = 1)

        fixture.startInitial()
        advanceUntilIdle()

        assertArrayEquals(
            pcm16(0x0102, 0x0102, 0x0102, 0x0304, 0x0304, 0x0304),
            process(processor, size = 12),
        )
        assertTrue(source.injectionComplete())

        activation.close()
        fixture.close()
    }

    @Test
    fun `mono fixture duplicates each sample into negotiated output channels`() = runTest {
        val fixture = fixtureSource(pcm16(0x0102))
        val source = activeSource()
        val processor = LiveKitInjectedPcmProcessor(source)
        val activation = source.activate(RUN_HASH, fixture, this)
        processor.initializeAudioProcessing(sampleRateHz = 16_000, numChannels = 2)

        fixture.startInitial()
        advanceUntilIdle()

        assertArrayEquals(pcm16(0x0102, 0x0102), process(processor, size = 4))
        activation.close()
        fixture.close()
    }

    @Test
    fun `capture format reset discards an unfinished resampling phase`() = runTest {
        val fixture = fixtureSource(pcm16(0x0102, 0x0304))
        val source = activeSource()
        val processor = LiveKitInjectedPcmProcessor(source)
        val activation = source.activate(RUN_HASH, fixture, this)
        processor.initializeAudioProcessing(sampleRateHz = 48_000, numChannels = 1)

        fixture.startInitial()
        advanceUntilIdle()
        assertArrayEquals(pcm16(0x0102), process(processor, size = 2))

        processor.resetAudioProcessing(newRate = 16_000)

        assertArrayEquals(pcm16(0x0304), process(processor, size = 2))
        activation.close()
        fixture.close()
    }

    @Test
    fun `fixture bytes remain ordered across differently sized SDK buffers`() = runTest {
        val fixture = fixtureSource(pcm16(0x0201, 0x0403, 0x0605))
        val source = activeSource()
        val processor = LiveKitInjectedPcmProcessor(source)
        val activation = source.activate(RUN_HASH, fixture, this)

        fixture.startInitial()
        advanceUntilIdle()

        assertArrayEquals(pcm16(0x0201), process(processor, 2))
        assertFalse(source.injectionComplete())
        assertArrayEquals(pcm16(0x0403), process(processor, 2))
        assertFalse(source.injectionComplete())
        assertArrayEquals(pcm16(0x0605, 0), process(processor, 4))
        assertTrue(source.injectionComplete())

        activation.close()
        fixture.close()
    }

    @Test
    fun `active processor zero fills before ready and overwrites every hardware byte after ready`() = runTest {
        val fixture = fixtureSource(pcm16(0x0c0b, 0x0e0d))
        val source = activeSource()
        val processor = LiveKitInjectedPcmProcessor(source)
        val activation = source.activate(RUN_HASH, fixture, this)

        assertArrayEquals(pcm16(0, 0), process(processor, 4))
        fixture.startInitial()
        advanceUntilIdle()
        val hardware = byteArrayOf(99, 99, 99, 99, 99, 99)
        val buffer = ByteBuffer.wrap(hardware)
        processor.processAudio(numBands = 1, numFrames = 3, buffer = buffer)

        assertArrayEquals(pcm16(0x0c0b, 0x0e0d, 0), hardware)
        assertEquals(6, buffer.position())
        assertTrue(source.injectionComplete())
        activation.close()
        fixture.close()
    }

    @Test
    fun `inactive processor leaves normal microphone capture untouched`() {
        val source = activeSource()
        val processor = LiveKitInjectedPcmProcessor(source)
        val hardware = byteArrayOf(41, 42, 43, 44)
        val buffer = ByteBuffer.wrap(hardware).apply {
            position(1)
            limit(3)
        }

        assertFalse(processor.isEnabled())
        processor.processAudio(numBands = 1, numFrames = 1, buffer = buffer)

        assertArrayEquals(byteArrayOf(41, 42, 43, 44), hardware)
        assertEquals(1, buffer.position())
    }

    @Test
    fun `activation rejects microphone and a mismatched automation run`() = runTest {
        var status = liveKitStatus()
        val source = LiveKitAutomationPcmSource(automationStatus = { status })
        val fixture = fixtureSource(byteArrayOf(1, 2))

        assertTrue(
            runCatching {
                source.activate(RUN_HASH, VoiceCaptureSource.Microphone, this)
            }.exceptionOrNull() is IllegalStateException,
        )
        status = status.copy(requestedTransport = VoiceAgentTransport.DirectGemini)
        assertTrue(
            runCatching { source.activate(RUN_HASH, fixture, this) }
                .exceptionOrNull() is IllegalStateException,
        )
        assertFalse(source.isActive)
        fixture.close()
    }

    @Test
    fun `runtime rollover disables the processor and discards queued fixture bytes`() = runTest {
        var status = liveKitStatus()
        val fixture = fixtureSource(byteArrayOf(1, 2, 3, 4))
        val source = LiveKitAutomationPcmSource(automationStatus = { status })
        val processor = LiveKitInjectedPcmProcessor(source)
        val activation = source.activate(RUN_HASH, fixture, this)
        fixture.startInitial()
        advanceUntilIdle()

        status = liveKitStatus(runHash = RUN_HASH_B)
        val hardware = byteArrayOf(91, 92, 93, 94)
        val buffer = ByteBuffer.wrap(hardware)

        assertFalse(processor.isEnabled())
        processor.processAudio(numBands = 1, numFrames = 2, buffer = buffer)
        assertArrayEquals(byteArrayOf(91, 92, 93, 94), hardware)
        assertEquals(0, buffer.position())
        assertFalse(source.injectionComplete())
        activation.close()
        fixture.close()
    }

    @Test
    fun `LiveKit audio options install the post processor without bypass`() {
        val processor = LiveKitInjectedPcmProcessor(activeSource())

        val options = liveKitAutomationAudioOptions(processor)
        val processorOptions = requireNotNull(options.audioProcessorOptions)

        assertTrue(options.audioHandler is NoAudioHandler)
        assertSame(processor, processorOptions.capturePostProcessor)
        assertFalse(processorOptions.capturePostBypass)
        assertEquals("rikka-stage1-pcm", processor.getName())
    }

    private fun activeSource() =
        LiveKitAutomationPcmSource(automationStatus = { liveKitStatus() })

    private fun fixtureSource(bytes: ByteArray): VoiceCaptureFixtureSource {
        val token = VoiceCaptureFixtureArming.arm(
            initial = VoiceCaptureFixture(
                path = "prompt.pcm",
                pcm16 = bytes,
                chunkBytes = 2,
                chunkDelayMs = 0,
            ),
            staged = emptyList(),
        )
        return VoiceCaptureFixtureArming.claim(token).getOrThrow()
    }

    private fun liveKitStatus(runHash: String = RUN_HASH) = VoiceAutomationStatus(
        state = VoiceAutomationRunState.Active,
        runHash = runHash,
        comparisonHash = COMPARISON_HASH,
        requestedTransport = VoiceAgentTransport.LiveKitExperimental,
    )

    private fun process(processor: LiveKitInjectedPcmProcessor, size: Int): ByteArray {
        val bytes = ByteArray(size) { 99 }
        processor.processAudio(1, size / 2, ByteBuffer.wrap(bytes))
        return bytes
    }

    private fun pcm16(vararg samples: Int): ByteArray =
        ByteArray(samples.size * PCM16_BYTES_PER_SAMPLE).also { bytes ->
            samples.forEachIndexed { index, sample ->
                bytes[index * PCM16_BYTES_PER_SAMPLE] = sample.toByte()
                bytes[index * PCM16_BYTES_PER_SAMPLE + 1] = (sample ushr 8).toByte()
            }
        }

    private companion object {
        const val RUN_HASH = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val RUN_HASH_B = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val COMPARISON_HASH =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val PCM16_BYTES_PER_SAMPLE = 2
    }
}
