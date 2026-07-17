package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner

data class VoiceAgentRouteMetadata(
    val owner: VoiceAudioRouteOwner,
    val failure: VoiceAgentTelecomFailure? = null,
)

sealed interface VoiceAgentRouteLease {
    val metadata: VoiceAgentRouteMetadata
    val isUsable: Boolean
    fun retire()
}

internal class TelecomVoiceAgentRouteLease(
    private val attemptId: VoiceAgentTelecomAttemptId,
    private val registry: VoiceAgentTelecomCallRegistry,
) : VoiceAgentRouteLease {
    private val retirement = RetirementBarrier()

    override val metadata = VoiceAgentRouteMetadata(VoiceAudioRouteOwner.Telecom)
    override val isUsable: Boolean
        get() = registry.isOwnedAttemptActive(attemptId)

    override fun retire() = retirement.retire {
        registry.retireOwnedAttempt(attemptId)
    }
}

internal class DirectFallbackVoiceAgentRouteLease(
    failure: VoiceAgentTelecomFailure,
) : VoiceAgentRouteLease {
    override val metadata = VoiceAgentRouteMetadata(VoiceAudioRouteOwner.DirectFallback, failure)
    override val isUsable = true

    override fun retire() = Unit
}
