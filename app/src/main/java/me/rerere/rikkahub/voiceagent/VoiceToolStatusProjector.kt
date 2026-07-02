package me.rerere.rikkahub.voiceagent

fun summarizeVoiceToolStatus(
    toolCalls: Map<String, VoiceToolStatus>,
    fallback: VoiceToolStatus,
): VoiceToolStatus {
    return when (fallback) {
        is VoiceToolStatus.CallingHermes -> fallback
        is VoiceToolStatus.QueuedHermes -> fallback
        else -> toolCalls.values.filterIsInstance<VoiceToolStatus.CallingHermes>().firstOrNull()
            ?: toolCalls.values.filterIsInstance<VoiceToolStatus.QueuedHermes>().firstOrNull()
            ?: toolCalls.values.filterIsInstance<VoiceToolStatus.HermesFailed>().firstOrNull()
            ?: fallback
    }
}

fun VoiceToolStatus.isTerminalHermesToolStatus(): Boolean = when (this) {
    is VoiceToolStatus.HermesAnswered,
    is VoiceToolStatus.HermesFailed,
        -> true
    VoiceToolStatus.Idle,
    is VoiceToolStatus.QueuedHermes,
    is VoiceToolStatus.CallingHermes,
        -> false
}
