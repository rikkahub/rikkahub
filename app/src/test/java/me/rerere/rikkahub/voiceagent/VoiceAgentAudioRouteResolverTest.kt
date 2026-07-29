package me.rerere.rikkahub.voiceagent

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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
        val telecomCall = ResolverFakeCall()
        var attempt: VoiceAgentTelecomAttemptId? = null
        val gateway = FakeTelecomGateway(onStart = { id ->
            attempt = id
            registry.activate(id, telecomCall)
        })

        val lease = VoiceAgentAudioRouteResolver(gateway, registry, 100).resolve().requireResolvedLease()

        assertEquals(VoiceAudioRouteOwner.Telecom, lease.metadata.owner)
        assertEquals(null, lease.metadata.failure)
        assertTrue(registry.isOwnedAttemptActive(requireNotNull(attempt)))
        lease.retire()
        assertEquals(1, telecomCall.disconnectCalls)
    }

    @Test
    fun `registration failure selects direct fallback`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()

        val lease = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(registerResult = Result.failure(IllegalStateException("denied"))),
            registry,
            100,
        ).resolve().requireResolvedLease()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, lease.metadata.owner)
        assertEquals("telecom_register_failed", lease.metadata.failure?.diagnosticName)
        lease.retire()
    }

    @Test
    fun `fallback retirement cannot affect a newer Telecom attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val lease = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(registerResult = Result.failure(IllegalStateException("denied"))),
            registry,
            100,
        ).resolve().requireResolvedLease()
        val newerAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        val newerCall = ResolverFakeCall()
        assertTrue(registry.activate(newerAttempt, newerCall))
        val newerLease = registry.consumeActiveOutcome(newerAttempt).requireResolvedLease()

        lease.retire()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, lease.metadata.owner)
        assertEquals(0, newerCall.disconnectCalls)
        assertTrue(registry.isOwnedAttemptActive(newerAttempt))
        newerLease.retire()
    }

    @Test
    fun `placement failure selects direct fallback`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()

        val lease = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(startResult = Result.failure(IllegalStateException("rejected"))),
            registry,
            100,
        ).resolve().requireResolvedLease()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, lease.metadata.owner)
        assertEquals("telecom_start_failed", lease.metadata.failure?.diagnosticName)
    }

    @Test
    fun `throwing pre-lease supersession stays owned ahead of replacement allocation`() = runBlocking {
        val cleanupFailure = AtomicReference<Throwable?>(
            IllegalStateException("framework retirement failed"),
        )
        val registry = VoiceAgentTelecomCallRegistry()
        val previous = registry.beginAttempt().requireAllocatedAttemptId()
        val previousCall = CallbackFaithfulResolverCall(registry, cleanupFailure)
        registry.activate(previous, previousCall)
        registry.awaitOutcome(previous)
        val gateway = FakeTelecomGateway()

        val failure = runCatching {
            VoiceAgentAudioRouteResolver(gateway, registry, 100).resolve()
        }.exceptionOrNull()

        assertSame(cleanupFailure.get(), failure)
        assertEquals(0, gateway.registerCalls)
        assertEquals(0, gateway.startCalls)
        assertEquals(1, previousCall.disconnectCalls.get())
        assertFalse(registry.isOwnedAttemptActive(previous))
        assertFalse(registry.isOwnedAttemptActive(VoiceAgentTelecomAttemptId(previous.value + 1)))

        cleanupFailure.set(null)
        val replacementCall = ResolverFakeCall()
        val replacementGateway = FakeTelecomGateway(onStart = { attempt ->
            assertEquals(previous.value + 1, attempt.value)
            assertTrue(registry.activate(attempt, replacementCall))
        })

        val lease = VoiceAgentAudioRouteResolver(replacementGateway, registry, 100).resolve().requireResolvedLease()

        assertEquals(VoiceAudioRouteOwner.Telecom, lease.metadata.owner)
        assertEquals(2, previousCall.disconnectCalls.get())
        assertEquals(1, replacementGateway.registerCalls)
        assertEquals(1, replacementGateway.startCalls)
        assertEquals(0, replacementCall.disconnectCalls)
        lease.retire()
    }

    @Test
    fun `begin joins concurrent pre-lease retirement before allocating replacement`() = runBlocking {
        val cleanupFailure = IllegalStateException("concurrent predecessor cleanup failed")
        val cleanupFailureRef = AtomicReference<Throwable?>(cleanupFailure)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry()
        val previous = registry.beginAttempt().requireAllocatedAttemptId()
        val previousCall = CallbackFaithfulResolverCall(
            registry = registry,
            cleanupFailure = cleanupFailureRef,
            onCleanup = {
                cleanupEntered.countDown()
                check(releaseCleanup.await(1, TimeUnit.SECONDS)) {
                    "concurrent predecessor cleanup was not released"
                }
            },
        )
        assertTrue(registry.activate(previous, previousCall))
        registry.awaitOutcome(previous)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val retirement = executor.submit<Throwable?> {
                runCatching {
                    registry.retireAttempt(
                        previous,
                        VoiceAgentTelecomFailure(
                            diagnosticName = "telecom_resolution_cancelled",
                            detail = "concurrent cancellation",
                        ),
                    )
                }.exceptionOrNull()
            }
            check(cleanupEntered.await(1, TimeUnit.SECONDS)) {
                "concurrent predecessor cleanup did not start"
            }
            val beginThread = AtomicReference<Thread>()
            val begin = executor.submit<Throwable?> {
                beginThread.set(Thread.currentThread())
                runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull()
            }

            val joinDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (beginThread.get()?.state != Thread.State.WAITING) {
                check(System.nanoTime() < joinDeadline) {
                    "begin caller did not join concurrent predecessor cleanup"
                }
                Thread.yield()
            }
            assertFalse(begin.isDone)
            releaseCleanup.countDown()

            assertSame(cleanupFailure, retirement.get(1, TimeUnit.SECONDS))
            assertSame(cleanupFailure, begin.get(1, TimeUnit.SECONDS))
            assertEquals(1, previousCall.disconnectCalls.get())
            assertFalse(registry.isOwnedAttemptActive(VoiceAgentTelecomAttemptId(previous.value + 1)))

            cleanupFailureRef.set(null)
            val replacementCall = ResolverFakeCall()
            val gateway = FakeTelecomGateway(onStart = { attempt ->
                assertEquals(previous.value + 1, attempt.value)
                assertTrue(registry.activate(attempt, replacementCall))
            })
            val lease = VoiceAgentAudioRouteResolver(gateway, registry, 100).resolve().requireResolvedLease()

            assertEquals(VoiceAudioRouteOwner.Telecom, lease.metadata.owner)
            assertEquals(2, previousCall.disconnectCalls.get())
            assertEquals(1, gateway.registerCalls)
            assertEquals(1, gateway.startCalls)
            lease.retire()
        } finally {
            releaseCleanup.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `ConnectionService rejection is preserved`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val gateway = FakeTelecomGateway(onStart = { id ->
            registry.fail(id, VoiceAgentTelecomFailure("telecom_outgoing_failed", "framework rejected"))
        })

        val lease = VoiceAgentAudioRouteResolver(gateway, registry, 100).resolve().requireResolvedLease()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, lease.metadata.owner)
        assertEquals("telecom_outgoing_failed", lease.metadata.failure?.diagnosticName)
        assertEquals("framework rejected", lease.metadata.failure?.detail)
    }

    @Test
    fun `timeout selects fallback and disconnects late connection`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        var attempt: VoiceAgentTelecomAttemptId? = null
        val gateway = FakeTelecomGateway(onStart = { attempt = it })

        val lease = VoiceAgentAudioRouteResolver(gateway, registry, 1).resolve().requireResolvedLease()
        val late = ResolverFakeCall()
        val accepted = registry.activate(requireNotNull(attempt), late)

        assertEquals(VoiceAudioRouteOwner.DirectFallback, lease.metadata.owner)
        assertEquals("telecom_connection_timeout", lease.metadata.failure?.diagnosticName)
        assertEquals(false, accepted)
        assertEquals(1, late.disconnectCalls)
    }

    @Test
    fun `active attempt at timeout boundary retains Telecom ownership`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val telecomCall = ResolverFakeCall()
        var attempt: VoiceAgentTelecomAttemptId? = null
        val gateway = FakeTelecomGateway(onStart = { id ->
            attempt = id
            registry.activate(id, telecomCall)
        })

        val lease = VoiceAgentAudioRouteResolver(gateway, registry, 0).resolve().requireResolvedLease()

        assertEquals(VoiceAudioRouteOwner.Telecom, lease.metadata.owner)
        assertEquals(null, lease.metadata.failure)
        assertTrue(registry.isOwnedAttemptActive(requireNotNull(attempt)))
        lease.retire()
        assertEquals(1, telecomCall.disconnectCalls)
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
        val lease = resolution.await().requireResolvedLease()

        assertEquals(VoiceAudioRouteOwner.Telecom, lease.metadata.owner)
        assertEquals(null, lease.metadata.failure)
        assertTrue(registry.isOwnedAttemptActive(requireNotNull(attempt)))
        assertEquals(0, call.disconnectCalls)
        lease.retire()
        assertEquals(1, call.disconnectCalls)
    }

    @Test
    fun `framework failure during activation stays dirty until exact cleanup precedes replacement`() = runBlocking {
        val firstFailure = IllegalStateException("activation cleanup failed")
        val secondFailure = IllegalArgumentException("activation cleanup retry failed")
        val cleanupFailure = AtomicReference<Throwable?>(firstFailure)
        val activationEntered = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val registry = VoiceAgentTelecomCallRegistry()
        val oldCall = CallbackFaithfulResolverCall(
            registry = registry,
            cleanupFailure = cleanupFailure,
            onCleanup = { call -> events += "old-cleanup-$call" },
        )
        val accepted = AtomicBoolean(true)
        val activationFailure = AtomicReference<Throwable>()
        var activation: Thread? = null
        var attempt: VoiceAgentTelecomAttemptId? = null
        val firstGateway = RecordingTelecomGateway(events) { id ->
            attempt = id
            activation = thread {
                runCatching {
                    accepted.set(
                        registry.activate(id, oldCall) {
                            activationEntered.countDown()
                            check(releaseActivation.await(5, TimeUnit.SECONDS)) {
                                "activation callback was not released"
                            }
                            events += "activation-returned"
                        },
                    )
                }.onFailure(activationFailure::set)
            }
            check(activationEntered.await(1, TimeUnit.SECONDS)) {
                "activation callback did not start"
            }
        }
        val resolver = VoiceAgentAudioRouteResolver(firstGateway, registry, 1_000)
        val resolution = async(Dispatchers.Default) {
            runCatching { resolver.resolve() }.exceptionOrNull()
        }
        check(activationEntered.await(1, TimeUnit.SECONDS)) {
            "activation callback did not start"
        }
        val dirtyOutcome = async(Dispatchers.Default) {
            registry.observeOutcome(requireNotNull(attempt))
        }

        val frameworkFailure = runCatching { oldCall.disconnectFromApp() }.exceptionOrNull()
        val outcome = withTimeoutOrNull(1_000) { dirtyOutcome.await() }
        val resolutionFailure = withTimeoutOrNull(1_000) { resolution.await() }

        assertSame(firstFailure, frameworkFailure)
        assertTrue(outcome is VoiceAgentTelecomOutcome.CleanupFailed)
        val cleanupFailed = outcome as VoiceAgentTelecomOutcome.CleanupFailed
        assertEquals("telecom_connection_disconnected", cleanupFailed.failure.diagnosticName)
        assertSame(firstFailure, cleanupFailed.cleanupError)
        assertSame(firstFailure, resolutionFailure)
        assertEquals(1, oldCall.disconnectCalls.get())
        assertEquals(1, firstGateway.registerCalls)
        assertEquals(1, firstGateway.startCalls)

        releaseActivation.countDown()
        activation?.join(1_000)
        assertFalse(checkNotNull(activation).isAlive)
        activationFailure.get()?.let { throw AssertionError("activation failed", it) }
        assertFalse(accepted.get())
        assertEquals(1, oldCall.disconnectCalls.get())

        events.clear()
        cleanupFailure.set(secondFailure)
        val replacementCall = ResolverFakeCall()
        val replacementGateway = RecordingTelecomGateway(events) { id ->
            events += "replacement-active"
            assertTrue(registry.activate(id, replacementCall))
        }
        val retryResolver = VoiceAgentAudioRouteResolver(replacementGateway, registry, 1_000)

        val retryFailure = runCatching { retryResolver.resolve() }.exceptionOrNull()

        assertSame(secondFailure, retryFailure)
        assertEquals(2, oldCall.disconnectCalls.get())
        assertEquals(0, replacementGateway.registerCalls)
        assertEquals(0, replacementGateway.startCalls)
        assertEquals(0, replacementCall.disconnectCalls)
        assertEquals(listOf("old-cleanup-2"), events)

        events.clear()
        cleanupFailure.set(null)
        val lease = retryResolver.resolve().requireResolvedLease()

        assertEquals(VoiceAudioRouteOwner.Telecom, lease.metadata.owner)
        assertEquals(3, oldCall.disconnectCalls.get())
        assertEquals(
            listOf("old-cleanup-3", "register", "start", "replacement-active"),
            events,
        )
        assertEquals(0, replacementCall.disconnectCalls)
        lease.retire()
        assertEquals(1, replacementCall.disconnectCalls)
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
                executionDispatchers = DefaultVoiceAgentRouteExecutionDispatchers.copy(
                    acquisition = Dispatchers.Unconfined,
                ),
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
        val lease = resolution.await().requireResolvedLease()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, lease.metadata.owner)
        assertEquals("telecom_connection_timeout", lease.metadata.failure?.diagnosticName)
        assertFalse(accepted.get())
        assertEquals(1, call.disconnectCalls)
        assertEquals(listOf("setActive", "fallback"), events)
    }
}
