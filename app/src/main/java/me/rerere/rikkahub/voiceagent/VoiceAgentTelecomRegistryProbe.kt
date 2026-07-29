package me.rerere.rikkahub.voiceagent

internal fun interface VoiceAgentTelecomRegistryProbe {
    fun onEvent(event: VoiceAgentTelecomRegistryProbeEvent)
}

internal sealed interface VoiceAgentTelecomRegistryProbeEvent {
    data class ActivationOutcomeSelected(
        val attemptId: VoiceAgentTelecomAttemptId,
        val outcome: VoiceAgentTelecomOutcome,
    ) : VoiceAgentTelecomRegistryProbeEvent

    data object FailedRetirementResultPublishing : VoiceAgentTelecomRegistryProbeEvent

    data object FailedRetirementResultPublished : VoiceAgentTelecomRegistryProbeEvent

    data object FailedRetirementResultPublishedBeforeFinalization : VoiceAgentTelecomRegistryProbeEvent

    data object RouteRetirementJoining : VoiceAgentTelecomRegistryProbeEvent

    data object ActiveOutcomeClaimed : VoiceAgentTelecomRegistryProbeEvent

    data object UndeliveredRouteRetryResultPublishing : VoiceAgentTelecomRegistryProbeEvent

    data object UndeliveredCleanupFailurePublishing : VoiceAgentTelecomRegistryProbeEvent
}

internal object NoOpVoiceAgentTelecomRegistryProbe : VoiceAgentTelecomRegistryProbe {
    override fun onEvent(event: VoiceAgentTelecomRegistryProbeEvent) = Unit
}
