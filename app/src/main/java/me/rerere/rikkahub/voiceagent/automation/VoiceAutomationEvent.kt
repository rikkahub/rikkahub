package me.rerere.rikkahub.voiceagent.automation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.voiceagent.VoiceAgentCallEndpointType
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport

@Serializable
internal enum class VoiceAutomationEventName(val wireName: String) {
    @SerialName("run_prepared") RUN_PREPARED("run_prepared"),
    @SerialName("call_start_requested") CALL_START_REQUESTED("call_start_requested"),
    @SerialName("direct_config_attested") DIRECT_CONFIG_ATTESTED("direct_config_attested"),
    @SerialName("call_active") CALL_ACTIVE("call_active"),
    @SerialName("call_stopped") CALL_STOPPED("call_stopped"),
    @SerialName("run_finalized") RUN_FINALIZED("run_finalized"),
    @SerialName("injection_started") INJECTION_STARTED("injection_started"),
    @SerialName("injection_first_chunk") INJECTION_FIRST_CHUNK("injection_first_chunk"),
    @SerialName("injection_completed") INJECTION_COMPLETED("injection_completed"),
    @SerialName("prompt_ended") PROMPT_ENDED("prompt_ended"),
    @SerialName("capture_attested") CAPTURE_ATTESTED("capture_attested"),
    @SerialName("remote_audio_first_non_silent") REMOTE_AUDIO_FIRST_NON_SILENT("remote_audio_first_non_silent"),
    @SerialName("remote_track_attached") REMOTE_TRACK_ATTACHED("remote_track_attached"),
    @SerialName("remote_track_detached") REMOTE_TRACK_DETACHED("remote_track_detached"),
    @SerialName("playback_queued") PLAYBACK_QUEUED("playback_queued"),
    @SerialName("playback_active") PLAYBACK_ACTIVE("playback_active"),
    @SerialName("playback_stopped") PLAYBACK_STOPPED("playback_stopped"),
    @SerialName("playback_written") PLAYBACK_WRITTEN("playback_written"),
    @SerialName("playback_drained") PLAYBACK_DRAINED("playback_drained"),
    @SerialName("route_requested") ROUTE_REQUESTED("route_requested"),
    @SerialName("route_observed") ROUTE_OBSERVED("route_observed"),
    @SerialName("lifecycle_requested") LIFECYCLE_REQUESTED("lifecycle_requested"),
    @SerialName("lifecycle_observed") LIFECYCLE_OBSERVED("lifecycle_observed"),
    @SerialName("network_observed") NETWORK_OBSERVED("network_observed"),
    @SerialName("interrupt_started") INTERRUPT_STARTED("interrupt_started"),
    @SerialName("reconnect_started") RECONNECT_STARTED("reconnect_started"),
    @SerialName("reconnect_transport_restored") RECONNECT_TRANSPORT_RESTORED("reconnect_transport_restored"),
    @SerialName("reconnect_media_restored") RECONNECT_MEDIA_RESTORED("reconnect_media_restored"),
    @SerialName("handover_started") HANDOVER_STARTED("handover_started"),
    @SerialName("handover_cellular_observed") HANDOVER_CELLULAR_OBSERVED("handover_cellular_observed"),
    @SerialName("handover_wifi_restored") HANDOVER_WIFI_RESTORED("handover_wifi_restored"),
    @SerialName("handover_media_restored") HANDOVER_MEDIA_RESTORED("handover_media_restored"),
    @SerialName("dropout_started") DROPOUT_STARTED("dropout_started"),
    @SerialName("dropout_ended") DROPOUT_ENDED("dropout_ended"),
    @SerialName("failure") FAILURE("failure"),
}

@Serializable
internal enum class VoiceAutomationNetwork(val wireName: String) {
    @SerialName("wifi") WIFI("wifi"),
    @SerialName("cellular") CELLULAR("cellular"),
    @SerialName("none") NONE("none"),
}

