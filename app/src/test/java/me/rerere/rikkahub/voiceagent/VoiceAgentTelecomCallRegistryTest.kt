package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAgentTelecomCallRegistryTest {
    @Test
    fun `matching connection completes pending attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val call = FakeTelecomCall()

        assertEquals(true, registry.activate(attempt, call))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))
        assertEquals(true, registry.hasActiveConnection())
    }

    @Test
    fun `matching failure completes pending attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val failure = VoiceAgentTelecomFailure("telecom_outgoing_failed", "rejected")

        registry.fail(attempt, failure)

        assertEquals(VoiceAgentTelecomOutcome.Failed(failure), registry.awaitOutcome(attempt))
    }

    @Test
    fun `late connection after failure is disconnected`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val late = FakeTelecomCall()
        registry.fail(attempt, VoiceAgentTelecomFailure("telecom_connection_timeout", "timeout"))

        assertEquals(false, registry.activate(attempt, late))
        assertEquals(1, late.disconnectCalls)
        assertEquals(false, registry.hasActiveConnection())
    }

    @Test
    fun `begin attempt disconnects previous active call`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val first = FakeTelecomCall()
        val second = FakeTelecomCall()

        val firstAttempt = registry.beginAttempt()
        assertEquals(false, registry.hasActiveConnection())
        registry.activate(firstAttempt, first)
        assertEquals(true, registry.hasActiveConnection())

        val secondAttempt = registry.beginAttempt()
        registry.activate(secondAttempt, second)

        assertEquals(1, first.disconnectCalls)
        assertEquals(0, second.disconnectCalls)
        assertEquals(true, registry.hasActiveConnection())
    }

    @Test
    fun `clear only removes the matching active call`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val first = FakeTelecomCall()
        val second = FakeTelecomCall()

        val attempt = registry.beginAttempt()
        registry.activate(attempt, first)
        registry.clear(second)
        registry.disconnectActive()

        assertEquals(1, first.disconnectCalls)
        assertEquals(0, second.disconnectCalls)
        assertEquals(false, registry.hasActiveConnection())
    }

    @Test
    fun `disconnect active clears call before disconnect callback can reenter`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = FakeTelecomCall {
            registry.clear(it)
        }

        val attempt = registry.beginAttempt()
        registry.activate(attempt, call)
        registry.disconnectActive()
        registry.disconnectActive()

        assertEquals(1, call.disconnectCalls)
        assertEquals(false, registry.hasActiveConnection())
    }

    private class FakeTelecomCall(
        private val onDisconnect: (FakeTelecomCall) -> Unit = {},
    ) : VoiceAgentTelecomCall {
        var disconnectCalls = 0

        override fun disconnectFromApp() {
            disconnectCalls += 1
            onDisconnect(this)
        }
    }
}
