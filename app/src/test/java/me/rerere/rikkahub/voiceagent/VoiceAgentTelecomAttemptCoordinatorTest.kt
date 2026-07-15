package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAgentTelecomAttemptCoordinatorTest {
    @Test
    fun `start places and observes the same attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        var placedAttempt: VoiceAgentTelecomAttemptId? = null
        val outcomes = mutableListOf<VoiceAgentTelecomOutcome>()
        val coordinator = VoiceAgentTelecomAttemptCoordinator(
            registry = registry,
            scope = this,
            startCall = { attempt ->
                placedAttempt = attempt
                Result.success(Unit)
            },
        )

        val attempt = coordinator.start(isCurrent = { true }, onOutcome = outcomes::add)
        registry.activate(attempt, FakeTelecomCall())
        yield()

        assertEquals(attempt, placedAttempt)
        assertEquals(listOf(VoiceAgentTelecomOutcome.Active), outcomes)
    }

    @Test
    fun `immediate placement failure fails and observes the same attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val outcomes = mutableListOf<VoiceAgentTelecomOutcome>()
        val coordinator = VoiceAgentTelecomAttemptCoordinator(
            registry = registry,
            scope = this,
            startCall = { Result.failure(IllegalStateException("denied")) },
        )

        val attempt = coordinator.start(isCurrent = { true }, onOutcome = outcomes::add)
        yield()

        val failure = VoiceAgentTelecomFailure("telecom_start_failed", "denied")
        assertEquals(listOf(VoiceAgentTelecomOutcome.Failed(failure)), outcomes)
        assertAttemptWasConsumed(registry, attempt)
    }

    @Test
    fun `retire suppresses end-stale outcome observer`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val outcomes = mutableListOf<VoiceAgentTelecomOutcome>()
        val coordinator = VoiceAgentTelecomAttemptCoordinator(
            registry = registry,
            scope = this,
            startCall = { Result.success(Unit) },
        )

        val attempt = coordinator.start(isCurrent = { true }, onOutcome = outcomes::add)
        coordinator.retire()
        val stale = FakeTelecomCall()
        registry.activate(attempt, stale)
        yield()

        assertEquals(1, stale.disconnectCalls)
        assertEquals(emptyList<VoiceAgentTelecomOutcome>(), outcomes)
        assertAttemptWasConsumed(registry, attempt)
    }

    @Test
    fun `current predicate suppresses generation-stale outcome`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val outcomes = mutableListOf<VoiceAgentTelecomOutcome>()
        val coordinator = VoiceAgentTelecomAttemptCoordinator(
            registry = registry,
            scope = this,
            startCall = { Result.success(Unit) },
        )

        val attempt = coordinator.start(isCurrent = { false }, onOutcome = outcomes::add)
        registry.activate(attempt, FakeTelecomCall())
        yield()

        assertEquals(emptyList<VoiceAgentTelecomOutcome>(), outcomes)
    }

    private suspend fun assertAttemptWasConsumed(
        registry: VoiceAgentTelecomCallRegistry,
        attemptId: VoiceAgentTelecomAttemptId,
    ) {
        val error = runCatching { registry.awaitOutcome(attemptId) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
    }

    private class FakeTelecomCall : VoiceAgentTelecomCall {
        var disconnectCalls = 0

        override fun disconnectFromApp() {
            disconnectCalls += 1
        }
    }
}
