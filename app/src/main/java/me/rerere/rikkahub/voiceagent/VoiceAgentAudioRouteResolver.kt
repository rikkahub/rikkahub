package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun interface VoiceAgentTelecomOutcomeTimeout {
    suspend fun awaitOutcome(
        timeoutMs: Long,
        observe: suspend () -> VoiceAgentTelecomOutcome,
    ): VoiceAgentTelecomOutcome?
}

private object DefaultVoiceAgentTelecomOutcomeTimeout : VoiceAgentTelecomOutcomeTimeout {
    override suspend fun awaitOutcome(
        timeoutMs: Long,
        observe: suspend () -> VoiceAgentTelecomOutcome,
    ): VoiceAgentTelecomOutcome? = withTimeoutOrNull(timeoutMs) { observe() }
}

class VoiceAgentAudioRouteResolver internal constructor(
    private val gateway: VoiceAgentTelecomGateway,
    private val registry: VoiceAgentTelecomCallRegistry,
    private val timeoutMs: Long,
    private val outcomeTimeout: VoiceAgentTelecomOutcomeTimeout,
) {
    constructor(
        gateway: VoiceAgentTelecomGateway,
        registry: VoiceAgentTelecomCallRegistry,
        timeoutMs: Long = 3_000L,
    ) : this(gateway, registry, timeoutMs, DefaultVoiceAgentTelecomOutcomeTimeout)

    suspend fun resolve(): VoiceAgentRouteLease {
        val attempt = try {
            registry.beginAttempt()
        } catch (error: VoiceAgentTelecomAttemptStartException) {
            val outcome = withContext(NonCancellable) {
                registry.awaitOutcome(error.attemptId)
            }
            val failure = (outcome as VoiceAgentTelecomOutcome.Failed).failure
            return DirectFallbackVoiceAgentRouteLease(failure)
        }
        try {
            gateway.register().exceptionOrNull()?.let {
                return fallback(attempt, "telecom_register_failed", it)
            }
            gateway.startCall(attempt).exceptionOrNull()?.let {
                return fallback(attempt, "telecom_start_failed", it)
            }
            return when (val outcome = outcomeTimeout.awaitOutcome(timeoutMs) { registry.observeOutcome(attempt) }) {
                VoiceAgentTelecomOutcome.Active -> {
                    registry.acknowledgeOutcome(attempt)
                    TelecomVoiceAgentRouteLease(attempt, registry)
                }
                is VoiceAgentTelecomOutcome.Failed -> {
                    registry.acknowledgeOutcome(attempt)
                    DirectFallbackVoiceAgentRouteLease(outcome.failure)
                }
                null -> fallback(
                    attempt,
                    "telecom_connection_timeout",
                    IllegalStateException("Android Telecom did not become active within ${timeoutMs}ms"),
                )
            }
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                val retirementError = runCatching {
                    registry.retireAttempt(
                        attempt,
                        VoiceAgentTelecomFailure(
                            diagnosticName = "telecom_resolution_cancelled",
                            detail = cancellation.message ?: cancellation.javaClass.simpleName,
                        ),
                    )
                }.exceptionOrNull()
                val acknowledgementError = runCatching {
                    registry.awaitOutcome(attempt)
                }.exceptionOrNull()
                retirementError?.let(cancellation::addSuppressed)
                acknowledgementError?.let(cancellation::addSuppressed)
            }
            throw cancellation
        }
    }

    private suspend fun fallback(
        attempt: VoiceAgentTelecomAttemptId,
        name: String,
        error: Throwable,
    ): VoiceAgentRouteLease {
        val failure = VoiceAgentTelecomFailure(name, error.message ?: error.javaClass.simpleName)
        registry.fail(attempt, failure)
        val retired = withContext(NonCancellable) {
            registry.awaitOutcome(attempt)
        }
        return when (retired) {
            VoiceAgentTelecomOutcome.Active -> TelecomVoiceAgentRouteLease(attempt, registry)
            is VoiceAgentTelecomOutcome.Failed -> DirectFallbackVoiceAgentRouteLease(retired.failure)
        }
    }
}