@Serializable
internal enum class VoiceAutomationLifecycle(val wireName: String) {
    @SerialName("foreground") FOREGROUND("foreground"),
    @SerialName("background") BACKGROUND("background"),
}

@Serializable
internal enum class VoiceAutomationCorrelationKind(val wireName: String) {
    @SerialName("app") APP("app"),
    @SerialName("session") SESSION("session"),
    @SerialName("room") ROOM("room"),
    @SerialName("participant") PARTICIPANT("participant"),
    @SerialName("dispatch") DISPATCH("dispatch"),
    @SerialName("worker_event") WORKER_EVENT("worker_event"),
    @SerialName("media_state") MEDIA_STATE("media_state"),
}

internal data class VoiceAutomationEvent(
    val schemaVersion: Int = 1,
    val monotonicMs: Long,
    val wallClockMs: Long,
    val runHash: String,
    val comparisonHash: String,
    val requestedTransport: VoiceAgentTransport,
    val observedTransport: VoiceAgentTransport?,
    val name: VoiceAutomationEventName,
    val route: VoiceAgentCallEndpointType? = null,
    val network: VoiceAutomationNetwork? = null,
    val lifecycle: VoiceAutomationLifecycle? = null,
    val playbackEpoch: Long? = null,
    val byteCount: Long? = null,
    val rmsActive: Boolean? = null,
    val audioWindowMicros: Long? = null,
    val succeeded: Boolean? = null,
    val reconnectDurationMs: Long? = null,
    val failureCategory: String? = null,
    val failureMessage: String? = null,
    val correlationKind: VoiceAutomationCorrelationKind? = null,
    val correlationHash: String? = null,
    val requestedModelHash: String? = null,
    val observedModelHash: String? = null,
    val voiceHash: String? = null,
    val instructionHash: String? = null,
    val directAccountConfigurationHash: String? = null,
    val conversationHash: String? = null,
    val captureSource: String? = null,
    val micBytes: Long? = null,
    val fixtureBytes: Long? = null,
)

internal data class VoiceAutomationRunBinding(
    val runHash: String,
    val comparisonHash: String,
    val requestedTransport: VoiceAgentTransport,
)

internal data class VoiceAutomationEventInput(
    val name: VoiceAutomationEventName,
    val observedTransport: VoiceAgentTransport? = null,
    val route: VoiceAgentCallEndpointType? = null,
    val network: VoiceAutomationNetwork? = null,
    val lifecycle: VoiceAutomationLifecycle? = null,
    val playbackEpoch: Long? = null,
    val byteCount: Long? = null,
    val rmsActive: Boolean? = null,
    val audioWindowMicros: Long? = null,
    val succeeded: Boolean? = null,
    val reconnectDurationMs: Long? = null,
    val failureCategory: String? = null,
    val failureMessage: String? = null,
    val correlationKind: VoiceAutomationCorrelationKind? = null,
    val correlationHash: String? = null,
    val requestedModelHash: String? = null,
    val observedModelHash: String? = null,
    val voiceHash: String? = null,
    val instructionHash: String? = null,
    val directAccountConfigurationHash: String? = null,
    val conversationHash: String? = null,
    val captureSource: String? = null,
    val micBytes: Long? = null,
    val fixtureBytes: Long? = null,
)

internal object VoiceAutomationEventValidation {
    private val hashPattern = Regex("sha256:[0-9a-f]{64}")

    fun validate(binding: VoiceAutomationRunBinding) {
        validateHash("runHash", binding.runHash)
        validateHash("comparisonHash", binding.comparisonHash)
    }

