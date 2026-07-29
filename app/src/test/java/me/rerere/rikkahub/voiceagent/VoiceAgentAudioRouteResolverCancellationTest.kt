package me.rerere.rikkahub.voiceagent

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentAudioRouteResolverCancellationTest {
    @Test
    fun `cancellation winning final resolved delivery retires and consumes exact route`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = DeliveryGateTelecomCall()
        val gateway = DeliveryGateTelecomGateway { attempt ->
            assertTrue(registry.activate(attempt, call))
        }
        val cleanupExecutor = Executors.newSingleThreadExecutor()
        val cleanupDispatcher = cleanupExecutor.asCoroutineDispatcher()
        val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)
        val observedFailure = AtomicReference<Throwable>()
        val cancellation = CancellationException("cancel armed resolved delivery")

        try {
            val resolution = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    VoiceAgentAudioRouteResolver(
                        gateway = gateway,
                        registry = registry,
                        timeoutMs = 1_000,
                        cleanupScope = cleanupScope,
                        executionDispatchers = DefaultVoiceAgentRouteExecutionDispatchers.copy(
                            acquisition = Dispatchers.Unconfined,
                            cleanup = cleanupDispatcher,
                        ),
                        deliveryProbe = VoiceAgentRouteDeliveryProbe { job -> job.cancel(cancellation) },
                    ).resolve()
                } catch (error: Throwable) {
                    observedFailure.set(error)
                    throw error
                }
            }
            withTimeout(5_000) {
                runCatching { resolution.await() }.exceptionOrNull()
            }
            val thrown = observedFailure.get()
            assertTrue(thrown is CancellationException)
            val thrownCancellation = thrown as CancellationException
            assertEquals(cancellation.message, thrownCancellation.message)
            assertEquals(0, thrownCancellation.suppressed.size)
            val canonicalCancellation = thrownCancellation.canonicalVoiceAgentCancellation()
            assertSame(cancellation, canonicalCancellation)
            assertEquals(cancellation.message, canonicalCancellation.message)
            assertEquals(0, canonicalCancellation.suppressed.size)
            assertEquals(1, call.disconnectCalls.get())
            withTimeout(1_000) {
                assertAttemptWasConsumed(registry, VoiceAgentTelecomAttemptId(1))
            }

            val next = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(2L, next.value)
            registry.retireAttempt(next, VoiceAgentTelecomFailure("test_cleanup", "test cleanup"))
            registry.awaitOutcome(next)
        } finally {
            cleanupScope.cancel()
            cleanupDispatcher.close()
            cleanupExecutor.shutdownNow()
        }
        Unit
    }

    @Test
    fun `failed undelivered route cleanup stays retryable through exact lease owner`() = runBlocking {
        val cleanupFailure = IllegalStateException("undelivered route cleanup failed")
        val cleanupFailureRef = AtomicReference<Throwable?>(cleanupFailure)
        val retryEntered = CountDownLatch(1)
        val retryJoined = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val events = mutableListOf<String>()
        val registry = VoiceAgentTelecomCallRegistry(
            probe = VoiceAgentTelecomRegistryProbe { event ->
                if (event is VoiceAgentTelecomRegistryProbeEvent.RouteRetirementJoining) {
                    retryJoined.countDown()
                }
            },
        )
        val oldCall = DeliveryGateTelecomCall(
            cleanupFailure = cleanupFailureRef,
            beforeDisconnect = { call ->
                events += "old-cleanup-$call"
                if (call == 2) {
                    retryEntered.countDown()
                    check(releaseRetry.await(5, TimeUnit.SECONDS)) {
                        "retained route retry was not released"
                    }
                }
            },
        )
        val initialGateway = DeliveryGateTelecomGateway { attempt ->
            assertTrue(registry.activate(attempt, oldCall))
        }
        val observedFailure = AtomicReference<Throwable>()
        val resolutionReturned = CountDownLatch(1)
        val beginExecutor = Executors.newFixedThreadPool(2)
        val resolverExecutor = Executors.newSingleThreadExecutor()
        val resolverDispatcher = resolverExecutor.asCoroutineDispatcher()
        val cleanupExecutor = Executors.newSingleThreadExecutor()
        val cleanupDispatcher = cleanupExecutor.asCoroutineDispatcher()
        val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)
        val cancellation = CancellationException("cancel armed route with failing cleanup")

        try {
            async(resolverDispatcher) {
                try {
                    VoiceAgentAudioRouteResolver(
                        gateway = initialGateway,
                        registry = registry,
                        timeoutMs = 1_000,
                        cleanupScope = cleanupScope,
                        executionDispatchers = DefaultVoiceAgentRouteExecutionDispatchers.copy(
                            cleanup = cleanupDispatcher,
                        ),
                        deliveryProbe = VoiceAgentRouteDeliveryProbe { job -> job.cancel(cancellation) },
                    ).resolve()
                } catch (error: Throwable) {
                    observedFailure.set(error)
                    throw error
                } finally {
                    resolutionReturned.countDown()
                }
            }
            assertTrue(resolutionReturned.await(1, TimeUnit.SECONDS))
            val thrown = observedFailure.get()
            assertTrue(thrown is CancellationException)
            assertEquals(cancellation.message, thrown.message)
            assertEquals(1, thrown.suppressed.size)
            assertSame(cleanupFailure, thrown.suppressed.single())
            assertEquals(1, oldCall.disconnectCalls.get())

            val retryOwner = beginExecutor.submit<VoiceAgentTelecomAttemptStartResult> {
                registry.beginAttempt()
            }
            assertTrue(retryEntered.await(1, TimeUnit.SECONDS))
            val retryJoiner = beginExecutor.submit<VoiceAgentTelecomAttemptStartResult> {
                registry.beginAttempt()
            }
            assertTrue(retryJoined.await(1, TimeUnit.SECONDS))
            assertFalse(retryOwner.isDone)
            assertFalse(retryJoiner.isDone)

            releaseRetry.countDown()
            val ownerResult = retryOwner.get(1, TimeUnit.SECONDS)
            val joinerResult = retryJoiner.get(1, TimeUnit.SECONDS)
            assertTrue(ownerResult is VoiceAgentTelecomAttemptStartResult.CleanupFailed)
            assertTrue(joinerResult is VoiceAgentTelecomAttemptStartResult.CleanupFailed)
            assertSame(cleanupFailure, (ownerResult as VoiceAgentTelecomAttemptStartResult.CleanupFailed).error)
            assertSame(cleanupFailure, (joinerResult as VoiceAgentTelecomAttemptStartResult.CleanupFailed).error)
            assertEquals(2, oldCall.disconnectCalls.get())

            cleanupFailureRef.set(null)
            val replacementCall = DeliveryGateTelecomCall(
                beforeDisconnect = { call -> events += "replacement-cleanup-$call" },
            )
            val replacementGateway = DeliveryGateTelecomGateway { attempt ->
                events += "replacement-active"
                assertTrue(registry.activate(attempt, replacementCall))
            }
            val lease = VoiceAgentAudioRouteResolver(
                replacementGateway,
                registry,
                1_000,
            ).resolve().requireResolvedLease()

            assertEquals(3, oldCall.disconnectCalls.get())
            assertEquals(
                listOf("old-cleanup-1", "old-cleanup-2", "old-cleanup-3", "replacement-active"),
                events,
            )
            assertEquals(1, replacementGateway.registerCalls)
            assertEquals(1, replacementGateway.startCalls)
            lease.retire()
            assertEquals(1, replacementCall.disconnectCalls.get())
        } finally {
            releaseRetry.countDown()
            cleanupFailureRef.set(null)
            beginExecutor.shutdownNow()
            resolverDispatcher.close()
            resolverExecutor.shutdownNow()
            cleanupScope.cancel()
            cleanupDispatcher.close()
            cleanupExecutor.shutdownNow()
        }
        Unit
    }

    @Test
    fun `cancellation winning cleanup failed delivery suppresses exact error once`() = runBlocking {
        val cleanupFailure = IllegalStateException("direct active cleanup failed")
        val cleanupFailureRef = AtomicReference<Throwable?>(cleanupFailure)
        val registry = VoiceAgentTelecomCallRegistry()
        val call = DeliveryGateTelecomCall(cleanupFailureRef)
        val gateway = DeliveryGateTelecomGateway { attempt ->
            assertTrue(registry.activate(attempt, call))
            assertSame(
                cleanupFailure,
                runCatching {
                    registry.retireAttempt(
                        attempt,
                        VoiceAgentTelecomFailure("active_cleanup", "active cleanup"),
                    )
                }.exceptionOrNull(),
            )
        }
        val observedFailure = AtomicReference<Throwable>()
        val resolutionReturned = CountDownLatch(1)
        val cancellation = CancellationException("cancel armed cleanup failure")

        try {
            async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    VoiceAgentAudioRouteResolver(
                        gateway = gateway,
                        registry = registry,
                        timeoutMs = 1_000,
                        executionDispatchers = DefaultVoiceAgentRouteExecutionDispatchers.copy(
                            acquisition = Dispatchers.Unconfined,
                        ),
                        deliveryProbe = VoiceAgentRouteDeliveryProbe { job -> job.cancel(cancellation) },
                    ).resolve()
                } catch (error: Throwable) {
                    observedFailure.set(error)
                    throw error
                } finally {
                    resolutionReturned.countDown()
                }
            }
            assertTrue(resolutionReturned.await(1, TimeUnit.SECONDS))
            val thrown = observedFailure.get()
            assertTrue(thrown is CancellationException)
            assertEquals(cancellation.message, thrown.message)
            assertEquals(1, thrown.suppressed.size)
            assertSame(cleanupFailure, thrown.suppressed.single())
            assertEquals(1, call.disconnectCalls.get())

            cleanupFailureRef.set(null)
            val next = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(2L, next.value)
            assertEquals(2, call.disconnectCalls.get())
            registry.retireAttempt(next, VoiceAgentTelecomFailure("test_cleanup", "test cleanup"))
            registry.awaitOutcome(next)
        } finally {
            cleanupFailureRef.set(null)
        }
        Unit
    }

    @Test
    fun `cancellation after atomic active claim retires route ownership`() = runBlocking {
        val claimEntered = CountDownLatch(1)
        val releaseClaim = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry(
            probe = VoiceAgentTelecomRegistryProbe { event ->
                if (event is VoiceAgentTelecomRegistryProbeEvent.ActiveOutcomeClaimed) {
                    claimEntered.countDown()
                    check(releaseClaim.await(5, TimeUnit.SECONDS)) { "active claim was not released" }
                }
            },
        )
        val telecomCall = ResolverFakeCall()
        val gateway = FakeTelecomGateway(onStart = { attempt ->
            assertTrue(registry.activate(attempt, telecomCall))
        })
        val resolverExecutor = Executors.newSingleThreadExecutor()
        val resolverDispatcher = resolverExecutor.asCoroutineDispatcher()
        val observedFailure = AtomicReference<Throwable>()
        val resolutionReturned = CountDownLatch(1)

        try {
            val resolution = async(resolverDispatcher) {
                try {
                    VoiceAgentAudioRouteResolver(gateway, registry, 1_000).resolve()
                } catch (error: Throwable) {
                    observedFailure.set(error)
                    throw error
                } finally {
                    resolutionReturned.countDown()
                }
            }
            assertTrue(claimEntered.await(1, TimeUnit.SECONDS))
            val cancellation = CancellationException("cancel after active claim")

            resolution.cancel(cancellation)
            releaseClaim.countDown()

            assertTrue(resolutionReturned.await(1, TimeUnit.SECONDS))
            val thrown = observedFailure.get()
            assertTrue(thrown is CancellationException)
            assertEquals(cancellation.message, thrown.message)
            assertEquals(0, thrown.suppressed.size)
            assertEquals(1, telecomCall.disconnectCalls)
            assertEquals(1, gateway.registerCalls)
            assertEquals(1, gateway.startCalls)
            withTimeout(1_000) {
                assertAttemptWasConsumed(registry, VoiceAgentTelecomAttemptId(1))
            }

            val next = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(2L, next.value)
            registry.retireAttempt(next, VoiceAgentTelecomFailure("test_cleanup", "test cleanup"))
            registry.awaitOutcome(next)
        } finally {
            releaseClaim.countDown()
            resolverDispatcher.close()
            resolverExecutor.shutdownNow()
        }
        Unit
    }

    @Test
    fun `canceled active consumption join keeps cancellation primary over exact cleanup failure`() = runBlocking {
        val cleanupFailure = IllegalStateException("joined active cleanup failed")
        val cleanupFailureRef = AtomicReference<Throwable?>(cleanupFailure)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val consumptionJoinEntered = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry(
            probe = VoiceAgentTelecomRegistryProbe { event ->
                if (event is VoiceAgentTelecomRegistryProbeEvent.RouteRetirementJoining) {
                    consumptionJoinEntered.countDown()
                }
            },
        )
        val activeCall = CallbackFaithfulResolverCall(
            registry = registry,
            cleanupFailure = cleanupFailureRef,
            onCleanup = {
                cleanupEntered.countDown()
                check(releaseCleanup.await(5, TimeUnit.SECONDS)) {
                    "joined active cleanup was not released"
                }
            },
        )
        val outcomeGate = ActiveOutcomeReturnGate()
        val gateway = FakeTelecomGateway(onStart = { attempt ->
            assertTrue(registry.activate(attempt, activeCall))
        })
        val resolverExecutor = Executors.newSingleThreadExecutor()
        val resolverDispatcher = resolverExecutor.asCoroutineDispatcher()
        val replacementExecutor = Executors.newSingleThreadExecutor()
        val observedFailure = AtomicReference<Throwable>()
        val resolutionReturned = CountDownLatch(1)

        try {
            val resolution = async(resolverDispatcher) {
                try {
                    VoiceAgentAudioRouteResolver(
                        gateway = gateway,
                        registry = registry,
                        timeoutMs = 1_000,
                        outcomeTimeout = outcomeGate,
                    ).resolve()
                } catch (error: Throwable) {
                    observedFailure.set(error)
                    throw error
                } finally {
                    resolutionReturned.countDown()
                }
            }
            assertEquals(VoiceAgentTelecomOutcome.Active, outcomeGate.observedOutcome.await())
            val replacement = replacementExecutor.submit<VoiceAgentTelecomAttemptStartResult> {
                registry.beginAttempt()
            }
            assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS))
            outcomeGate.returnOutcome.complete(Unit)
            assertTrue(consumptionJoinEntered.await(1, TimeUnit.SECONDS))
            val cancellation = CancellationException("cancel active consumption join")

            resolution.cancel(cancellation)
            releaseCleanup.countDown()

            val replacementResult = replacement.get(1, TimeUnit.SECONDS)
            assertTrue(replacementResult is VoiceAgentTelecomAttemptStartResult.CleanupFailed)
            assertSame(
                cleanupFailure,
                (replacementResult as VoiceAgentTelecomAttemptStartResult.CleanupFailed).error,
            )
            assertTrue(resolutionReturned.await(1, TimeUnit.SECONDS))
            val thrown = observedFailure.get()
            assertTrue(thrown is CancellationException)
            assertEquals(cancellation.message, thrown.message)
            assertEquals(1, thrown.suppressed.size)
            assertSame(cleanupFailure, thrown.suppressed.single())
            assertEquals(1, activeCall.disconnectCalls.get())

            cleanupFailureRef.set(null)
            val retry = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(2L, retry.value)
            assertEquals(2, activeCall.disconnectCalls.get())
            registry.retireAttempt(retry, VoiceAgentTelecomFailure("test_cleanup", "test cleanup"))
            registry.awaitOutcome(retry)
        } finally {
            outcomeGate.returnOutcome.complete(Unit)
            releaseCleanup.countDown()
            cleanupFailureRef.set(null)
            resolverDispatcher.close()
            resolverExecutor.shutdownNow()
            replacementExecutor.shutdownNow()
        }
        Unit
    }

    @Test
    fun `canceled blocked begin keeps cancellation primary over joined cleanup failure`() = runBlocking {
        val cleanupFailure = IllegalStateException("joined pre-lease cleanup failed")
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
                check(releaseCleanup.await(5, TimeUnit.SECONDS)) {
                    "joined pre-lease cleanup was not released"
                }
            },
        )
        assertTrue(registry.activate(previous, previousCall))
        val cleanupExecutor = Executors.newSingleThreadExecutor()
        val resolverThread = AtomicReference<Thread>()
        val resolverExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "canceled-begin-resolver").also(resolverThread::set)
        }
        val resolverDispatcher = resolverExecutor.asCoroutineDispatcher()
        val gateway = FakeTelecomGateway()
        val observedFailure = AtomicReference<Throwable>()
        val resolutionReturned = CountDownLatch(1)

        try {
            val cleanupOwner = cleanupExecutor.submit<Throwable?> {
                runCatching {
                    registry.retireAttempt(
                        previous,
                        VoiceAgentTelecomFailure("prelease_cleanup", "blocked cleanup"),
                    )
                }.exceptionOrNull()
            }
            check(cleanupEntered.await(1, TimeUnit.SECONDS)) {
                "joined pre-lease cleanup did not start"
            }
            val resolution = async(resolverDispatcher) {
                try {
                    VoiceAgentAudioRouteResolver(gateway, registry, 1_000).resolve()
                } catch (error: Throwable) {
                    observedFailure.set(error)
                    throw error
                } finally {
                    resolutionReturned.countDown()
                }
            }
            awaitBlocked(resolverThread)
            val cancellation = CancellationException("cancel blocked begin")

            resolution.cancel(cancellation)
            releaseCleanup.countDown()

            assertSame(cleanupFailure, cleanupOwner.get(1, TimeUnit.SECONDS))
            assertTrue(resolutionReturned.await(1, TimeUnit.SECONDS))
            val thrown = observedFailure.get()
            assertTrue(thrown is CancellationException)
            assertEquals(cancellation.message, thrown.message)
            assertEquals(1, thrown.suppressed.size)
            assertSame(cleanupFailure, thrown.suppressed.single())
            assertEquals(0, gateway.registerCalls)
            assertEquals(0, gateway.startCalls)

            cleanupFailureRef.set(null)
            val replacement = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(previous.value + 1, replacement.value)
        } finally {
            releaseCleanup.countDown()
            resolverDispatcher.close()
            resolverExecutor.shutdownNow()
            cleanupExecutor.shutdownNow()
        }
    }

    @Test
    fun `canceled route cleanup keeps cancellation primary without allocating`() = runBlocking {
        val cleanupFailure = IllegalStateException("route predecessor cleanup failed")
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
                check(releaseCleanup.await(5, TimeUnit.SECONDS)) {
                    "route predecessor cleanup was not released"
                }
            },
        )
        assertTrue(registry.activate(previous, previousCall))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(previous))
        val previousLease = registry.consumeActiveOutcome(previous).requireResolvedLease()
        val resolverExecutor = Executors.newSingleThreadExecutor()
        val resolverDispatcher = resolverExecutor.asCoroutineDispatcher()
        val gateway = FakeTelecomGateway()
        val observedFailure = AtomicReference<Throwable>()
        val resolutionReturned = CountDownLatch(1)

        try {
            val resolution = async(resolverDispatcher) {
                try {
                    VoiceAgentAudioRouteResolver(gateway, registry, 1_000).resolve()
                } catch (error: Throwable) {
                    observedFailure.set(error)
                    throw error
                } finally {
                    resolutionReturned.countDown()
                }
            }
            check(cleanupEntered.await(1, TimeUnit.SECONDS)) {
                "route predecessor cleanup did not start"
            }
            val cancellation = CancellationException("cancel route cleanup")

            resolution.cancel(cancellation)
            releaseCleanup.countDown()

            assertTrue(resolutionReturned.await(1, TimeUnit.SECONDS))
            val thrown = observedFailure.get()
            assertTrue(thrown is CancellationException)
            assertEquals(cancellation.message, thrown.message)
            assertEquals(1, thrown.suppressed.size)
            assertSame(cleanupFailure, thrown.suppressed.single())
            assertEquals(
                null,
                registry.awaitOutcomeIfPresent(VoiceAgentTelecomAttemptId(previous.value + 1)),
            )
            assertEquals(1, previousCall.disconnectCalls.get())
            assertEquals(0, gateway.registerCalls)
            assertEquals(0, gateway.startCalls)

            cleanupFailureRef.set(null)
            previousLease.retire()
            val next = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(previous.value + 1, next.value)
            registry.retireAttempt(next, VoiceAgentTelecomFailure("test_cleanup", "test cleanup"))
            registry.awaitOutcome(next)
        } finally {
            releaseCleanup.countDown()
            cleanupFailureRef.set(null)
            previousLease.retire()
            resolverDispatcher.close()
            resolverExecutor.shutdownNow()
        }
        Unit
    }

    @Test
    fun `canceled blocked route cleanup consumes successful allocation before rethrowing`() = runBlocking {
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry()
        val previous = registry.beginAttempt().requireAllocatedAttemptId()
        val previousCall = CallbackFaithfulResolverCall(
            registry = registry,
            cleanupFailure = AtomicReference(null),
            onCleanup = {
                cleanupEntered.countDown()
                check(releaseCleanup.await(5, TimeUnit.SECONDS)) {
                    "successful route cleanup was not released"
                }
            },
        )
        assertTrue(registry.activate(previous, previousCall))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(previous))
        val previousLease = registry.consumeActiveOutcome(previous).requireResolvedLease()
        val resolverThread = AtomicReference<Thread>()
        val resolverExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "canceled-successful-begin-resolver").also(resolverThread::set)
        }
        val resolverDispatcher = resolverExecutor.asCoroutineDispatcher()
        val gateway = FakeTelecomGateway()
        val observedFailure = AtomicReference<Throwable>()
        val resolutionReturned = CountDownLatch(1)

        try {
            val resolution = async(resolverDispatcher) {
                try {
                    VoiceAgentAudioRouteResolver(gateway, registry, 1_000).resolve()
                } catch (error: Throwable) {
                    observedFailure.set(error)
                    throw error
                } finally {
                    resolutionReturned.countDown()
                }
            }
            check(cleanupEntered.await(1, TimeUnit.SECONDS)) {
                "successful route cleanup did not start"
            }
            val cancellation = CancellationException("cancel successful route cleanup")

            resolution.cancel(cancellation)
            releaseCleanup.countDown()

            assertTrue(resolutionReturned.await(1, TimeUnit.SECONDS))
            val thrown = observedFailure.get()
            assertTrue(thrown is CancellationException)
            assertEquals(cancellation.message, thrown.message)
            assertEquals(0, thrown.suppressed.size)
            assertEquals(1, previousCall.disconnectCalls.get())
            assertEquals(0, gateway.registerCalls)
            assertEquals(0, gateway.startCalls)

            val canceledAttempt = VoiceAgentTelecomAttemptId(previous.value + 1)
            withTimeout(1_000) {
                assertAttemptWasConsumed(registry, canceledAttempt)
            }
            val next = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(previous.value + 2, next.value)
            registry.retireAttempt(next, VoiceAgentTelecomFailure("test_cleanup", "test cleanup"))
            registry.awaitOutcome(next)
        } finally {
            releaseCleanup.countDown()
            previousLease.retire()
            resolverDispatcher.close()
            resolverExecutor.shutdownNow()
        }
        Unit
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
        assertFalse(registry.isOwnedAttemptActive(requireNotNull(attempt)))
    }

    @Test
    fun `retirement error does not replace caller cancellation`() = runTest {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = ThrowingResolverCall()
        val cancellation = CancellationException("caller cancelled after activation")
        var attempt: VoiceAgentTelecomAttemptId? = null
        val resolver = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(onStart = { id ->
                attempt = id
                registry.activate(id, call)
                throw cancellation
            }),
            registry,
            1_000,
        )

        val thrown = runCatching { resolver.resolve() }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(listOf("framework retirement failed"), cancellation.suppressed.map { it.message })
        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(requireNotNull(attempt)))
    }

    @Test
    fun `canceled active resolution keeps exact dirty cleanup ahead of replacement gateway work`() = runBlocking {
        val firstFailure = IllegalStateException("first pre-lease cleanup failed")
        val secondFailure = IllegalArgumentException("second pre-lease cleanup failed")
        val cleanupFailure = AtomicReference<Throwable?>(firstFailure)
        val retryEntered = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val registry = VoiceAgentTelecomCallRegistry()
        val oldCall = CallbackFaithfulResolverCall(
            registry = registry,
            cleanupFailure = cleanupFailure,
            onCleanup = { call ->
                events += "old-cleanup-$call"
                if (call == 2) {
                    retryEntered.countDown()
                    check(releaseRetry.await(1, TimeUnit.SECONDS)) {
                        "predecessor cleanup retry was not released"
                    }
                }
            },
        )
        val cancellation = CancellationException("caller cancelled after activation")
        val initialGateway = RecordingTelecomGateway(events) { attempt ->
            assertTrue(registry.activate(attempt, oldCall))
            throw cancellation
        }

        val thrown = runCatching {
            VoiceAgentAudioRouteResolver(
                gateway = initialGateway,
                registry = registry,
                timeoutMs = 1_000,
                executionDispatchers = DefaultVoiceAgentRouteExecutionDispatchers.copy(
                    acquisition = Dispatchers.Unconfined,
                ),
            ).resolve()
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(1, cancellation.suppressed.size)
        assertSame(firstFailure, cancellation.suppressed.single())
        assertEquals(1, oldCall.disconnectCalls.get())
        events.clear()

        cleanupFailure.set(secondFailure)
        val replacementCall = ResolverFakeCall()
        val replacementGateway = RecordingTelecomGateway(events) { attempt ->
            events += "replacement-active"
            assertTrue(registry.activate(attempt, replacementCall))
        }
        val retryResolver = VoiceAgentAudioRouteResolver(replacementGateway, registry, 1_000)
        val secondRetryStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val owner = executor.submit<Throwable?> {
                runCatching { runBlocking { retryResolver.resolve() } }.exceptionOrNull()
            }
            check(retryEntered.await(1, TimeUnit.SECONDS)) {
                "next resolution did not retry predecessor cleanup"
            }
            val joiner = executor.submit<Throwable?> {
                secondRetryStarted.countDown()
                runCatching { runBlocking { retryResolver.resolve() } }.exceptionOrNull()
            }
            check(secondRetryStarted.await(1, TimeUnit.SECONDS)) {
                "joining resolution did not start"
            }
            assertTrue(
                runCatching { owner.get(100, TimeUnit.MILLISECONDS) }
                    .exceptionOrNull() is TimeoutException,
            )
            assertTrue(
                runCatching { joiner.get(100, TimeUnit.MILLISECONDS) }
                    .exceptionOrNull() is TimeoutException,
            )
            assertEquals(0, replacementGateway.registerCalls)
            assertEquals(0, replacementGateway.startCalls)
            assertEquals(0, replacementCall.disconnectCalls)

            releaseRetry.countDown()
            assertSame(secondFailure, owner.get(1, TimeUnit.SECONDS))
            assertSame(secondFailure, joiner.get(1, TimeUnit.SECONDS))
            assertEquals(2, oldCall.disconnectCalls.get())
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
        } finally {
            releaseRetry.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `caller cancellation waits for blocked activation retirement before rethrowing`() = runTest {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = ResolverFakeCall()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        var activation: Thread? = null
        var attempt: VoiceAgentTelecomAttemptId? = null
        val resolver = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(onStart = { id ->
                attempt = id
                activation = thread {
                    accepted.set(
                        registry.activate(id, call) {
                            callbackEntered.countDown()
                            releaseCallback.await()
                        },
                    )
                }
                assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))
            }),
            registry,
            1_000,
            executionDispatchers = VoiceAgentRouteExecutionDispatchers(
                acquisition = Dispatchers.Unconfined,
                cleanup = Dispatchers.Unconfined,
            ),
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
        assertFalse(registry.isOwnedAttemptActive(requireNotNull(attempt)))
    }
}

private class DeliveryGateTelecomGateway(
    private val onStart: (VoiceAgentTelecomAttemptId) -> Unit,
) : VoiceAgentTelecomGateway {
    var registerCalls = 0
    var startCalls = 0

    override fun register(): Result<Unit> {
        registerCalls += 1
        return Result.success(Unit)
    }

    override fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit> {
        startCalls += 1
        onStart(attemptId)
        return Result.success(Unit)
    }
}

private class DeliveryGateTelecomCall(
    private val cleanupFailure: AtomicReference<Throwable?> = AtomicReference(null),
    private val beforeDisconnect: (Int) -> Unit = {},
) : VoiceAgentTelecomCall {
    val disconnectCalls = AtomicInteger()

    override fun disconnectFromApp() {
        val call = disconnectCalls.incrementAndGet()
        beforeDisconnect(call)
        cleanupFailure.get()?.let { throw it }
    }
}
