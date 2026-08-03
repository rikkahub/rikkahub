package me.rerere.rikkahub.voiceagent.automation

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport

internal interface VoiceAutomationClock {
    fun monotonicMs(): Long
    fun wallClockMs(): Long
}

internal interface VoiceAutomationRuntime {
    fun prepare(binding: VoiceAutomationRunBinding)
    fun record(event: VoiceAutomationEventInput)
    fun recordIfActiveRun(runHash: String, event: VoiceAutomationEventInput): Boolean {
        val status = status()
        if (status.state != VoiceAutomationRunState.Active || status.runHash != runHash) {
            return false
        }
        record(event)
        return true
    }
    fun markReconnectTransportRestored(runHash: String): Boolean =
        status().let { status ->
            status.state == VoiceAutomationRunState.Active && status.runHash == runHash
        }
    fun status(): VoiceAutomationStatus
    fun finalizeRun(): File
    fun finalizeRunIfMatches(binding: VoiceAutomationRunBinding): File? = null
    fun reset()
}

@Serializable
internal enum class VoiceAutomationRunState {
    @SerialName("idle") Idle,
    @SerialName("active") Active,
    @SerialName("finalized") Finalized,
}

internal data class VoiceAutomationStatus(
    val state: VoiceAutomationRunState,
    val runHash: String? = null,
    val comparisonHash: String? = null,
    val requestedTransport: VoiceAgentTransport? = null,
    val eventCount: Long = 0,
)

