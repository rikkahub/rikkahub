package me.rerere.rikkahub.voiceagent.livekit

import io.livekit.android.audio.NoAudioHandler
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    fun `PCM16 fixture samples become normalized floats in the native capture buffer`() = runTest {
        val fixture = fixtureSource(pcm16(16_384, -16_384))
        val source = activeSource()
        val processor = LiveKitInjectedPcmProcessor(source)
        val activation = source.activate(RUN_HASH, fixture, this)
        processor.initializeAudioProcessing(sampleRateHz = 48_000, numChannels = 1)

        fixture.startInitial()
        advanceUntilIdle()

        assertArrayEquals(
            floatArrayOf(0.5f, 0.5f, 0.5f, -0.5f, -0.5f, -0.5f),
            processFloats(processor, sampleCount = 6),
            0.0f,
        )
        assertTrue(source.injectionComplete())

        activation.close()
        fixture.close()
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
            floatArrayOf(normalized(0x0102), normalized(0x0102)),
            processFloats(processor, sampleCount = 2),
            0.0f,
        )
        assertFalse(source.injectionComplete())
        assertArrayEquals(
            floatArrayOf(
                normalized(0x0102),
                normalized(0x0304),
                normalized(0x0304),
                normalized(0x0304),
            ),
            processFloats(processor, sampleCount = 4),
            0.0f,
        )
        assertTrue(source.injectionComplete())

        activation.close()
        fixture.close()
    }

    @Test
    fun `capture callback receives one float stream even when capture reports stereo`() = runTest {
        val fixture = fixtureSource(pcm16(0x0102))
        val source = activeSource()
        val processor = LiveKitInjectedPcmProcessor(source)
        val activation = source.activate(RUN_HASH, fixture, this)
        processor.initializeAudioProcessing(sampleRateHz = 16_000, numChannels = 2)

        fixture.startInitial()
        advanceUntilIdle()

        assertArrayEquals(
            floatArrayOf(normalized(0x0102)),
            processFloats(processor, sampleCount = 1),
            0.0f,
        )
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
        assertArrayEquals(
            floatArrayOf(normalized(0x0102)),
            processFloats(processor, sampleCount = 1),
            0.0f,
        )

        processor.resetAudioProcessing(newRate = 16_000)

        assertArrayEquals(
            floatArrayOf(normalized(0x0304)),
            processFloats(processor, sampleCount = 1),
            0.0f,
        )
        activation.close()
        fixture.close()
    }

    @Test
    fun `deactivation discards an unfinished resampling phase before reactivation`() = runTest {
        val firstFixture = fixtureSource(pcm16(0x0102))
        val source = activeSource()
        val processor = LiveKitInjectedPcmProcessor(source)
        val firstActivation = source.activate(RUN_HASH, firstFixture, this)
        processor.initializeAudioProcessing(sampleRateHz = 48_000, numChannels = 1)

        firstFixture.startInitial()
        advanceUntilIdle()
        assertArrayEquals(
            floatArrayOf(normalized(0x0102)),
            processFloats(processor, sampleCount = 1),
            0.0f,
        )
        firstActivation.close()
        firstFixture.close()

        val secondFixture = fixtureSource(pcm16(0x0304))
        val secondActivation = source.activate(RUN_HASH, secondFixture, this)
        secondFixture.startInitial()
        advanceUntilIdle()

        assertArrayEquals(
            floatArrayOf(normalized(0x0304)),
            processFloats(processor, sampleCount = 1),
            0.0f,
        )

        secondActivation.close()
        secondFixture.close()
    }

    @Test
    fun `fixture samples remain ordered across differently sized SDK buffers`() = runTest {
        val fixture = fixtureSource(pcm16(0x0201, 0x0403, 0x0605))
        val source = activeSource()
        val processor = LiveKitInjectedPcmProcessor(source)
        val activation = source.activate(RUN_HASH, fixture, this)

        fixture.startInitial()
        advanceUntilIdle()

        assertArrayEquals(
            floatArrayOf(normalized(0x0201)),
            processFloats(processor, 1),
            0.0f,
        )
        assertFalse(source.injectionComplete())
        assertArrayEquals(
            floatArrayOf(normalized(0x0403)),
            processFloats(processor, 1),
            0.0f,
        )
        assertFalse(source.injectionComplete())
        assertArrayEquals(
            floatArrayOf(normalized(0x0605), 0.0f),
            processFloats(processor, 2),
            0.0f,
        )
        assertTrue(source.injectionComplete())

        activation.close()
        fixture.close()
    }

    @Test
    fun `active processor zero fills before ready and overwrites every hardware float after ready`() = runTest {
        val fixture = fixtureSource(pcm16(0x0c0b, 0x0e0d))
        val source = activeSource()
        val processor = LiveKitInjectedPcmProcessor(source)
        val activation = source.activate(RUN_HASH, fixture, this)

        assertArrayEquals(
            floatArrayOf(0.0f, 0.0f),
            processFloats(processor, 2),
            0.0f,
        )
        fixture.startInitial()
        advanceUntilIdle()
        val buffer = ByteBuffer.allocate(3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        repeat(3) { buffer.putFloat(99.0f) }
        buffer.clear()
        processor.processAudio(numBands = 1, numFrames = 3, buffer = buffer)

        buffer.flip()
        assertArrayEquals(
            floatArrayOf(normalized(0x0c0b), normalized(0x0e0d), 0.0f),
            FloatArray(3) { buffer.float },
            0.0f,
        )
        assertEquals(3 * Float.SIZE_BYTES, buffer.limit())
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

    private fun processFloats(
        processor: LiveKitInjectedPcmProcessor,
        sampleCount: Int,
    ): FloatArray {
        val buffer = ByteBuffer.allocateDirect(sampleCount * Float.SIZE_BYTES)
        processor.processAudio(1, sampleCount, buffer)
        buffer.flip()
        buffer.order(ByteOrder.nativeOrder())
        return FloatArray(sampleCount) { buffer.float }
    }

    private fun normalized(sample: Int): Float = sample.toShort().toFloat() / 32_768.0f

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
