package me.rerere.rikkahub.voiceagent.automation

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.telemetry.sha256Hex
import org.koin.core.context.GlobalContext

internal fun interface VoiceAutomationScheduledTransition {
    fun cancel()
}

internal fun interface VoiceAutomationTransitionScheduler {
    fun schedule(
        delayMs: Long,
        transition: () -> Unit,
    ): VoiceAutomationScheduledTransition
}

internal interface VoiceAutomationAudioProbe {
    fun onInjectionStarted(totalBytes: Long)
    fun onInjectionChunk(byteCount: Int)
    fun onInjectionCompleted()
    fun onCaptureAttested(source: String, micBytes: Long, fixtureBytes: Long) = Unit
    fun onOutputQueued(byteCount: Int)
    fun onOutputWritten(byteCount: Int, nonSilent: Boolean)
    fun captureLiveKitMediaOwner(): VoiceAutomationMediaOwner? = null
    fun onLiveKitRemoteTrackAttached(owner: VoiceAutomationMediaOwner) = Unit
    fun onLiveKitRemoteTrackDetached(owner: VoiceAutomationMediaOwner) = Unit
    fun onLiveKitOutputObserved(
        owner: VoiceAutomationMediaOwner,
        nonSilent: Boolean,
        beforeSemanticBoundary: () -> Unit,
    ): Boolean = true
    fun onLiveKitOutputWritten(
        owner: VoiceAutomationMediaOwner,
        byteCount: Int,
        nonSilent: Boolean,
        rmsActive: Boolean,
        audioWindowMicros: Long,
    ) {
        onOutputWritten(byteCount, nonSilent)
    }
    fun onLiveKitProgressWritten(
        owner: VoiceAutomationMediaOwner,
        byteCount: Int,
        nonSilent: Boolean,
        rmsActive: Boolean,
        audioWindowMicros: Long,
    ) {
        onLiveKitOutputWritten(owner, byteCount, nonSilent, rmsActive, audioWindowMicros)
    }
    fun onLiveKitOutputDrained(owner: VoiceAutomationMediaOwner) {
        onOutputDrained()
    }
    fun onLiveKitOutputSilenceConfirmed(
        owner: VoiceAutomationMediaOwner,
        beforePlaybackStops: () -> Unit = {},
    ) {
        onOutputSilenceConfirmed()
    }
    fun onOutputDrained()
    fun onInterruptionStarted()
    fun onOutputSilenceConfirmed()
}

internal data class VoiceAutomationMediaOwner(
    val runHash: String,
)

