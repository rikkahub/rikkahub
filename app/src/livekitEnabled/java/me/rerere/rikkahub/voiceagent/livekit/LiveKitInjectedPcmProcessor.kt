package me.rerere.rikkahub.voiceagent.livekit

import io.livekit.android.audio.AudioProcessorInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixtureSource
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureSource
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunState
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRuntime
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationStatus
import me.rerere.rikkahub.voiceagent.telemetry.VoiceLatencyTelemetryCoordinator
import org.koin.core.context.GlobalContext

internal class LiveKitAutomationPcmSource(
    private val automationStatus: () -> VoiceAutomationStatus? = ::activeAutomationStatusOrNull,
) : LiveKitAutomationAudioBinding {
    private val lock = Any()
    private val queuedPcm = ArrayDeque<QueuedPcm>()
    private var nextGeneration = 0L
    private var activeOwner: ActiveOwner? = null
    private var captureJob: Job? = null
    private var injectionEnded = false
    private var outputSampleRateHz = FIXTURE_SAMPLE_RATE_HZ
    private var outputChannelCount = 1
    private var resamplePhase = 0
    private var currentSample: Short? = null

    val isActive: Boolean
        get() {
            var staleJob: Job? = null
            val active = synchronized(lock) {
                val owner = activeOwner ?: return@synchronized false
                if (owner.matchesCurrentStatus()) {
                    true
                } else {
                    staleJob = clearOwnerLocked(owner.generation)
                    false
                }
            }
            staleJob?.cancel()
            return active
        }

    override fun activate(
        runHash: String,
        captureSource: VoiceCaptureSource,
        scope: CoroutineScope,
    ): AutoCloseable {
        val fixtureSource = captureSource as? VoiceCaptureFixtureSource
            ?: error("LiveKit automation requires a fixture capture source")
        val status = checkNotNull(automationStatus()) {
            "LiveKit automation requires an active automation run"
        }
        check(status.state == VoiceAutomationRunState.Active)
        check(status.requestedTransport == VoiceAgentTransport.LiveKitExperimental)
        check(status.runHash == runHash)

        val owner = synchronized(lock) {
            check(activeOwner == null) { "LiveKit automation audio already has an active owner" }
            check(nextGeneration < Long.MAX_VALUE) { "LiveKit automation audio generation exhausted" }
            ActiveOwner(nextGeneration + 1, runHash).also { next ->
                nextGeneration = next.generation
                activeOwner = next
                queuedPcm.clear()
                injectionEnded = false
                resetResamplingLocked()
            }
        }
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            fixtureSource.pump(
                onPcm16 = { pcm16 -> enqueuePcm16(owner.generation, pcm16) },
                onFixtureComplete = { markInjectionEnded(owner.generation) },
            )
        }
        val published = synchronized(lock) {
            if (activeOwner == owner && owner.matchesCurrentStatus()) {
                captureJob = job
                true
            } else {
                clearOwnerLocked(owner.generation)
                false
            }
        }
        if (!published) {
            job.cancel()
            error("LiveKit automation audio activation became stale")
        }
        return AutoCloseable { deactivate(owner.generation) }
    }

    private fun enqueuePcm16(expectedGeneration: Long, pcm16: ByteArray) {
        var staleJob: Job? = null
        synchronized(lock) {
            val owner = activeOwner ?: return@synchronized
            if (owner.generation != expectedGeneration) return@synchronized
            if (!owner.matchesCurrentStatus()) {
                staleJob = clearOwnerLocked(owner.generation)
                return@synchronized
            }
            if (pcm16.isEmpty()) return@synchronized
            queuedPcm.addLast(QueuedPcm(pcm16.copyOf()))
            injectionEnded = false
        }
        staleJob?.cancel()
    }

    internal fun injectionComplete(): Boolean {
        var staleJob: Job? = null
        val complete = synchronized(lock) {
            val owner = activeOwner ?: return@synchronized false
            if (!owner.matchesCurrentStatus()) {
                staleJob = clearOwnerLocked(owner.generation)
                return@synchronized false
            }
            injectionEnded && queuedPcm.isEmpty() && currentSample == null
        }
        staleJob?.cancel()
        return complete
    }

    fun configureOutputFormat(sampleRateHz: Int, numChannels: Int) {
        require(sampleRateHz > 0) { "LiveKit capture sample rate must be positive" }
        require(numChannels > 0) { "LiveKit capture channel count must be positive" }
        synchronized(lock) {
            if (outputSampleRateHz == sampleRateHz && outputChannelCount == numChannels) return
            outputSampleRateHz = sampleRateHz
            outputChannelCount = numChannels
            resetResamplingLocked()
        }
    }

    fun replaceOrZero(buffer: ByteBuffer) {
        var staleJob: Job? = null
        synchronized(lock) {
            val owner = activeOwner ?: return@synchronized
            if (!owner.matchesCurrentStatus()) {
                staleJob = clearOwnerLocked(owner.generation)
                return@synchronized
            }
            // WebRTC exposes its native float* capture plane through this ByteBuffer.
            buffer.order(ByteOrder.nativeOrder())
            while (buffer.remaining() >= FLOAT_BYTES_PER_SAMPLE) {
                val sample = currentSample ?: readSampleLocked().also { currentSample = it }
                buffer.putFloat(sample?.toFloat() ?: 0.0f)
                if (sample != null) {
                    resamplePhase += FIXTURE_SAMPLE_RATE_HZ
                    while (resamplePhase >= outputSampleRateHz) {
                        resamplePhase -= outputSampleRateHz
                        currentSample = readSampleLocked()
                        if (currentSample == null) {
                            resamplePhase = 0
                            break
                        }
                    }
                }
            }
            while (buffer.hasRemaining()) buffer.put(0)
        }
        staleJob?.cancel()
    }

    private fun readSampleLocked(): Short? {
        val low = readByteLocked() ?: return null
        val high = readByteLocked() ?: return null
        return ((high.toInt() shl 8) or (low.toInt() and 0xff)).toShort()
    }

    private fun readByteLocked(): Byte? {
        while (true) {
            val queued = queuedPcm.peekFirst() ?: return null
            if (queued.remaining == 0) {
                queuedPcm.removeFirst()
                continue
            }
            return queued.bytes[queued.offset++].also {
                if (queued.remaining == 0) queuedPcm.removeFirst()
            }
        }
    }

    private fun markInjectionEnded(expectedGeneration: Long) {
        var staleJob: Job? = null
        synchronized(lock) {
            val owner = activeOwner ?: return@synchronized
            if (owner.generation != expectedGeneration) return@synchronized
            if (!owner.matchesCurrentStatus()) {
                staleJob = clearOwnerLocked(owner.generation)
            } else {
                injectionEnded = true
            }
        }
        staleJob?.cancel()
    }

    private fun deactivate(generation: Long) {
        synchronized(lock) { clearOwnerLocked(generation) }?.cancel()
    }

    private fun clearOwnerLocked(generation: Long): Job? {
        if (activeOwner?.generation != generation) return null
        activeOwner = null
        queuedPcm.clear()
        injectionEnded = false
        resetResamplingLocked()
        return captureJob.also { captureJob = null }
    }

    private fun resetResamplingLocked() {
        resamplePhase = 0
        currentSample = null
    }

    private fun ActiveOwner.matchesCurrentStatus(): Boolean =
        runCatching { automationStatus() }
            .getOrNull()
            ?.let { status ->
                status.state == VoiceAutomationRunState.Active &&
                    status.runHash == runHash &&
                    status.requestedTransport == VoiceAgentTransport.LiveKitExperimental
            }
            ?: false

    private data class ActiveOwner(val generation: Long, val runHash: String)

    private class QueuedPcm(
        val bytes: ByteArray,
        var offset: Int = 0,
    ) {
        val remaining: Int
            get() = bytes.size - offset
    }

    private companion object {
        const val FIXTURE_SAMPLE_RATE_HZ = 16_000
        const val FLOAT_BYTES_PER_SAMPLE = Float.SIZE_BYTES
    }
}

