package me.rerere.rikkahub.voiceagent

internal data class VoiceAgentCallEndpointCandidate(
    val id: String,
    val type: VoiceAgentCallEndpointType,
    val name: String,
)

internal data class VoiceAgentCurrentCallEndpoint(
    val id: String,
    val type: VoiceAgentCallEndpointType,
)

internal enum class VoiceAgentCallEndpointType {
    Bluetooth,
    Earpiece,
    Speaker,
    WiredHeadset,
    Streaming,
    Unknown,
}

internal fun selectPreferredCallEndpoint(
    endpoints: List<VoiceAgentCallEndpointCandidate>,
): VoiceAgentCallEndpointCandidate? =
    endpoints.firstOrNull { it.type == VoiceAgentCallEndpointType.Bluetooth }

internal fun shouldRequestBluetoothCallEndpoint(
    currentEndpoint: VoiceAgentCurrentCallEndpoint?,
    requestedBluetoothEndpointId: String?,
    selectedBluetoothEndpointId: String,
): Boolean =
    currentEndpoint?.let {
        it.type != VoiceAgentCallEndpointType.Bluetooth || it.id != selectedBluetoothEndpointId
    } != false && requestedBluetoothEndpointId != selectedBluetoothEndpointId
