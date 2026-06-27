package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.Job
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent

internal sealed class VoiceReconnectDecision {
    data object Ignore : VoiceReconnectDecision()
    data object AlreadyPlanned : VoiceReconnectDecision()
    data object DeferredForActivation : VoiceReconnectDecision()
    data class Schedule(val plan: AutomaticReconnectPlan) : VoiceReconnectDecision()
    data class Exhausted(
        val reason: VoiceSessionStopReason,
        val attempts: Int,
        val elapsedMs: Long,
        val event: GeminiLiveEvent,
    ) : VoiceReconnectDecision()
}

internal data class VoiceReconnectCancellation(
    val job: Job?,
    val hadAutomaticReconnect: Boolean,
)

internal data class AutomaticReconnectPlan(
    val event: GeminiLiveEvent,
    val reason: VoiceSessionStopReason,
    val attempt: Int,
    val delayMs: Long,
)
