package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentAudioRouteResolverDeliveryTest {
    @Test
    fun `production cleanup capacity is reserved from acquisition`() {
        assertNotSame(
            DefaultVoiceAgentRouteExecutionDispatchers.acquisition,
            DefaultVoiceAgentRouteExecutionDispatchers.cleanup,
        )
    }

    @Test
    fun `reserved cleanup releases an admission domain filled with exact joiners`() = runBlocking {
        supervisorScope {
            val cleanupFailure = IllegalStateException("reserved cleanup failed")
            val currentFailure = AtomicReference<Throwable?>(cleanupFailure)
            val cleanupEntered = CountDownLatch(1)
            val releaseCleanup = CountDownLatch(1)
            val cleanupThread = AtomicReference<Thread>()
            val joinEntered = CountDownLatch(2)
            val registry = VoiceAgentTelecomCallRegistry(
                probe = VoiceAgentTelecomRegistryProbe { event ->
                    if (event is VoiceAgentTelecomRegistryProbeEvent.RouteRetirementJoining) {
                        joinEntered.countDown()
                    }
                },
            )
            val call = CallbackFaithfulResolverCall(
                registry = registry,
                cleanupFailure = currentFailure,
                onCleanup = {
                    cleanupThread.set(Thread.currentThread())
                    cleanupEntered.countDown()
                    check(releaseCleanup.await(5, TimeUnit.SECONDS)) {
                        "reserved cleanup was not released"
                    }
                },
            )
            val ownerGateway = DeliveryTestGateway { attempt ->
                assertTrue(registry.activate(attempt, call))
            }
            val joinerRegisterCalls = AtomicInteger()
            val joinerStartCalls = AtomicInteger()
            val joinerGateway = object : VoiceAgentTelecomGateway {
                override fun register(): Result<Unit> {
                    joinerRegisterCalls.incrementAndGet()
                    return Result.success(Unit)
                }

                override fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit> {
                    joinerStartCalls.incrementAndGet()
                    return Result.success(Unit)
                }
            }
            val admissionExecutor = Executors.newFixedThreadPool(2)
            val admissionReady = CountDownLatch(2)
            val releaseAdmission = CountDownLatch(1)
            val admissionDispatcher = FirstTasksGateDispatcher(
                executor = admissionExecutor,
                taskCount = 2,
                entered = admissionReady,
                release = releaseAdmission,
            )
            val reservedCleanupExecutor = Executors.newSingleThreadExecutor()
            val reservedCleanupDispatcher = reservedCleanupExecutor.asCoroutineDispatcher()
            val executionDispatchers = VoiceAgentRouteExecutionDispatchers(
                acquisition = admissionDispatcher,
                cleanup = reservedCleanupDispatcher,
            )
            val callerExecutor = Executors.newFixedThreadPool(2)
            val cleanupScope = CoroutineScope(SupervisorJob() + reservedCleanupDispatcher)
            val main = MainDeliveryGateDispatcher()
            val mainThread = AtomicReference<Thread>()
            val cancellation = CancellationException("cancel into reserved cleanup")
            val joiners = AtomicReference<List<java.util.concurrent.Future<Throwable?>>>()
            val observed = AtomicReference<Throwable>()
            var resolution: Deferred<VoiceAgentRouteResolution>? = null

            try {
                resolution = async(main) {
                    try {
                        VoiceAgentAudioRouteResolver(
                            gateway = ownerGateway,
                            registry = registry,
                            timeoutMs = 1_000,
                            executionDispatchers = VoiceAgentRouteExecutionDispatchers(
                                acquisition = kotlinx.coroutines.Dispatchers.Unconfined,
                                cleanup = reservedCleanupDispatcher,
                            ),
                            cleanupScope = cleanupScope,
                            deliveryProbe = VoiceAgentRouteDeliveryProbe { job ->
                                mainThread.set(Thread.currentThread())
                                joiners.set(
                                    List(2) {
                                        callerExecutor.submit<Throwable?> {
                                            runBlocking {
                                                runCatching {
                                                    VoiceAgentAudioRouteResolver(
                                                        gateway = joinerGateway,
                                                        registry = registry,
                                                        timeoutMs = 1_000,
                                                        executionDispatchers = executionDispatchers,
                                                    ).resolve()
                                                }.exceptionOrNull()
                                            }
                                        }
                                    },
                                )
                                check(admissionReady.await(1, TimeUnit.SECONDS)) {
                                    "admission domain was not filled"
                                }
                                job.cancel(cancellation)
                                releaseAdmission.countDown()
                            },
                        ).resolve()
                    } catch (error: Throwable) {
                        observed.set(error)
                        throw error
                    }
                }

                assertTrue(joinEntered.await(1, TimeUnit.SECONDS))
                assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS))
                assertEquals(0, joinerRegisterCalls.get())
                assertEquals(0, joinerStartCalls.get())
                assertEquals(1, call.disconnectCalls.get())
                assertTrue(cleanupThread.get() !== mainThread.get())

                releaseCleanup.countDown()
                checkNotNull(joiners.get()).forEach { joiner ->
                    assertSame(cleanupFailure, joiner.get(1, TimeUnit.SECONDS))
                }
                withTimeoutOrNull(1_000) { checkNotNull(resolution).join() }
                val thrown = observed.get()
                assertTrue(thrown is CancellationException)
                assertEquals(cancellation.message, thrown.message)
                assertEquals(1, thrown.suppressed.size)
                assertSame(cleanupFailure, thrown.suppressed.single())
                assertEquals(1, call.disconnectCalls.get())

                currentFailure.set(null)
                val next = registry.beginAttempt().requireAllocatedAttemptId()
                assertEquals(2, call.disconnectCalls.get())
                assertEquals(2L, next.value)
            } finally {
                releaseAdmission.countDown()
                releaseCleanup.countDown()
                withTimeoutOrNull(1_000) { resolution?.join() }
                currentFailure.set(null)
                main.close()
                cleanupScope.cancel()
                reservedCleanupDispatcher.close()
                reservedCleanupExecutor.shutdownNow()
                callerExecutor.shutdownNow()
                admissionExecutor.shutdownNow()
            }
        }
    }

    @Test
    fun `rejected allocated cleanup dispatch preserves cancellation and consumes attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val registerCalls = AtomicInteger()
        val startCalls = AtomicInteger()
        val gateway = object : VoiceAgentTelecomGateway {
            override fun register(): Result<Unit> {
                registerCalls.incrementAndGet()
                return Result.success(Unit)
            }

            override fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit> {
                startCalls.incrementAndGet()
                return Result.success(Unit)
            }
        }
        val main = MainDeliveryGateDispatcher()
        val acquisitionExecutor = Executors.newSingleThreadExecutor()
        val cancellation = CanonicalCancellationException(Any())
        val schedulingFailure = NonCopyableCleanupException(Any(), "allocated cleanup dispatch rejected")
        val resolutionRef = AtomicReference<Deferred<VoiceAgentRouteResolution>>()
        val mainBlocked = CountDownLatch(1)
        val releaseMain = CountDownLatch(1)
        val cancellationTriggered = CountDownLatch(1)
        val sentinelRan = CountDownLatch(1)
        val resolutionReturned = CountDownLatch(1)
        val observed = AtomicReference<Throwable>()
        val acquisitionDispatcher = AfterFirstTaskDispatcher(
            executor = acquisitionExecutor,
            beforeFirstTask = {
                main.execute {
                    mainBlocked.countDown()
                    check(releaseMain.await(5, TimeUnit.SECONDS)) { "Main gate was not released" }
                }
                check(mainBlocked.await(1, TimeUnit.SECONDS)) { "Main gate was not entered" }
            },
            afterFirstTask = {
                checkNotNull(resolutionRef.get()).cancel(cancellation)
                cancellationTriggered.countDown()
                releaseMain.countDown()
            },
        )

        try {
            val resolution = async(main) {
                try {
                    VoiceAgentAudioRouteResolver(
                        gateway = gateway,
                        registry = registry,
                        timeoutMs = 1_000,
                        executionDispatchers = VoiceAgentRouteExecutionDispatchers(
                            acquisition = acquisitionDispatcher,
                            cleanup = RejectingDispatcher(schedulingFailure),
                        ),
                    ).resolve()
                } catch (error: Throwable) {
                    observed.set(error)
                    throw error
                } finally {
                    resolutionReturned.countDown()
                }
            }
            resolutionRef.set(resolution)
            assertTrue(cancellationTriggered.await(1, TimeUnit.SECONDS))
            main.execute { sentinelRan.countDown() }

            assertTrue(sentinelRan.await(1, TimeUnit.SECONDS))
            assertTrue(resolutionReturned.await(1, TimeUnit.SECONDS))
            val thrown = observed.get()
            assertSame(cancellation, thrown)
            assertEquals(1, thrown.suppressed.size)
            assertSame(schedulingFailure, thrown.suppressed.single())
            assertEquals(0, registerCalls.get())
            assertEquals(0, startCalls.get())
            assertEquals(null, registry.awaitOutcomeIfPresent(VoiceAgentTelecomAttemptId(1)))

            val next = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(2L, next.value)
        } finally {
            releaseMain.countDown()
            main.close()
            acquisitionExecutor.shutdownNow()
        }
    }

    @Test
    fun `blocked replacement join leaves Main responsive to cancellation`() = runBlocking {
        supervisorScope {
            val cleanupFailure = IllegalStateException("blocked predecessor cleanup failed")
            val currentFailure = AtomicReference<Throwable?>(cleanupFailure)
            val cleanupEntered = CountDownLatch(1)
            val releaseCleanup = CountDownLatch(1)
            val joinEntered = CountDownLatch(1)
            val registry = VoiceAgentTelecomCallRegistry(
                probe = VoiceAgentTelecomRegistryProbe { event ->
                    if (event is VoiceAgentTelecomRegistryProbeEvent.RouteRetirementJoining) {
                        joinEntered.countDown()
                    }
                },
            )
            val previous = registry.beginAttempt().requireAllocatedAttemptId()
            val previousCall = CallbackFaithfulResolverCall(
                registry = registry,
                cleanupFailure = currentFailure,
                onCleanup = {
                    cleanupEntered.countDown()
                    check(releaseCleanup.await(5, TimeUnit.SECONDS)) {
                        "predecessor cleanup was not released"
                    }
                },
            )
            assertTrue(registry.activate(previous, previousCall))
            assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(previous))
            val previousLease = registry.consumeActiveOutcome(previous).requireResolvedLease() as
                TelecomVoiceAgentRouteLease
            val cleanupAcquisition = previousLease.claimUndeliveredCleanup()
            val retirementExecutor = Executors.newSingleThreadExecutor()
            val retirement = retirementExecutor.submit {
                previousLease.executeUndeliveredCleanup(cleanupAcquisition)
            }
            val main = MainDeliveryGateDispatcher()
            val blockingExecutor = Executors.newSingleThreadExecutor()
            val blockingDispatcher = blockingExecutor.asCoroutineDispatcher()
            val observed = AtomicReference<Throwable>()
            val resolutionReturned = CountDownLatch(1)
            val sentinelRan = CountDownLatch(1)
            val cancellation = CancellationException("cancel blocked Main replacement")
            var resolution: Deferred<VoiceAgentRouteResolution>? = null

            try {
                assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS))
                resolution = async(main) {
                    try {
                        VoiceAgentAudioRouteResolver(
                            gateway = DeliveryTestGateway { error("gateway must not run") },
                            registry = registry,
                            timeoutMs = 1_000,
                            executionDispatchers = DefaultVoiceAgentRouteExecutionDispatchers.copy(
                                acquisition = blockingDispatcher,
                            ),
                        ).resolve()
                    } catch (error: Throwable) {
                        observed.set(error)
                        throw error
                    } finally {
                        resolutionReturned.countDown()
                    }
                }
                assertTrue(joinEntered.await(1, TimeUnit.SECONDS))
                main.execute {
                    checkNotNull(resolution).cancel(cancellation)
                    sentinelRan.countDown()
                }

                assertTrue(sentinelRan.await(1, TimeUnit.SECONDS))
                assertTrue(checkNotNull(resolution).isCancelled)
                assertFalse(checkNotNull(resolution).isCompleted)
                releaseCleanup.countDown()

                retirement.get(1, TimeUnit.SECONDS)
                assertTrue(resolutionReturned.await(1, TimeUnit.SECONDS))
                val thrown = observed.get()
                assertTrue(thrown is CancellationException)
                assertEquals(cancellation.message, thrown.message)
                assertEquals(1, thrown.suppressed.size)
                assertSame(cleanupFailure, thrown.suppressed.single())
                assertEquals(1, previousCall.disconnectCalls.get())

                currentFailure.set(null)
                previousLease.retire()
                val next = registry.beginAttempt().requireAllocatedAttemptId()
                assertEquals(2L, next.value)
            } finally {
                releaseCleanup.countDown()
                withTimeoutOrNull(1_000) { resolution?.join() }
                currentFailure.set(null)
                previousLease.retire()
                main.close()
                blockingDispatcher.close()
                blockingExecutor.shutdownNow()
                retirementExecutor.shutdownNow()
            }
        }
    }

    @Test
    fun `delivery cancellation joins framework failure publication before exact retry`() = runBlocking {
        verifyFrameworkFailureDuringFinalDelivery(pauseFailurePublication = true)
    }

    @Test
    fun `delivery cancellation retries finalized framework failure with exact lease`() = runBlocking {
        verifyFrameworkFailureDuringFinalDelivery(pauseFailurePublication = false)
    }

    @Test
    fun `main cancellation returns while exact delivery cleanup blocks`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val call = BlockingDeliveryCall(cleanupEntered, releaseCleanup)
        val gateway = DeliveryTestGateway { attempt ->
            assertTrue(registry.activate(attempt, call))
        }
        val main = MainDeliveryGateDispatcher()
        val cleanupExecutor = Executors.newSingleThreadExecutor()
        val cleanupDispatcher = cleanupExecutor.asCoroutineDispatcher()
        val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)
        val observed = AtomicReference<Throwable>()
        val resolverReturned = CountDownLatch(1)
        val cancellation = CancellationException("cancel final delivery on Main")
        val cancelReturned = CountDownLatch(1)

        try {
            val resolution = async(main) {
                try {
                    VoiceAgentAudioRouteResolver(
                        gateway = gateway,
                        registry = registry,
                        timeoutMs = 1_000,
                        cleanupScope = cleanupScope,
                        executionDispatchers = DefaultVoiceAgentRouteExecutionDispatchers.copy(
                            cleanup = cleanupDispatcher,
                        ),
                        deliveryProbe = VoiceAgentRouteDeliveryProbe { job ->
                            job.cancel(cancellation)
                            cancelReturned.countDown()
                        },
                    ).resolve()
                } catch (error: Throwable) {
                    observed.set(error)
                    throw error
                } finally {
                    resolverReturned.countDown()
                }
            }
            assertTrue(cancelReturned.await(1, TimeUnit.SECONDS))
            assertTrue(main.awaitIdle())
            assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS))
            assertFalse(resolution.isCompleted)
            assertFalse(resolverReturned.await(100, TimeUnit.MILLISECONDS))
            assertEquals(1, call.disconnectCalls.get())

            releaseCleanup.countDown()
            assertTrue(resolverReturned.await(1, TimeUnit.SECONDS))
            val thrown = observed.get()
            assertTrue(thrown is CancellationException)
            assertEquals(cancellation.message, thrown.message)
            assertEquals(0, thrown.suppressed.size)
            assertEquals(1, call.disconnectCalls.get())
            assertAttemptWasConsumed(registry, VoiceAgentTelecomAttemptId(1))
        } finally {
            releaseCleanup.countDown()
            main.close()
            cleanupScope.cancel()
            cleanupDispatcher.close()
            cleanupExecutor.shutdownNow()
        }
    }

    @Test
    fun `failed asynchronous delivery cleanup is suppressed once and remains retryable`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val cleanupFailure = IllegalStateException("async delivery cleanup failed")
        val currentFailure = AtomicReference<Throwable?>(cleanupFailure)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val call = BlockingDeliveryCall(cleanupEntered, releaseCleanup, currentFailure)
        val gateway = DeliveryTestGateway { attempt ->
            assertTrue(registry.activate(attempt, call))
        }
        val main = MainDeliveryGateDispatcher()
        val cleanupExecutor = Executors.newSingleThreadExecutor()
        val cleanupDispatcher = cleanupExecutor.asCoroutineDispatcher()
        val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)
        val observed = AtomicReference<Throwable>()
        val resolverReturned = CountDownLatch(1)
        val cancellation = CancellationException("cancel failing final cleanup")
        val cancelReturned = CountDownLatch(1)

        try {
            val resolution = async(main) {
                try {
                    VoiceAgentAudioRouteResolver(
                        gateway = gateway,
                        registry = registry,
                        timeoutMs = 1_000,
                        cleanupScope = cleanupScope,
                        executionDispatchers = DefaultVoiceAgentRouteExecutionDispatchers.copy(
                            cleanup = cleanupDispatcher,
                        ),
                        deliveryProbe = VoiceAgentRouteDeliveryProbe { job ->
                            job.cancel(cancellation)
                            cancelReturned.countDown()
                        },
                    ).resolve()
                } catch (error: Throwable) {
                    observed.set(error)
                    throw error
                } finally {
                    resolverReturned.countDown()
                }
            }
            assertTrue(cancelReturned.await(1, TimeUnit.SECONDS))
            assertTrue(main.awaitIdle())
            assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS))
            assertFalse(resolution.isCompleted)
            releaseCleanup.countDown()
            assertTrue(resolverReturned.await(1, TimeUnit.SECONDS))

            val thrown = observed.get()
            assertTrue(thrown is CancellationException)
            assertEquals(cancellation.message, thrown.message)
            assertEquals(1, thrown.suppressed.size)
            assertSame(cleanupFailure, thrown.suppressed.single())
            assertEquals(1, call.disconnectCalls.get())

            val blocked = registry.beginAttempt()
            assertTrue(blocked is VoiceAgentTelecomAttemptStartResult.CleanupFailed)
            assertSame(cleanupFailure, (blocked as VoiceAgentTelecomAttemptStartResult.CleanupFailed).error)
            assertEquals(2, call.disconnectCalls.get())
            currentFailure.set(null)
            val replacement = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(2L, replacement.value)
            assertEquals(3, call.disconnectCalls.get())
        } finally {
            releaseCleanup.countDown()
            main.close()
            cleanupScope.cancel()
            cleanupDispatcher.close()
            cleanupExecutor.shutdownNow()
        }
    }

    @Test
    fun `rejected cleanup scheduling retains the exact lease for retry`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = BlockingDeliveryCall(CountDownLatch(0), CountDownLatch(0))
        val gateway = DeliveryTestGateway { attempt ->
            assertTrue(registry.activate(attempt, call))
        }
        val main = MainDeliveryGateDispatcher()
        val schedulingFailure = IllegalStateException("cleanup dispatcher rejected")
        val cleanupScope = CoroutineScope(SupervisorJob() + RejectingDispatcher(schedulingFailure))
        val observed = AtomicReference<Throwable>()
        val cancellation = CancellationException("cancel with rejected cleanup scheduling")
        val cancelReturned = CountDownLatch(1)

        try {
            val resolution = async(main) {
                try {
                    VoiceAgentAudioRouteResolver(
                        gateway = gateway,
                        registry = registry,
                        timeoutMs = 1_000,
                        cleanupScope = cleanupScope,
                        executionDispatchers = DefaultVoiceAgentRouteExecutionDispatchers.copy(
                            cleanup = RejectingDispatcher(schedulingFailure),
                        ),
                        deliveryProbe = VoiceAgentRouteDeliveryProbe { job ->
                            job.cancel(cancellation)
                            cancelReturned.countDown()
                        },
                    ).resolve()
                } catch (error: Throwable) {
                    observed.set(error)
                    throw error
                }
            }
            assertTrue(cancelReturned.await(1, TimeUnit.SECONDS))
            assertTrue(main.awaitIdle())
            val thrown = observed.get()
            assertTrue(thrown is CancellationException)
            assertEquals(1, thrown.suppressed.size)
            assertSame(schedulingFailure, thrown.suppressed.single())
            assertEquals(0, call.disconnectCalls.get())

            val replacement = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(2L, replacement.value)
            assertEquals(1, call.disconnectCalls.get())
        } finally {
            main.close()
            cleanupScope.cancel()
        }
    }

    private suspend fun CoroutineScope.verifyFrameworkFailureDuringFinalDelivery(
        pauseFailurePublication: Boolean,
    ) {
        val cleanupFailure = IllegalStateException("framework delivery cleanup failed")
        val currentFailure = AtomicReference<Throwable?>(cleanupFailure)
        val activeOutcomeClaimed = CountDownLatch(1)
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(if (pauseFailurePublication) 1 else 0)
        val retryEntered = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val replacementJoinEntered = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry(
            probe = VoiceAgentTelecomRegistryProbe { event ->
                when (event) {
                    VoiceAgentTelecomRegistryProbeEvent.ActiveOutcomeClaimed -> {
                        activeOutcomeClaimed.countDown()
                    }
                    VoiceAgentTelecomRegistryProbeEvent.FailedRetirementResultPublishing -> {
                        publicationEntered.countDown()
                        check(releasePublication.await(5, TimeUnit.SECONDS)) {
                            "framework failure publication was not released"
                        }
                    }
                    VoiceAgentTelecomRegistryProbeEvent.RouteRetirementJoining -> {
                        replacementJoinEntered.countDown()
                    }
                    else -> Unit
                }
            },
        )
        val call = CallbackFaithfulResolverCall(
            registry = registry,
            cleanupFailure = currentFailure,
            onCleanup = { callNumber ->
                if (callNumber == 2) {
                    retryEntered.countDown()
                    check(releaseRetry.await(5, TimeUnit.SECONDS)) {
                        "exact delivery retry was not released"
                    }
                }
            },
        )
        val gateway = DeliveryTestGateway { attempt ->
            assertTrue(registry.activate(attempt, call))
        }
        val main = MainDeliveryGateDispatcher()
        val cleanupExecutor = Executors.newSingleThreadExecutor()
        val cleanupDispatcher = cleanupExecutor.asCoroutineDispatcher()
        val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)
        val frameworkExecutor = Executors.newSingleThreadExecutor()
        val replacementExecutor = Executors.newSingleThreadExecutor()
        val observed = AtomicReference<Throwable>()
        val resolverReturned = CountDownLatch(1)
        val cancellation = CancellationException("cancel after framework cleanup failure")
        val cancelReturned = CountDownLatch(1)
        val frameworkFailure = AtomicReference<Throwable>()
        var resolution: Deferred<VoiceAgentRouteResolution>? = null

        try {
            resolution = async(main) {
                try {
                    VoiceAgentAudioRouteResolver(
                        gateway = gateway,
                        registry = registry,
                        timeoutMs = 1_000,
                        cleanupScope = cleanupScope,
                        executionDispatchers = DefaultVoiceAgentRouteExecutionDispatchers.copy(
                            cleanup = cleanupDispatcher,
                        ),
                        deliveryProbe = VoiceAgentRouteDeliveryProbe { job ->
                            assertTrue(activeOutcomeClaimed.await(1, TimeUnit.SECONDS))
                            val framework = frameworkExecutor.submit {
                                runCatching(call::disconnectFromApp)
                                    .exceptionOrNull()
                                    ?.let(frameworkFailure::set)
                            }
                            assertTrue(publicationEntered.await(1, TimeUnit.SECONDS))
                            if (!pauseFailurePublication) {
                                framework.get(1, TimeUnit.SECONDS)
                            }
                            job.cancel(cancellation)
                            cancelReturned.countDown()
                        },
                    ).resolve()
                } catch (error: Throwable) {
                    observed.set(error)
                    throw error
                } finally {
                    resolverReturned.countDown()
                }
            }
            assertTrue(cancelReturned.await(1, TimeUnit.SECONDS))
            assertTrue(main.awaitIdle())
            val replacement = replacementExecutor.submit<VoiceAgentTelecomAttemptStartResult> {
                registry.beginAttempt()
            }
            assertTrue(replacementJoinEntered.await(1, TimeUnit.SECONDS))
            assertFutureBlocked(replacement)
            assertFalse(checkNotNull(resolution).isCompleted)
            assertFalse(resolverReturned.await(100, TimeUnit.MILLISECONDS))
            if (pauseFailurePublication) {
                assertEquals(1, call.disconnectCalls.get())
            } else {
                assertTrue(retryEntered.await(1, TimeUnit.SECONDS))
                assertEquals(2, call.disconnectCalls.get())
            }

            releasePublication.countDown()
            assertTrue(retryEntered.await(1, TimeUnit.SECONDS))
            assertFutureBlocked(replacement)
            assertFalse(checkNotNull(resolution).isCompleted)
            assertEquals(2, call.disconnectCalls.get())

            releaseRetry.countDown()
            assertTrue(resolverReturned.await(1, TimeUnit.SECONDS))
            val thrown = observed.get()
            assertTrue(thrown is CancellationException)
            assertEquals(cancellation.message, thrown.message)
            assertEquals(1, thrown.suppressed.size)
            assertSame(cleanupFailure, thrown.suppressed.single())
            assertSame(cleanupFailure, frameworkFailure.get())
            val blocked = replacement.get(1, TimeUnit.SECONDS)
            assertTrue(blocked is VoiceAgentTelecomAttemptStartResult.CleanupFailed)
            assertSame(cleanupFailure, (blocked as VoiceAgentTelecomAttemptStartResult.CleanupFailed).error)
            assertEquals(2, call.disconnectCalls.get())

            currentFailure.set(null)
            val replacementAttempt = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(2L, replacementAttempt.value)
            assertEquals(3, call.disconnectCalls.get())
        } finally {
            currentFailure.set(null)
            releasePublication.countDown()
            releaseRetry.countDown()
            withTimeoutOrNull(1_000) { resolution?.join() }
            main.close()
            cleanupScope.cancel()
            cleanupDispatcher.close()
            cleanupExecutor.shutdownNow()
            frameworkExecutor.shutdownNow()
            replacementExecutor.shutdownNow()
        }
    }
}

private fun assertFutureBlocked(future: java.util.concurrent.Future<*>) {
    assertTrue(
        runCatching { future.get(100, TimeUnit.MILLISECONDS) }
            .exceptionOrNull() is TimeoutException,
    )
}

private class MainDeliveryGateDispatcher : CoroutineDispatcher(), AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        executor.execute(block)
    }

    fun awaitIdle(): Boolean = executor.submit {}.run {
        runCatching { get(1, TimeUnit.SECONDS) }.isSuccess
    }

    fun execute(block: () -> Unit) {
        executor.execute(block)
    }

    override fun close() {
        executor.shutdownNow()
    }
}

private class RejectingDispatcher(
    private val failure: Throwable,
) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        throw failure
    }
}

private class AfterFirstTaskDispatcher(
    private val executor: java.util.concurrent.Executor,
    private val beforeFirstTask: () -> Unit,
    private val afterFirstTask: () -> Unit,
) : CoroutineDispatcher() {
    private val first = java.util.concurrent.atomic.AtomicBoolean(true)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        executor.execute {
            val isFirst = first.compareAndSet(true, false)
            if (isFirst) beforeFirstTask()
            block.run()
            if (isFirst) afterFirstTask()
        }
    }
}

private class FirstTasksGateDispatcher(
    private val executor: java.util.concurrent.Executor,
    taskCount: Int,
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
) : CoroutineDispatcher() {
    private val remainingGatedTasks = AtomicInteger(taskCount)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        executor.execute {
            if (remainingGatedTasks.getAndDecrement() > 0) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "admission gate was not released" }
            }
            block.run()
        }
    }
}

private class DeliveryTestGateway(
    private val onStart: (VoiceAgentTelecomAttemptId) -> Unit,
) : VoiceAgentTelecomGateway {
    override fun register() = Result.success(Unit)

    override fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit> {
        onStart(attemptId)
        return Result.success(Unit)
    }
}

private class BlockingDeliveryCall(
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
    private val failure: AtomicReference<Throwable?> = AtomicReference(null),
) : VoiceAgentTelecomCall {
    val disconnectCalls = AtomicInteger()

    override fun disconnectFromApp() {
        disconnectCalls.incrementAndGet()
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "delivery cleanup was not released" }
        failure.get()?.let { throw it }
    }
}
