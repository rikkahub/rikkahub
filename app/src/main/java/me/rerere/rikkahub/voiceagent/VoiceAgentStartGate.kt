package me.rerere.rikkahub.voiceagent

internal enum class VoiceAgentStartGate {
    Ready,
    NeedsMicrophonePermission,
    NeedsBluetoothPermission,
    NeedsNotificationPermission,
}

internal fun voiceAgentStartGate(
    hasMicrophonePermission: Boolean,
    hasBluetoothConnectPermission: Boolean,
    hasNotificationPermission: Boolean,
): VoiceAgentStartGate =
    when {
        !hasMicrophonePermission -> VoiceAgentStartGate.NeedsMicrophonePermission
        !hasBluetoothConnectPermission -> VoiceAgentStartGate.NeedsBluetoothPermission
        !hasNotificationPermission -> VoiceAgentStartGate.NeedsNotificationPermission
        else -> VoiceAgentStartGate.Ready
    }
