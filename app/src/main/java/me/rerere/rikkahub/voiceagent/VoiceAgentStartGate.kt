package me.rerere.rikkahub.voiceagent

internal enum class VoiceAgentStartGate {
    Ready,
    NeedsMicrophonePermission,
}

internal fun voiceAgentStartGate(hasMicrophonePermission: Boolean): VoiceAgentStartGate =
    if (hasMicrophonePermission) {
        VoiceAgentStartGate.Ready
    } else {
        VoiceAgentStartGate.NeedsMicrophonePermission
    }