internal class DefaultVoiceAutomationAudioProbe(
    private val runtimeProvider: () -> VoiceAutomationRuntime?,
    private val monotonicMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
    private val scheduler: VoiceAutomationTransitionScheduler = SystemVoiceAutomationTransitionScheduler,
) : VoiceAutomationAudioProbe {
    private var runHash: String? = null
    private var lastSeenEventCount = 0L
    private var injectionTotalBytes: Long? = null
    private var injectedBytes = 0L
    private var injectionFirstChunkRecorded = false
    private var injectionCompleted = false
    private var playbackEpoch = 0L
    private var firstNonSilentRecorded = false
    private var outputState = OutputState.BeforeOutput
    private var lastNonSilentMs: Long? = null
    private var scheduledDropout: VoiceAutomationScheduledTransition? = null
    private var scheduledDropoutToken = 0L

    @Synchronized
    override fun onInjectionStarted(totalBytes: Long) {
        if (totalBytes < 0) return
        withActiveRuntime { runtime ->
            injectionTotalBytes = totalBytes
            injectedBytes = 0L
            injectionFirstChunkRecorded = false
            injectionCompleted = false
            record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.INJECTION_STARTED,
                    byteCount = totalBytes,
                ),
            )
        }
    }

    @Synchronized
    override fun onInjectionChunk(byteCount: Int) {
        if (byteCount <= 0) return
        withActiveRuntime { runtime ->
            if (injectionTotalBytes == null || injectionCompleted) return@withActiveRuntime
            injectedBytes += byteCount
            if (!injectionFirstChunkRecorded) {
                injectionFirstChunkRecorded = true
                record(
                    runtime,
                    VoiceAutomationEventInput(
                        name = VoiceAutomationEventName.INJECTION_FIRST_CHUNK,
                        byteCount = byteCount.toLong(),
                    ),
                )
            }
        }
    }

    @Synchronized
    override fun onInjectionCompleted() {
        withActiveRuntime { runtime ->
            val totalBytes = injectionTotalBytes ?: return@withActiveRuntime
            if (injectionCompleted || injectedBytes != totalBytes) return@withActiveRuntime
            injectionCompleted = true
            record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.INJECTION_COMPLETED,
                    byteCount = totalBytes,
                ),
            )
            record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.PROMPT_ENDED,
                    byteCount = totalBytes,
                ),
            )
        }
    }

    @Synchronized
    override fun onCaptureAttested(
        source: String,
        micBytes: Long,
        fixtureBytes: Long,
    ) {
        withActiveRuntime { runtime ->
            record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.CAPTURE_ATTESTED,
                    captureSource = source,
                    micBytes = micBytes,
                    fixtureBytes = fixtureBytes,
                ),
            )
        }
    }

    @Synchronized
    override fun onOutputQueued(byteCount: Int) {
        if (byteCount <= 0) return
        withActiveRuntime { runtime ->
            prepareOutputEpoch()
            record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.PLAYBACK_QUEUED,
                    playbackEpoch = playbackEpoch,
                    byteCount = byteCount.toLong(),
                ),
            )
        }
    }

    @Synchronized
    override fun onOutputWritten(byteCount: Int, nonSilent: Boolean) {
        if (byteCount <= 0) return
        withActiveRuntime { runtime ->
            recordOutputWritten(runtime, byteCount, nonSilent, mediaOwner = null)
        }
    }

    @Synchronized
    override fun captureLiveKitMediaOwner(): VoiceAutomationMediaOwner? {
        val runtime = runtimeProvider() ?: return null
        val status = runCatching(runtime::status).getOrNull() ?: return null
        if (
            status.state != VoiceAutomationRunState.Active ||
            status.requestedTransport != VoiceAgentTransport.LiveKitExperimental
        ) {
            return null
        }
        val activeRunHash = status.runHash ?: return null
        if (runCatching {
                VoiceAutomationEventValidation.validateHash("runHash", activeRunHash)
            }.isFailure
        ) {
            return null
        }
        return VoiceAutomationMediaOwner(activeRunHash)
    }

    @Synchronized
    override fun onLiveKitRemoteTrackAttached(owner: VoiceAutomationMediaOwner) {
        withActiveLiveKitOwner(owner) { runtime ->
            record(
                runtime,
                VoiceAutomationEventInput(VoiceAutomationEventName.REMOTE_TRACK_ATTACHED),
                owner,
            )
        }
    }

    @Synchronized
    override fun onLiveKitRemoteTrackDetached(owner: VoiceAutomationMediaOwner) {
        withActiveLiveKitOwner(owner) { runtime ->
            record(
                runtime,
                VoiceAutomationEventInput(VoiceAutomationEventName.REMOTE_TRACK_DETACHED),
                owner,
            )
        }
    }

    @Synchronized
    override fun onLiveKitOutputObserved(
        owner: VoiceAutomationMediaOwner,
        nonSilent: Boolean,
        beforeSemanticBoundary: () -> Unit,
    ): Boolean {
        var accepted = false
        withActiveLiveKitOwner(owner) { runtime ->
            observeOutput(
                runtime = runtime,
                nonSilent = nonSilent,
                mediaOwner = owner,
                beforePlaybackEpochChange = beforeSemanticBoundary,
            )
            accepted = true
        }
        return accepted
    }

    @Synchronized
    override fun onLiveKitOutputWritten(
        owner: VoiceAutomationMediaOwner,
        byteCount: Int,
        nonSilent: Boolean,
        rmsActive: Boolean,
        audioWindowMicros: Long,
    ) {
        if (byteCount <= 0) return
        withActiveLiveKitOwner(owner) { runtime ->
            recordOutputWritten(
                runtime,
                byteCount,
                nonSilent,
                mediaOwner = owner,
                rmsActive = rmsActive,
                audioWindowMicros = audioWindowMicros,
            )
        }
    }

    @Synchronized
    override fun onLiveKitProgressWritten(
        owner: VoiceAutomationMediaOwner,
        byteCount: Int,
        nonSilent: Boolean,
        rmsActive: Boolean,
        audioWindowMicros: Long,
    ) {
        if (byteCount <= 0) return
        withActiveLiveKitOwner(owner) { runtime ->
            recordOutputProgress(
                runtime = runtime,
                byteCount = byteCount,
                mediaOwner = owner,
                rmsActive = rmsActive,
                audioWindowMicros = audioWindowMicros,
            )
        }
    }

    @Synchronized
    override fun onOutputDrained() {
        withActiveRuntime { runtime ->
            recordOutputDrained(runtime, mediaOwner = null)
        }
    }

    @Synchronized
    override fun onLiveKitOutputDrained(owner: VoiceAutomationMediaOwner) {
        withActiveLiveKitOwner(owner) { runtime ->
            recordOutputDrained(runtime, mediaOwner = owner)
        }
    }

    @Synchronized
    override fun onInterruptionStarted() {
        withActiveRuntime { runtime ->
            if (outputState != OutputState.Active || lastNonSilentMs == null) {
                return@withActiveRuntime
            }
            cancelDropoutTransition()
            outputState = OutputState.Interrupted
            record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.INTERRUPT_STARTED,
                    playbackEpoch = playbackEpoch,
                ),
            )
        }
    }

    @Synchronized
    override fun onOutputSilenceConfirmed() {
        withActiveRuntime { runtime ->
            confirmOutputSilence(runtime, mediaOwner = null)
        }
    }

    @Synchronized
    override fun onLiveKitOutputSilenceConfirmed(
        owner: VoiceAutomationMediaOwner,
        beforePlaybackStops: () -> Unit,
    ) {
        withActiveLiveKitOwner(owner) { runtime ->
            confirmOutputSilence(
                runtime = runtime,
                mediaOwner = owner,
                beforePlaybackStops = beforePlaybackStops,
            )
        }
    }

    @Synchronized
    private fun onDropoutThresholdReached(
        token: Long,
        mediaOwner: VoiceAutomationMediaOwner?,
    ) {
        if (token != scheduledDropoutToken) return
        scheduledDropout = null
        if (mediaOwner == null) {
            withActiveRuntime { runtime -> recordDropout(runtime, mediaOwner = null) }
        } else {
            withActiveLiveKitOwner(mediaOwner) { runtime -> recordDropout(runtime, mediaOwner) }
        }
    }

    private inline fun withActiveLiveKitOwner(
        owner: VoiceAutomationMediaOwner,
        block: (VoiceAutomationRuntime) -> Unit,
    ) {
        withActiveRuntime { runtime ->
            val status = runCatching(runtime::status).getOrNull() ?: return@withActiveRuntime
            if (
                status.requestedTransport == VoiceAgentTransport.LiveKitExperimental &&
                status.runHash == owner.runHash
            ) {
                block(runtime)
            }
        }
    }

    private inline fun withActiveRuntime(block: (VoiceAutomationRuntime) -> Unit) {
        val runtime = runtimeProvider() ?: run {
            resetState()
            return
        }
        val status = runCatching(runtime::status).getOrNull() ?: run {
            resetState()
            return
        }
        if (status.state != VoiceAutomationRunState.Active) {
            resetState()
            return
        }
        if (runHash != status.runHash || status.eventCount < lastSeenEventCount) {
            resetState()
            runHash = status.runHash
        }
        lastSeenEventCount = status.eventCount
        block(runtime)
    }

    private fun record(runtime: VoiceAutomationRuntime, event: VoiceAutomationEventInput): Boolean =
        runCatching {
            if (runtime.status().state == VoiceAutomationRunState.Active) {
                runtime.record(event)
                lastSeenEventCount = runtime.status().eventCount
                true
            } else {
                false
            }
        }.getOrDefault(false)

    private fun recordOutputWritten(
        runtime: VoiceAutomationRuntime,
        byteCount: Int,
        nonSilent: Boolean,
        mediaOwner: VoiceAutomationMediaOwner?,
        rmsActive: Boolean? = null,
        audioWindowMicros: Long? = null,
    ) {
        observeOutput(runtime, nonSilent, mediaOwner)
        recordOutputProgress(runtime, byteCount, mediaOwner, rmsActive, audioWindowMicros)
    }

    private fun observeOutput(
        runtime: VoiceAutomationRuntime,
        nonSilent: Boolean,
        mediaOwner: VoiceAutomationMediaOwner?,
        beforePlaybackEpochChange: () -> Unit = {},
    ) {
        prepareOutputEpoch(beforePlaybackEpochChange)
        if (nonSilent) {
            if (outputState == OutputState.Dropout) {
                beforePlaybackEpochChange()
                playbackEpoch += 1
                record(
                    runtime,
                    VoiceAutomationEventInput(
                        name = VoiceAutomationEventName.DROPOUT_ENDED,
                        playbackEpoch = playbackEpoch,
                    ),
                    mediaOwner,
                )
                outputState = OutputState.BeforeOutput
            }
            if (!firstNonSilentRecorded) {
                firstNonSilentRecorded = true
                record(
                    runtime,
                    VoiceAutomationEventInput(
                        name = VoiceAutomationEventName.REMOTE_AUDIO_FIRST_NON_SILENT,
                        playbackEpoch = playbackEpoch,
                    ),
                    mediaOwner,
                )
            }
            if (
                outputState != OutputState.Active &&
                outputState != OutputState.Interrupted
            ) {
                outputState = OutputState.Active
                record(
                    runtime,
                    VoiceAutomationEventInput(
                        name = VoiceAutomationEventName.PLAYBACK_ACTIVE,
                        playbackEpoch = playbackEpoch,
                        correlationKind = mediaOwner?.let {
                            VoiceAutomationCorrelationKind.MEDIA_STATE
                        },
                        correlationHash = mediaOwner?.let {
                            mediaStateHash(it.runHash, playbackEpoch)
                        },
                    ),
                    mediaOwner,
                )
            }
            lastNonSilentMs = monotonicMs()
            if (outputState == OutputState.Active) {
                scheduleDropoutTransition(mediaOwner)
            }
        }
    }

    private fun recordOutputProgress(
        runtime: VoiceAutomationRuntime,
        byteCount: Int,
        mediaOwner: VoiceAutomationMediaOwner?,
        rmsActive: Boolean? = null,
        audioWindowMicros: Long? = null,
    ) {
        if (playbackEpoch == 0L) return
        record(
            runtime,
            VoiceAutomationEventInput(
                name = VoiceAutomationEventName.PLAYBACK_WRITTEN,
                playbackEpoch = playbackEpoch,
                byteCount = byteCount.toLong(),
                rmsActive = rmsActive,
                audioWindowMicros = audioWindowMicros,
            ),
            mediaOwner,
        )
    }

    private fun record(
        runtime: VoiceAutomationRuntime,
        event: VoiceAutomationEventInput,
        mediaOwner: VoiceAutomationMediaOwner?,
    ): Boolean {
        if (mediaOwner == null) {
            return record(runtime, event)
        }
        return runCatching {
            if (runtime.recordIfActiveRun(mediaOwner.runHash, event)) {
                lastSeenEventCount = runtime.status().eventCount
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private fun recordOutputDrained(
        runtime: VoiceAutomationRuntime,
        mediaOwner: VoiceAutomationMediaOwner?,
    ) {
        if (playbackEpoch == 0L) return
        if (!record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.PLAYBACK_DRAINED,
                    playbackEpoch = playbackEpoch,
                ),
                mediaOwner,
            )
        ) return
        cancelDropoutTransition()
        if (
            outputState != OutputState.Interrupted &&
            outputState != OutputState.Dropout &&
            outputState != OutputState.Stopped
        ) {
            outputState = OutputState.Drained
            lastNonSilentMs = null
        }
    }

    private fun confirmOutputSilence(
        runtime: VoiceAutomationRuntime,
        mediaOwner: VoiceAutomationMediaOwner?,
        beforePlaybackStops: () -> Unit = {},
    ) {
        val lastOutputMs = lastNonSilentMs ?: return
        if (
            outputState != OutputState.Interrupted ||
            monotonicMs() - lastOutputMs < INTERRUPTION_SILENCE_MS
        ) return
        beforePlaybackStops()
        if (!record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.PLAYBACK_STOPPED,
                    playbackEpoch = playbackEpoch,
                ),
                mediaOwner,
            )
        ) return
        cancelDropoutTransition()
        outputState = OutputState.Stopped
        lastNonSilentMs = null
    }

    private fun recordDropout(
        runtime: VoiceAutomationRuntime,
        mediaOwner: VoiceAutomationMediaOwner?,
    ) {
        if (outputState != OutputState.Active || lastNonSilentMs == null) return
        if (record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.DROPOUT_STARTED,
                    playbackEpoch = playbackEpoch,
                ),
                mediaOwner,
            )
        ) {
            outputState = OutputState.Dropout
        }
    }

    private fun mediaStateHash(runHash: String, epoch: Long): String {
        val value = "$MEDIA_STATE_HASH_DOMAIN|$runHash|$epoch|active"
        return "sha256:${sha256Hex(value)}"
    }

    private fun prepareOutputEpoch(beforePlaybackEpochChange: () -> Unit = {}) {
        if (playbackEpoch == 0L) {
            playbackEpoch = 1L
        } else if (
            outputState == OutputState.Drained ||
            outputState == OutputState.Stopped
        ) {
            beforePlaybackEpochChange()
            playbackEpoch += 1
            outputState = OutputState.BeforeOutput
        }
    }

    private fun scheduleDropoutTransition(mediaOwner: VoiceAutomationMediaOwner?) {
        cancelDropoutTransition()
        val token = scheduledDropoutToken
        scheduledDropout = scheduler.schedule(DROPOUT_THRESHOLD_MS) {
            onDropoutThresholdReached(token, mediaOwner)
        }
    }

    private fun cancelDropoutTransition() {
        scheduledDropoutToken += 1
        scheduledDropout?.cancel()
        scheduledDropout = null
    }

    private fun resetState() {
        cancelDropoutTransition()
        runHash = null
        lastSeenEventCount = 0L
        injectionTotalBytes = null
        injectedBytes = 0L
        injectionFirstChunkRecorded = false
        injectionCompleted = false
        playbackEpoch = 0L
        firstNonSilentRecorded = false
        outputState = OutputState.BeforeOutput
        lastNonSilentMs = null
    }

    private enum class OutputState {
        BeforeOutput,
        Active,
        Dropout,
        Interrupted,
        Stopped,
        Drained,
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val DROPOUT_THRESHOLD_MS = 250L
        const val INTERRUPTION_SILENCE_MS = 100L
        const val MEDIA_STATE_HASH_DOMAIN = "voice-automation-media-state-v1"
    }
}

private object SystemVoiceAutomationTransitionScheduler : VoiceAutomationTransitionScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "voice-automation-audio-probe").apply {
            isDaemon = true
        }
    }.apply {
        removeOnCancelPolicy = true
    }

    override fun schedule(
        delayMs: Long,
        transition: () -> Unit,
    ): VoiceAutomationScheduledTransition {
        val future = executor.schedule(transition, delayMs, TimeUnit.MILLISECONDS)
        return VoiceAutomationScheduledTransition {
            future.cancel(false)
        }
    }
}

internal object VoiceAutomationAudioProbes {
    val shared: VoiceAutomationAudioProbe by lazy {
        DefaultVoiceAutomationAudioProbe(
            runtimeProvider = ::runtimeOrNull,
        )
    }

    fun activeSharedOrNull(): VoiceAutomationAudioProbe? =
        if (
            runCatching {
                runtimeOrNull()?.status()?.state == VoiceAutomationRunState.Active
            }.getOrDefault(false)
        ) {
            shared
        } else {
            null
        }

    private fun runtimeOrNull(): VoiceAutomationRuntime? =
        runCatching {
            GlobalContext.get().get<VoiceAutomationRuntime>()
        }.getOrNull()
}
