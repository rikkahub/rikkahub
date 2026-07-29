package me.rerere.rikkahub.voiceagent.livekit

import io.livekit.android.audio.AudioProcessorInterface
import java.nio.ByteBuffer
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
            injectionEnded && queuedPcm.isEmpty()
        }
        staleJob?.cancel()
        return complete
    }

    fun replaceOrZero(buffer: ByteBuffer) {
        var staleJob: Job? = null
        synchronized(lock) {
            val owner = activeOwner ?: return@synchronized
            if (!owner.matchesCurrentStatus()) {
                staleJob = clearOwnerLocked(owner.generation)
                return@synchronized
            }
            while (buffer.hasRemaining()) {
                val queued = queuedPcm.peekFirst()
                if (queued == null) {
                    buffer.put(0)
                } else {
                    val byteCount = minOf(buffer.remaining(), queued.remaining)
                    buffer.put(queued.bytes, queued.offset, byteCount)
                    queued.offset += byteCount
                    if (queued.remaining == 0) queuedPcm.removeFirst()
                }
            }
        }
        staleJob?.cancel()
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
        return captureJob.also { captureJob = null }
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
}

internal class LiveKitInjectedPcmProcessor(
    private val source: LiveKitAutomationPcmSource,
) : AudioProcessorInterface {
    override fun getName(): String = "rikka-stage1-pcm"
    override fun isEnabled(): Boolean = source.isActive
    override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) = Unit
    override fun resetAudioProcessing(newRate: Int) = Unit

    override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        source.replaceOrZero(buffer)
    }
}

private fun activeAutomationStatusOrNull(): VoiceAutomationStatus? =
    runCatching {
        GlobalContext.get().get<VoiceAutomationRuntime>().status()
    }.getOrNull()
