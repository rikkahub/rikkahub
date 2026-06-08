package me.rerere.rikkahub.voiceagent

internal enum class VoiceAgentStartGate {
    Ready,
    NeedsMicrophonePermission,
    NeedsNotificationPermission,
}

internal fun voiceAgentStartGate(
    hasMicrophonePermission: Boolean,
    hasNotificationPermission: Boolean,
): VoiceAgentStartGate =
    when {
        !hasMicrophonePermission -> VoiceAgentStartGate.NeedsMicrophonePermission
        !hasNotificationPermission -> VoiceAgentStartGate.NeedsNotificationPermission
        else -> VoiceAgentStartGate.Ready
    }
