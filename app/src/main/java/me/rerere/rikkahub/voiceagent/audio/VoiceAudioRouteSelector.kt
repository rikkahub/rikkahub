package me.rerere.rikkahub.voiceagent.audio

internal data class VoiceAudioRouteDevice(
    val id: Int,
    val type: VoiceAudioRouteDeviceType,
    val name: String,
)

internal enum class VoiceAudioRouteDeviceType {
    BluetoothSco,
    BluetoothBleHeadset,
    BuiltInMic,
    WiredHeadset,
    Other,
}

internal fun selectPreferredCaptureRoute(devices: List<VoiceAudioRouteDevice>): VoiceAudioRouteDevice? =
    devices.firstOrNull { it.type == VoiceAudioRouteDeviceType.BluetoothSco }
        ?: devices.firstOrNull { it.type == VoiceAudioRouteDeviceType.BluetoothBleHeadset }
