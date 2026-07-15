package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner

data class VoiceAgentAudioRouteResolution(
    val owner: VoiceAudioRouteOwner,
    val failure: VoiceAgentTelecomFailure? = null,
)

class VoiceAgentAudioRouteResolver(
    private val gateway: VoiceAgentTelecomGateway,
    private val registry: VoiceAgentTelecomCallRegistry,
    private val timeoutMs: Long = 3_000L,
) {
    suspend fun resolve(): VoiceAgentAudioRouteResolution {
        val attempt = registry.beginAttempt()
        gateway.register().exceptionOrNull()?.let {
            return fallback(attempt, "telecom_register_failed", it)
        }
        gateway.startCall(attempt).exceptionOrNull()?.let {
            return fallback(attempt, "telecom_start_failed", it)
        }
        return when (val outcome = withTimeoutOrNull(timeoutMs) { registry.awaitOutcome(attempt) }) {
            VoiceAgentTelecomOutcome.Active -> VoiceAgentAudioRouteResolution(VoiceAudioRouteOwner.Telecom)
            is VoiceAgentTelecomOutcome.Failed -> VoiceAgentAudioRouteResolution(
                VoiceAudioRouteOwner.DirectFallback,
                outcome.failure,
            )
            null -> fallback(
                attempt,
                "telecom_connection_timeout",
                IllegalStateException("Android Telecom did not become active within ${timeoutMs}ms"),
            )
        }
    }

    private suspend fun fallback(
        attempt: VoiceAgentTelecomAttemptId,
        name: String,
        error: Throwable,
    ): VoiceAgentAudioRouteResolution {
        val failure = VoiceAgentTelecomFailure(name, error.message ?: error.javaClass.simpleName)
        registry.fail(attempt, failure)
        val retired = withContext(NonCancellable) {
            registry.awaitOutcome(attempt)
        }
        return when (retired) {
            VoiceAgentTelecomOutcome.Active -> VoiceAgentAudioRouteResolution(VoiceAudioRouteOwner.Telecom)
            is VoiceAgentTelecomOutcome.Failed -> VoiceAgentAudioRouteResolution(
                VoiceAudioRouteOwner.DirectFallback,
                retired.failure,
            )
        }
    }
}