internal class LiveKitInjectedPcmProcessor(
    private val source: LiveKitAutomationPcmSource,
) : AudioProcessorInterface {
    private var currentSampleRateHz = 16000
    private var currentChannelCount = 1
    private var telemetryCoordinator: VoiceLatencyTelemetryCoordinator? = null

    fun attachTelemetry(coordinator: VoiceLatencyTelemetryCoordinator) {
        this.telemetryCoordinator = coordinator
    }

    override fun getName(): String = "rikka-stage1-pcm"
    override fun isEnabled(): Boolean = source.isActive || telemetryCoordinator != null

    override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) {
        currentSampleRateHz = sampleRateHz
        currentChannelCount = numChannels
        source.configureOutputFormat(sampleRateHz, numChannels)
    }

    override fun resetAudioProcessing(newRate: Int) {
        currentSampleRateHz = newRate
        source.configureOutputFormat(newRate, currentChannelCount)
    }

    override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        val coordinator = telemetryCoordinator
        if (source.isActive) {
            source.replaceOrZero(buffer)
            if (coordinator != null) {
                dispatchCapturedFloats(buffer, coordinator)
            }
        } else if (coordinator != null) {
            dispatchCapturedFloats(buffer, coordinator)
        }
    }

    private fun dispatchCapturedFloats(
        buffer: ByteBuffer,
        coordinator: VoiceLatencyTelemetryCoordinator,
    ) {
        val duplicate = buffer.duplicate()
        if (duplicate.position() > 0) {
            duplicate.flip()
        }
        duplicate.order(ByteOrder.nativeOrder())
        val sampleCount = duplicate.remaining() / Float.SIZE_BYTES
        if (sampleCount <= 0) return
        val pcm16 = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            val floatSample = duplicate.float
            val shortSample = floatSample.toInt().coerceIn(-32768, 32767).toShort()
            pcm16[i * 2] = (shortSample.toInt() and 0xFF).toByte()
            pcm16[i * 2 + 1] = ((shortSample.toInt() shr 8) and 0xFF).toByte()
        }
        coordinator.onCapturePcm16(pcm16, currentSampleRateHz, currentChannelCount)
    }
}

private fun activeAutomationStatusOrNull(): VoiceAutomationStatus? =
    runCatching {
        GlobalContext.get().get<VoiceAutomationRuntime>().status()
    }.getOrNull()
