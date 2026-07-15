package me.rerere.rikkahub.voiceagent

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentAudioRouteResolverTest {
    @Test
    fun `active attempt selects Telecom`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val gateway = FakeTelecomGateway(onStart = { id ->
            registry.activate(id, ResolverFakeCall())
        })

        val result = VoiceAgentAudioRouteResolver(gateway, registry, 100).resolve()

        assertEquals(VoiceAudioRouteOwner.Telecom, result.owner)
        assertEquals(null, result.failure)
        assertTrue(registry.hasActiveConnection())
        assertAllAttemptsConsumed(registry)
    }

    @Test
    fun `registration failure selects direct fallback`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()

        val result = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(registerResult = Result.failure(IllegalStateException("denied"))),
            registry,
            100,
        ).resolve()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, result.owner)
        assertEquals("telecom_register_failed", result.failure?.diagnosticName)
        assertAllAttemptsConsumed(registry)
    }

    @Test
    fun `placement failure selects direct fallback`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()

        val result = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(startResult = Result.failure(IllegalStateException("rejected"))),
            registry,
            100,
        ).resolve()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, result.owner)
        assertEquals("telecom_start_failed", result.failure?.diagnosticName)
        assertAllAttemptsConsumed(registry)
    }

    @Test
    fun `ConnectionService rejection is preserved`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val gateway = FakeTelecomGateway(onStart = { id ->
            registry.fail(id, VoiceAgentTelecomFailure("telecom_outgoing_failed", "framework rejected"))
        })

        val result = VoiceAgentAudioRouteResolver(gateway, registry, 100).resolve()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, result.owner)
        assertEquals("telecom_outgoing_failed", result.failure?.diagnosticName)
        assertEquals("framework rejected", result.failure?.detail)
        assertAllAttemptsConsumed(registry)
    }

    @Test
    fun `timeout selects fallback and disconnects late connection`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        var attempt: VoiceAgentTelecomAttemptId? = null
        val gateway = FakeTelecomGateway(onStart = { attempt = it })

        val result = VoiceAgentAudioRouteResolver(gateway, registry, 1).resolve()
        val late = ResolverFakeCall()
        val accepted = registry.activate(requireNotNull(attempt), late)

        assertEquals(VoiceAudioRouteOwner.DirectFallback, result.owner)
        assertEquals("telecom_connection_timeout", result.failure?.diagnosticName)
        assertEquals(false, accepted)
        assertEquals(1, late.disconnectCalls)
        assertAllAttemptsConsumed(registry)
    }

    @Test
    fun `active attempt at timeout boundary retains Telecom ownership`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val gateway = FakeTelecomGateway(onStart = { attempt ->
            registry.activate(attempt, ResolverFakeCall())
        })

        val result = VoiceAgentAudioRouteResolver(gateway, registry, 0).resolve()

        assertEquals(VoiceAudioRouteOwner.Telecom, result.owner)
        assertEquals(null, result.failure)
        assertTrue(registry.hasActiveConnection())
        assertAllAttemptsConsumed(registry)
    }

    @Test
    fun `timeout waits for in-progress activation retirement before fallback`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = ResolverFakeCall()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        val events = Collections.synchronizedList(mutableListOf<String>())
        var activation: Thread? = null
        var startedAttempt: VoiceAgentTelecomAttemptId? = null
        val gateway = FakeTelecomGateway(onStart = { attempt ->
            startedAttempt = attempt
            activation = thread {
                accepted.set(
                    registry.activate(attempt, call) {
                        callbackEntered.countDown()
                        releaseCallback.await()
                        events += "setActive"
                    },
                )
            }
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))
        })

        val resolution = async(start = CoroutineStart.UNDISPATCHED) {
            VoiceAgentAudioRouteResolver(gateway, registry, 1).resolve().also {
                events += "fallback"
            }
        }

        try {
            withTimeout(1_000) {
                while (attemptPhaseName(registry, requireNotNull(startedAttempt)) != "Cancelling") {
                    yield()
                }
            }
            assertFalse(resolution.isCompleted)
            assertEquals(0, call.disconnectCalls)
        } finally {
            releaseCallback.countDown()
            activation?.join()
        }
        val result = resolution.await()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, result.owner)
        assertEquals("telecom_connection_timeout", result.failure?.diagnosticName)
        assertFalse(accepted.get())
        assertEquals(1, call.disconnectCalls)
        assertEquals(listOf("setActive", "fallback"), events)
        assertAllAttemptsConsumed(registry)
    }

    private fun assertAllAttemptsConsumed(registry: VoiceAgentTelecomCallRegistry) {
        val attemptsField = registry.javaClass.getDeclaredField("attempts").apply { isAccessible = true }
        val attempts = attemptsField.get(registry) as Map<*, *>
        assertTrue("resolver left an acknowledged attempt record", attempts.isEmpty())
    }

    private fun attemptPhaseName(
        registry: VoiceAgentTelecomCallRegistry,
        attemptId: VoiceAgentTelecomAttemptId,
    ): String? = registryLock(registry).let { lock ->
        synchronized(lock) {
            val attemptsField = registry.javaClass.getDeclaredField("attempts").apply { isAccessible = true }
            val attempts = attemptsField.get(registry) as Map<*, *>
            val record = attempts[attemptId] ?: return@synchronized null
            val phaseField = record.javaClass.getDeclaredField("phase").apply { isAccessible = true }
            phaseField.get(record).javaClass.simpleName
        }
    }

    private fun registryLock(registry: VoiceAgentTelecomCallRegistry): Any =
        requireNotNull(registry.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(registry))
}

private class FakeTelecomGateway(
    private val registerResult: Result<Unit> = Result.success(Unit),
    private val startResult: Result<Unit> = Result.success(Unit),
    private val onStart: (VoiceAgentTelecomAttemptId) -> Unit = {},
) : VoiceAgentTelecomGateway {
    override fun register(): Result<Unit> = registerResult

    override fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit> {
        onStart(attemptId)
        return startResult
    }
}

private class ResolverFakeCall : VoiceAgentTelecomCall {
    var disconnectCalls = 0

    override fun disconnectFromApp() {
        disconnectCalls += 1
    }
}