    fun validate(event: VoiceAutomationEvent) {
        require(event.schemaVersion == 1) { "Unsupported automation event schema version" }
        require(event.monotonicMs > 0) { "monotonicMs must be positive" }
        require(event.wallClockMs > 0) { "wallClockMs must be positive" }
        validateHash("runHash", event.runHash)
        validateHash("comparisonHash", event.comparisonHash)
        require(event.playbackEpoch == null || event.playbackEpoch > 0) {
            "playbackEpoch must be positive"
        }
        require(event.byteCount == null || event.byteCount >= 0) { "byteCount must not be negative" }
        val isLiveKitPlaybackWritten =
            event.requestedTransport == VoiceAgentTransport.LiveKitExperimental &&
                event.name == VoiceAutomationEventName.PLAYBACK_WRITTEN
        if (isLiveKitPlaybackWritten) {
            require(event.byteCount != null && event.byteCount > 0) {
                "LiveKit playback_written requires positive byteCount"
            }
            requireNotNull(event.rmsActive) {
                "LiveKit playback_written requires rmsActive"
            }
            requireNotNull(event.audioWindowMicros) {
                "LiveKit playback_written requires audioWindowMicros"
            }
            require(event.audioWindowMicros > 0) {
                "audioWindowMicros must be positive"
            }
        } else {
            require(event.rmsActive == null && event.audioWindowMicros == null) {
                "RMS window fields are only allowed on LiveKit playback_written"
            }
        }
        require(event.micBytes == null || event.micBytes >= 0) { "micBytes must not be negative" }
        require(event.fixtureBytes == null || event.fixtureBytes >= 0) {
            "fixtureBytes must not be negative"
        }
        if (event.name == VoiceAutomationEventName.CAPTURE_ATTESTED) {
            require(event.captureSource in setOf("microphone", "fixture"))
            requireNotNull(event.micBytes)
            requireNotNull(event.fixtureBytes)
        } else {
            require(event.captureSource == null && event.micBytes == null && event.fixtureBytes == null) {
                "Capture fields are only allowed on capture attestation"
            }
        }
        if (
            event.requestedTransport == VoiceAgentTransport.LiveKitExperimental &&
            event.name == VoiceAutomationEventName.RECONNECT_TRANSPORT_RESTORED
        ) {
            requireNotNull(event.reconnectDurationMs)
            require(event.reconnectDurationMs in 0 until 20_000)
        } else {
            require(event.reconnectDurationMs == null)
        }

        if (event.name == VoiceAutomationEventName.FAILURE) {
            require(event.requestedTransport == VoiceAgentTransport.LiveKitExperimental)
            val expectedMessage = when (event.failureCategory) {
                "NETWORK_TIMEOUT" -> "LiveKit connection timed out after 20s"
                "WORKER_UNAVAILABLE" -> "LiveKit worker participant disconnected"
                else -> throw IllegalArgumentException("Unsupported Spec A failure category")
            }
            require(event.succeeded == false)
            require(event.failureMessage == expectedMessage)
        } else {
            require(event.failureCategory == null && event.failureMessage == null)
        }
        require((event.correlationKind == null) == (event.correlationHash == null)) {
            "correlation kind and hash must be supplied together"
        }
        event.correlationHash?.let { validateHash("correlationHash", it) }
        val configurationHashes = listOf(
            "requestedModelHash" to event.requestedModelHash,
            "observedModelHash" to event.observedModelHash,
            "voiceHash" to event.voiceHash,
            "instructionHash" to event.instructionHash,
            "directAccountConfigurationHash" to event.directAccountConfigurationHash,
            "conversationHash" to event.conversationHash,
        )
        if (event.name == VoiceAutomationEventName.DIRECT_CONFIG_ATTESTED) {
            configurationHashes.forEach { (name, value) ->
                requireNotNull(value) { "$name is required for Direct configuration attestation" }
                validateHash(name, value)
            }
        } else {
            require(configurationHashes.all { it.second == null }) {
                "configuration hashes are only allowed on Direct configuration attestation"
            }
        }
    }

    fun validateHash(name: String, value: String) {
        require(hashPattern.matches(value)) { "$name must be a SHA-256 hash" }
    }
}
