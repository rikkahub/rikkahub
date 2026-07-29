package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner

data class VoiceAgentRouteMetadata(
    val owner: VoiceAudioRouteOwner,
    val failure: VoiceAgentTelecomFailure? = null,
)

sealed interface VoiceAgentRouteResolution {
    data class Resolved(
        val lease: VoiceAgentRouteLease,
    ) : VoiceAgentRouteResolution

    data class Superseded(
        val metadata: VoiceAgentRouteMetadata,
    ) : VoiceAgentRouteResolution

    data class CleanupFailed(
        val error: Throwable,
    ) : VoiceAgentRouteResolution
}

sealed interface VoiceAgentRouteLease {
    val metadata: VoiceAgentRouteMetadata
    val isUsable: Boolean
    fun retire()
}

internal sealed interface UndeliveredRouteRetirement {
    data object Retired : UndeliveredRouteRetirement

    data class Retained(
        val error: Throwable,
    ) : UndeliveredRouteRetirement
}

internal class UndeliveredRouteRetirementOwner internal constructor(
    internal val lease: TelecomVoiceAgentRouteLease,
    internal val attempt: SynchronousAttemptResult = SynchronousAttemptResult(),
) {
    private val completion = CompletableDeferred<Result<Unit>>()
    @Volatile
    private var schedulingRejected = false

    internal fun markSchedulingRejected() {
        schedulingRejected = true
    }

    internal fun wasSchedulingRejected(): Boolean = schedulingRejected

    internal fun publishAttemptAndCompletion(result: Result<Unit>) {
        attempt.publish(result)
        publishCompletion(result)
    }

    internal fun publishCompletion(result: Result<Unit>) {
        check(completion.complete(result)) { "Undelivered route cleanup claim was already completed" }
    }

    internal suspend fun awaitResult(): Result<Unit> = completion.await()
}

internal class TelecomVoiceAgentRouteLease(
    private val attemptId: VoiceAgentTelecomAttemptId,
    private val registry: VoiceAgentTelecomCallRegistry,
) : VoiceAgentRouteLease {
    private val retirement = RetryableRetirement()

    override val metadata = VoiceAgentRouteMetadata(VoiceAudioRouteOwner.Telecom)
    override val isUsable: Boolean
        get() = registry.isOwnedAttemptActive(attemptId)

    override fun retire() = retirement.retire {
        registry.retireOwnedAttempt(attemptId, this)
    }

    internal fun claimUndeliveredCleanup(): UndeliveredRouteCleanupAcquisition =
        registry.claimUndeliveredRouteCleanup(attemptId, this)

    internal fun executeUndeliveredCleanup(acquisition: UndeliveredRouteCleanupAcquisition) {
        val claim = acquisition.claim
        var step = acquisition.step
        while (true) {
            when (val current = step) {
                UndeliveredRouteCleanupStep.Complete -> {
                    claim.publishAttemptAndCompletion(Result.success(Unit))
                    return
                }
                UndeliveredRouteCleanupStep.Execute -> {
                    val result = runCatching {
                        retirement.retire {
                            registry.retireClaimedUndeliveredRoute(attemptId, this, claim)
                        }
                    }
                    registry.completeUndeliveredRouteCleanup(attemptId, this, claim, result)
                    return
                }
                is UndeliveredRouteCleanupStep.JoinRetirement -> {
                    current.attempt.awaitResult()
                }
                is UndeliveredRouteCleanupStep.JoinFailurePublication -> {
                    current.publication.awaitResult()
                }
            }
            step = registry.continueClaimedUndeliveredRouteCleanup(attemptId, this, claim)
        }
    }

    internal fun rejectUndeliveredCleanupScheduling(
        claim: UndeliveredRouteRetirementOwner,
        error: Throwable,
    ) {
        registry.rejectUndeliveredRouteCleanup(attemptId, this, claim, error)
    }

    fun retireUndelivered(): UndeliveredRouteRetirement {
        val cleanupError = runCatching(::retire).exceptionOrNull()
            ?: return UndeliveredRouteRetirement.Retired
        registry.retainUndeliveredRouteLease(attemptId, this, cleanupError)
        return UndeliveredRouteRetirement.Retained(cleanupError)
    }
}

internal class DirectFallbackVoiceAgentRouteLease(
    failure: VoiceAgentTelecomFailure,
) : VoiceAgentRouteLease {
    override val metadata = VoiceAgentRouteMetadata(VoiceAudioRouteOwner.DirectFallback, failure)
    override val isUsable = true

    override fun retire() = Unit
}

internal fun VoiceAgentRouteLease.retireUndelivered(): UndeliveredRouteRetirement = when (this) {
    is TelecomVoiceAgentRouteLease -> retireUndelivered()
    is DirectFallbackVoiceAgentRouteLease -> {
        retire()
        UndeliveredRouteRetirement.Retired
    }
}