internal class DefaultVoiceAutomationRuntime(
    private val noBackupFilesDir: File,
    private val clock: VoiceAutomationClock = SystemVoiceAutomationClock,
) : VoiceAutomationRuntime {
    private var binding: VoiceAutomationRunBinding? = null
    private var writer: VoiceAutomationEventWriter? = null
    private var currentStatus = VoiceAutomationStatus(VoiceAutomationRunState.Idle)
    private var lastEmittedMonotonicMs: Long? = null
    private var directAppCorrelationRecorded = false
    private var reconnectStarted = false
    private var reconnectTransportRestored = false
    private var reconnectMediaRestored = false
    private var handoverStarted = false
    private var handoverCellularObserved = false
    private var handoverWifiRestored = false
    private var handoverMediaRestored = false

    @Synchronized
    override fun prepare(binding: VoiceAutomationRunBinding) {
        check(currentStatus.state != VoiceAutomationRunState.Active) {
            "Automation run is already active"
        }
        VoiceAutomationEventValidation.validate(binding)
        val candidateWriter = VoiceAutomationEventWriter.create(noBackupFilesDir, binding.runHash)
        val monotonicMs = nextMonotonicMs()
        candidateWriter.append(
            event(
                binding = binding,
                input = VoiceAutomationEventInput(VoiceAutomationEventName.RUN_PREPARED),
                monotonicMs = monotonicMs,
            ),
        )
        this.binding = binding
        writer = candidateWriter
        lastEmittedMonotonicMs = monotonicMs
        directAppCorrelationRecorded = false
        resetRestorationState()
        currentStatus = activeStatus(binding, eventCount = 1)
    }

    @Synchronized
    override fun record(event: VoiceAutomationEventInput) {
        when (currentStatus.state) {
            VoiceAutomationRunState.Idle -> Unit
            VoiceAutomationRunState.Active -> {
                require(event.name !in setOf(
                    VoiceAutomationEventName.RUN_PREPARED,
                    VoiceAutomationEventName.RUN_FINALIZED,
                )) { "Run lifecycle boundaries are reserved for the runtime" }
                recordActive(event)
            }
            VoiceAutomationRunState.Finalized -> error("Automation run has already been finalized")
        }
    }

    @Synchronized
    override fun recordIfActiveRun(
        runHash: String,
        event: VoiceAutomationEventInput,
    ): Boolean {
        if (
            currentStatus.state != VoiceAutomationRunState.Active ||
            binding?.runHash != runHash
        ) {
            return false
        }
        require(event.name !in setOf(
            VoiceAutomationEventName.RUN_PREPARED,
            VoiceAutomationEventName.RUN_FINALIZED,
        )) { "Run lifecycle boundaries are reserved for the runtime" }
        val isDirectAppCorrelation =
            binding?.requestedTransport == VoiceAgentTransport.DirectGemini &&
                event.name == VoiceAutomationEventName.CALL_ACTIVE &&
                event.observedTransport == VoiceAgentTransport.DirectGemini &&
                event.correlationKind == VoiceAutomationCorrelationKind.APP &&
                event.correlationHash == runHash
        recordActive(
            if (isDirectAppCorrelation && directAppCorrelationRecorded) {
                event.copy(correlationKind = null, correlationHash = null)
            } else {
                event
            },
        )
        if (isDirectAppCorrelation) directAppCorrelationRecorded = true
        return true
    }

    @Synchronized
    override fun markReconnectTransportRestored(runHash: String): Boolean {
        if (
            currentStatus.state != VoiceAutomationRunState.Active ||
            binding?.runHash != runHash
        ) {
            return false
        }
        recordActive(
            VoiceAutomationEventInput(VoiceAutomationEventName.RECONNECT_TRANSPORT_RESTORED),
        )
        return true
    }

    @Synchronized
    override fun status(): VoiceAutomationStatus = currentStatus

    @Synchronized
    override fun finalizeRun(): File {
        return finalizeActiveRun()
    }

    @Synchronized
    override fun finalizeRunIfMatches(binding: VoiceAutomationRunBinding): File? {
        if (currentStatus.state != VoiceAutomationRunState.Active || this.binding != binding) return null
        return finalizeActiveRun()
    }

    private fun finalizeActiveRun(): File {
        check(currentStatus.state == VoiceAutomationRunState.Active) { "No active automation run to finalize" }
        recordActive(VoiceAutomationEventInput(VoiceAutomationEventName.RUN_FINALIZED))
        currentStatus = activeStatus(checkNotNull(binding), currentStatus.eventCount).copy(
            state = VoiceAutomationRunState.Finalized,
        )
        return checkNotNull(writer).file
    }

    @Synchronized
    override fun reset() {
        binding = null
        writer = null
        directAppCorrelationRecorded = false
        resetRestorationState()
        currentStatus = VoiceAutomationStatus(VoiceAutomationRunState.Idle)
    }

    private fun recordActive(input: VoiceAutomationEventInput) {
        when (input.name) {
            VoiceAutomationEventName.RECONNECT_STARTED -> {
                if (reconnectStarted) {
                    rejectRecordedTransition(input, "Reconnect start must be unique")
                }
                reconnectStarted = true
            }
            VoiceAutomationEventName.RECONNECT_TRANSPORT_RESTORED -> {
                if (!reconnectStarted) {
                    rejectRecordedTransition(input, "Transport restoration must follow reconnect start")
                }
                if (reconnectTransportRestored) {
                    rejectRecordedTransition(input, "Transport restoration must be unique")
                }
                reconnectTransportRestored = true
            }
            VoiceAutomationEventName.HANDOVER_STARTED -> {
                if (handoverStarted) {
                    rejectRecordedTransition(input, "Handover start must be unique")
                }
                handoverStarted = true
            }
            VoiceAutomationEventName.HANDOVER_CELLULAR_OBSERVED -> {
                if (handoverCellularObserved) {
                    rejectRecordedTransition(input, "Cellular observation must be unique")
                }
                if (!handoverStarted) {
                    rejectRecordedTransition(input, "Cellular observation must follow handover start")
                }
                handoverCellularObserved = true
            }
            VoiceAutomationEventName.HANDOVER_WIFI_RESTORED -> {
                if (handoverWifiRestored) {
                    rejectRecordedTransition(input, "Wi-Fi restoration must be unique")
                }
                if (!handoverCellularObserved) {
                    rejectRecordedTransition(input, "Wi-Fi restoration must follow cellular observation")
                }
                handoverWifiRestored = true
            }
            else -> Unit
        }
        appendActive(input)
        if (input.name == VoiceAutomationEventName.PLAYBACK_WRITTEN) {
            recordRestoredMedia(input.playbackEpoch)
        }
    }

    private fun rejectRecordedTransition(
        input: VoiceAutomationEventInput,
        message: String,
    ): Nothing {
        appendActive(input)
        error(message)
    }

    private fun appendActive(input: VoiceAutomationEventInput) {
        val activeBinding = checkNotNull(binding)
        val monotonicMs = nextMonotonicMs()
        checkNotNull(writer).append(event(activeBinding, input, monotonicMs))
        lastEmittedMonotonicMs = monotonicMs
        currentStatus = activeStatus(activeBinding, currentStatus.eventCount + 1)
    }

    private fun recordRestoredMedia(playbackEpoch: Long?) {
        if (handoverWifiRestored && reconnectTransportRestored && !handoverMediaRestored) {
            handoverMediaRestored = true
            appendActive(
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.HANDOVER_MEDIA_RESTORED,
                    playbackEpoch = checkNotNull(playbackEpoch),
                ),
            )
        }
        if (
            reconnectStarted &&
            reconnectTransportRestored &&
            (!handoverStarted || handoverMediaRestored) &&
            !reconnectMediaRestored
        ) {
            reconnectMediaRestored = true
            appendActive(
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.RECONNECT_MEDIA_RESTORED,
                    playbackEpoch = checkNotNull(playbackEpoch),
                ),
            )
        }
    }

    private fun resetRestorationState() {
        reconnectStarted = false
        reconnectTransportRestored = false
        reconnectMediaRestored = false
        handoverStarted = false
        handoverCellularObserved = false
        handoverWifiRestored = false
        handoverMediaRestored = false
    }

    private fun event(
        binding: VoiceAutomationRunBinding,
        input: VoiceAutomationEventInput,
        monotonicMs: Long,
    ) = VoiceAutomationEvent(
        monotonicMs = monotonicMs,
        wallClockMs = clock.wallClockMs(),
        runHash = binding.runHash,
        comparisonHash = binding.comparisonHash,
        requestedTransport = binding.requestedTransport,
        observedTransport = input.observedTransport,
        name = input.name,
        route = input.route,
        network = input.network,
        lifecycle = input.lifecycle,
        playbackEpoch = input.playbackEpoch,
        byteCount = input.byteCount,
        rmsActive = input.rmsActive,
        audioWindowMicros = input.audioWindowMicros,
        succeeded = input.succeeded,
        correlationKind = input.correlationKind,
        correlationHash = input.correlationHash,
        requestedModelHash = input.requestedModelHash,
        observedModelHash = input.observedModelHash,
        voiceHash = input.voiceHash,
        instructionHash = input.instructionHash,
        directAccountConfigurationHash = input.directAccountConfigurationHash,
        conversationHash = input.conversationHash,
        captureSource = input.captureSource,
        micBytes = input.micBytes,
        fixtureBytes = input.fixtureBytes,
    )

    private fun nextMonotonicMs(): Long {
        val observed = clock.monotonicMs()
        val previous = lastEmittedMonotonicMs ?: return observed
        check(previous < Long.MAX_VALUE) { "Automation monotonic timestamp exhausted" }
        return maxOf(observed, previous + 1)
    }

    private fun activeStatus(binding: VoiceAutomationRunBinding, eventCount: Long) = VoiceAutomationStatus(
        state = VoiceAutomationRunState.Active,
        runHash = binding.runHash,
        comparisonHash = binding.comparisonHash,
        requestedTransport = binding.requestedTransport,
        eventCount = eventCount,
    )
}

private object SystemVoiceAutomationClock : VoiceAutomationClock {
    override fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    override fun wallClockMs(): Long = System.currentTimeMillis()
}
