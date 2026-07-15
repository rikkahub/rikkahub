package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VoiceAgentTelecomBoundaryTest {
    @Test
    fun `generated address parts round trip an attempt id`() {
        val attempt = VoiceAgentTelecomAttemptId(42)

        assertEquals(
            attempt,
            voiceAgentTelecomAttemptIdOrNull(
                scheme = VOICE_AGENT_CALL_URI_SCHEME,
                schemeSpecificPart = attempt.toVoiceAgentTelecomSchemeSpecificPart(),
            ),
        )
    }

    @Test
    fun `attempt parser rejects malformed address parts`() {
        val malformed = listOf(
            "other-scheme" to "voice-agent-1",
            VOICE_AGENT_CALL_URI_SCHEME.uppercase() to "voice-agent-1",
            VOICE_AGENT_CALL_URI_SCHEME to "1",
            VOICE_AGENT_CALL_URI_SCHEME to "Voice-agent-1",
            VOICE_AGENT_CALL_URI_SCHEME to "voice-agent-",
            VOICE_AGENT_CALL_URI_SCHEME to "voice-agent-0",
            VOICE_AGENT_CALL_URI_SCHEME to "voice-agent--1",
            VOICE_AGENT_CALL_URI_SCHEME to "voice-agent-+1",
            VOICE_AGENT_CALL_URI_SCHEME to "voice-agent-1x",
        )

        malformed.forEach { (scheme, schemeSpecificPart) ->
            assertEquals(
                "$scheme:$schemeSpecificPart",
                null,
                voiceAgentTelecomAttemptIdOrNull(scheme, schemeSpecificPart),
            )
        }
    }

    @Test
    fun `adapter exposes no unscoped start call API`() {
        val startCallMethods = VoiceAgentTelecomAdapter::class.java.declaredMethods
            .filter { it.name.startsWith("startCall") && !it.isSynthetic }

        assertFalse(startCallMethods.any { it.parameterCount == 0 })
    }

    @Test
    fun `malformed connection request is disconnected at boundary`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val connection = FakeTelecomCall()
        val malformedAttempt = voiceAgentTelecomAttemptIdOrNull(
            scheme = "other-scheme",
            schemeSpecificPart = "voice-agent-1",
        )

        assertFalse(
            activateVoiceAgentTelecomConnection(
                registry = registry,
                attemptId = malformedAttempt,
                connection = connection,
                makeActive = {},
            ),
        )
        assertEquals(1, connection.disconnectCalls)
    }

    @Test
    fun `outgoing failure boundary completes only matching attempt`() = kotlinx.coroutines.runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val stale = registry.beginAttempt()
        val current = registry.beginAttempt()
        val staleFailure = VoiceAgentTelecomFailure("stale", "ignored")
        val currentFailure = VoiceAgentTelecomFailure("telecom_outgoing_failed", "rejected")

        failVoiceAgentTelecomAttempt(registry, stale, staleFailure)
        failVoiceAgentTelecomAttempt(registry, current, currentFailure)

        assertEquals("telecom_attempt_superseded", failedOutcome(registry, stale).failure.diagnosticName)
        assertEquals(currentFailure, failedOutcome(registry, current).failure)
    }

    private suspend fun failedOutcome(
        registry: VoiceAgentTelecomCallRegistry,
        attemptId: VoiceAgentTelecomAttemptId,
    ): VoiceAgentTelecomOutcome.Failed =
        registry.awaitOutcome(attemptId) as VoiceAgentTelecomOutcome.Failed

    private class FakeTelecomCall : VoiceAgentTelecomCall {
        var disconnectCalls = 0

        override fun disconnectFromApp() {
            disconnectCalls += 1
        }
    }
}
