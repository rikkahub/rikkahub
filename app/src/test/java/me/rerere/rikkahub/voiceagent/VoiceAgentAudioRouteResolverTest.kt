package me.rerere.rikkahub.voiceagent

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
        assertEquals(VoiceAgentTelecomAttemptId(1), result.telecomAttemptId)
        assertTrue(registry.hasActiveConnection())
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
        assertEquals(null, result.telecomAttemptId)
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
    }

    @Test
    fun `throwing previous-call supersession returns contained fallback and consumes replacement`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val previous = registry.beginAttempt()
        val previousCall = ThrowingResolverCall()
        registry.activate(previous, previousCall)
        registry.awaitOutcome(previous)
        val gateway = FakeTelecomGateway()

        val result = VoiceAgentAudioRouteResolver(gateway, registry, 100).resolve()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, result.owner)
        assertEquals(
            VoiceAgentTelecomFailure(
                diagnosticName = "telecom_supersession_cleanup_failed",
                detail = "framework retirement failed",
            ),
            result.failure,
        )
        assertEquals(null, result.telecomAttemptId)
        assertEquals(0, gateway.registerCalls)
        assertEquals(0, gateway.startCalls)
        assertEquals(1, previousCall.disconnectCalls)
        assertFalse(registry.hasActiveConnection())
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
        assertEquals(VoiceAgentTelecomAttemptId(1), result.telecomAttemptId)
        assertTrue(registry.hasActiveConnection())
    }

    @Test
    fun `completed outcome at timeout publication boundary selects one owner and is consumed`() = runTest {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = ResolverFakeCall()
        var attempt: VoiceAgentTelecomAttemptId? = null
        val timeout = BoundaryOutcomeTimeout()
        val resolver = VoiceAgentAudioRouteResolver(
            gateway = FakeTelecomGateway(onStart = { attempt = it }),
            registry = registry,
            timeoutMs = 1_000,
            outcomeTimeout = timeout,
        )
        val resolution = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve() }
        timeout.observationStarted.await()

        assertTrue(registry.activate(requireNotNull(attempt), call))
        runCurrent()
        assertEquals(VoiceAgentTelecomOutcome.Active, timeout.observedOutcome.await())
        assertFalse(resolution.isCompleted)

        timeout.returnTimeout.complete(Unit)
        runCurrent()
        val result = resolution.await()

        assertEquals(VoiceAudioRouteOwner.Telecom, result.owner)
        assertEquals(null, result.failure)
        assertEquals(requireNotNull(attempt), result.telecomAttemptId)
        assertTrue(registry.hasActiveConnection())
        assertEquals(0, call.disconnectCalls)
    }

    @Test
    fun `caller cancellation retires pending attempt before rethrowing`() = runTest {
        val registry = VoiceAgentTelecomCallRegistry()
        var attempt: VoiceAgentTelecomAttemptId? = null
        val cancellation = CancellationException("caller cancelled")
        val resolver = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(onStart = {
                attempt = it
                throw cancellation
            }),
            registry,
            1_000,
        )

        val thrown = runCatching { resolver.resolve() }.exceptionOrNull()
        val late = ResolverFakeCall()
        val accepted = registry.activate(requireNotNull(attempt), late)

        assertSame(cancellation, thrown)
        assertFalse(accepted)
        assertEquals(1, late.disconnectCalls)
        assertFalse(registry.hasActiveConnection())
    }

    @Test
    fun `retirement error does not replace caller cancellation`() = runTest {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = ThrowingResolverCall()
        val cancellation = CancellationException("caller cancelled after activation")
        val resolver = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(onStart = { attempt ->
                registry.activate(attempt, call)
                throw cancellation
            }),
            registry,
            1_000,
        )

        val thrown = runCatching { resolver.resolve() }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(listOf("framework retirement failed"), cancellation.suppressed.map { it.message })
        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.hasActiveConnection())
    }

    @Test
    fun `caller cancellation waits for blocked activation retirement before rethrowing`() = runTest {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = ResolverFakeCall()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        var activation: Thread? = null
        val resolver = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(onStart = { attempt ->
                activation = thread {
                    accepted.set(
                        registry.activate(attempt, call) {
                            callbackEntered.countDown()
                            releaseCallback.await()
                        },
                    )
                }
                assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))
            }),
            registry,
            1_000,
        )
        val resolution = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve() }
        val cancellation = CancellationException("caller cancelled during activation")

        resolution.cancel(cancellation)
        runCurrent()
        try {
            assertFalse(resolution.isCompleted)
            assertEquals(0, call.disconnectCalls)
        } finally {
            releaseCallback.countDown()
            activation?.join()
        }
        runCurrent()
        val thrown = runCatching { resolution.await() }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(cancellation.message, thrown?.message)
        assertFalse(accepted.get())
        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.hasActiveConnection())
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
        val gateway = FakeTelecomGateway(onStart = { attempt ->
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
            VoiceAgentAudioRouteResolver(
                gateway = gateway,
                registry = registry,
                timeoutMs = 1_000,
                outcomeTimeout = ImmediateOutcomeTimeout,
            ).resolve().also {
                events += "fallback"
            }
        }

        try {
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
    }
}

private class FakeTelecomGateway(
    private val registerResult: Result<Unit> = Result.success(Unit),
    private val startResult: Result<Unit> = Result.success(Unit),
    private val onStart: (VoiceAgentTelecomAttemptId) -> Unit = {},
) : VoiceAgentTelecomGateway {
    var registerCalls = 0
    var startCalls = 0

    override fun register(): Result<Unit> {
        registerCalls += 1
        return registerResult
    }

    override fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit> {
        startCalls += 1
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

private class ThrowingResolverCall : VoiceAgentTelecomCall {
    var disconnectCalls = 0

    override fun disconnectFromApp() {
        disconnectCalls += 1
        error("framework retirement failed")
    }
}

private class BoundaryOutcomeTimeout : VoiceAgentTelecomOutcomeTimeout {
    val observationStarted = CompletableDeferred<Unit>()
    val observedOutcome = CompletableDeferred<VoiceAgentTelecomOutcome>()
    val returnTimeout = CompletableDeferred<Unit>()

    override suspend fun awaitOutcome(
        timeoutMs: Long,
        observe: suspend () -> VoiceAgentTelecomOutcome,
    ): VoiceAgentTelecomOutcome? {
        observationStarted.complete(Unit)
        observedOutcome.complete(observe())
        returnTimeout.await()
        return null
    }
}

private object ImmediateOutcomeTimeout : VoiceAgentTelecomOutcomeTimeout {
    override suspend fun awaitOutcome(
        timeoutMs: Long,
        observe: suspend () -> VoiceAgentTelecomOutcome,
    ): VoiceAgentTelecomOutcome? = null
}
