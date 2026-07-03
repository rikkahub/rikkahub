package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.hermes.HermesQueueSnapshot
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStatus

sealed interface VoiceSessionStatus {
    data object Idle : VoiceSessionStatus
    data object PreparingContext : VoiceSessionStatus
    data object RequestingToken : VoiceSessionStatus
    data object ConnectingGemini : VoiceSessionStatus
    data object Connected : VoiceSessionStatus
    data object Reconnecting : VoiceSessionStatus
    data object Ending : VoiceSessionStatus
    data object Ended : VoiceSessionStatus
    data class Error(val message: String) : VoiceSessionStatus
}

sealed interface VoiceAudioStatus {
    data object Listening : VoiceAudioStatus
    data object UserSpeaking : VoiceAudioStatus
    data object AssistantSpeaking : VoiceAudioStatus
    data object Muted : VoiceAudioStatus
    data object PlaybackSuppressed : VoiceAudioStatus
}

sealed interface VoiceToolStatus {
    data object Idle : VoiceToolStatus
    data class QueuedHermes(val callId: String, val jobId: String) : VoiceToolStatus
    data class CallingHermes(val callId: String, val elapsedMs: Long = 0L) : VoiceToolStatus
    data class HermesAnswered(val callId: String, val elapsedMs: Long) : VoiceToolStatus
    data class HermesFailed(val callId: String, val message: String) : VoiceToolStatus
}

data class VoiceHermesQueueUiStatus(
    val activeCount: Int = 0,
    val completedWaitingCount: Int = 0,
    val failedWaitingCount: Int = 0,
    val expiredWaitingCount: Int = 0,
    val canceledWaitingCount: Int = 0,
    val announcedTerminalCount: Int = 0,
) {
    val hasVisibleWork: Boolean
        get() = activeCount > 0 ||
            completedWaitingCount > 0 ||
            failedWaitingCount > 0 ||
            expiredWaitingCount > 0 ||
            canceledWaitingCount > 0

    companion object {
        fun fromSnapshot(snapshot: HermesQueueSnapshot): VoiceHermesQueueUiStatus {
            return VoiceHermesQueueUiStatus(
                activeCount = snapshot.active.size,
                completedWaitingCount = snapshot.unannouncedTerminal.countStatus(HermesQueueStatus.Complete),
                failedWaitingCount = snapshot.unannouncedTerminal.countStatus(HermesQueueStatus.Failed),
                expiredWaitingCount = snapshot.unannouncedTerminal.countStatus(HermesQueueStatus.Expired),
                canceledWaitingCount = snapshot.unannouncedTerminal.countStatus(HermesQueueStatus.Canceled),
                announcedTerminalCount = snapshot.announcedTerminal.size,
            )
        }
    }
}

private fun List<me.rerere.rikkahub.voiceagent.hermes.HermesQueueRecord>.countStatus(
    status: HermesQueueStatus,
): Int = count { it.status == status }

sealed interface VoicePersistenceStatus {
    data object Idle : VoicePersistenceStatus
    data object Saving : VoicePersistenceStatus
    data object Saved : VoicePersistenceStatus
    data class SaveFailed(val message: String) : VoicePersistenceStatus
}

sealed interface VoiceCallStatus {
    data object Idle : VoiceCallStatus
    data object ForegroundStarting : VoiceCallStatus
    data object BackgroundCapable : VoiceCallStatus
    data class Degraded(val message: String) : VoiceCallStatus
    data object Ending : VoiceCallStatus
    data object Ended : VoiceCallStatus
}

data class VoiceAgentUiState(
    val session: VoiceSessionStatus = VoiceSessionStatus.Idle,
    val audio: VoiceAudioStatus = VoiceAudioStatus.Listening,
    val tool: VoiceToolStatus = VoiceToolStatus.Idle,
    val call: VoiceCallStatus = VoiceCallStatus.Idle,
    val toolCalls: Map<String, VoiceToolStatus> = emptyMap(),
    val hermesQueue: VoiceHermesQueueUiStatus = VoiceHermesQueueUiStatus(),
    val persistence: VoicePersistenceStatus = VoicePersistenceStatus.Idle,
    val inputTranscript: String = "",
    val outputTranscript: String = "",
    val traceId: String = "",
    val error: String? = null,
    val diagnostics: List<VoiceDiagnosticLine> = emptyList(),
)

data class VoiceDiagnosticLine(
    val name: String,
    val detail: String,
    val at: String,
)

sealed interface VoiceAgentEvent {
    data object UserInterrupted : VoiceAgentEvent
    data class SessionError(val message: String) : VoiceAgentEvent
}

fun VoiceAgentUiState.reduce(event: VoiceAgentEvent): VoiceAgentUiState = when (event) {
    VoiceAgentEvent.UserInterrupted -> copy(audio = VoiceAudioStatus.PlaybackSuppressed)
    is VoiceAgentEvent.SessionError -> copy(
        session = VoiceSessionStatus.Error(event.message),
        error = event.message,
    )
}
