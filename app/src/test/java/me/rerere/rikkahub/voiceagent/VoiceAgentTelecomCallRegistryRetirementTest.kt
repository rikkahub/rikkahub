package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentTelecomCallRegistryRetirementTest {
    @Test
    fun `route retirement requires claimed lease ownership`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall()
        assertTrue(registry.activate(attempt, call))

        val failure = runCatching {
            registry.retireOwnedAttempt(attempt, TelecomVoiceAgentRouteLease(attempt, registry))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, call.disconnectCalls)
        assertTrue(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `active outcome consumption transfers route ownership once`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall()
        assertTrue(registry.activate(attempt, call))
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()

        val duplicate = registry.consumeActiveOutcome(attempt)

        assertEquals(
            VoiceAgentRouteResolution.Superseded(
                VoiceAgentRouteMetadata(VoiceAudioRouteOwner.Telecom),
            ),
            duplicate,
        )
        assertEquals(0, call.disconnectCalls)
        lease.retire()
    }

    @Test
    fun `registry retirement rejects route owned attempt`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall()
        assertTrue(registry.activate(attempt, call))
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()

        val failure = runCatching {
            registry.retireAttempt(
                attempt,
                VoiceAgentTelecomFailure("registry_cleanup", "must retain registry ownership"),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, call.disconnectCalls)
        lease.retire()
    }

    @Test
    fun `concurrent resolvers share route cleanup failure until lease retries`() = runBlocking {
        val cleanupFailure = IllegalStateException("route cleanup failed")
        val cleanupFailureRef = AtomicReference<Throwable?>(cleanupFailure)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry()
        val previous = allocatedId(registry.beginAttempt())
        val previousCall = BlockingTelecomCall(cleanupFailureRef, cleanupEntered, releaseCleanup)
        assertTrue(registry.activate(previous, previousCall))
        val lease = registry.consumeActiveOutcome(previous).requireResolvedLease()
        val ownerGateway = CountingGateway()
        val joinerGateway = CountingGateway()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val owner = executor.submit<Throwable?> {
                runBlocking {
                    runCatching {
                        VoiceAgentAudioRouteResolver(ownerGateway, registry, 100).resolve()
                    }.exceptionOrNull()
                }
            }
            assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS))
            val joinerEntered = CountDownLatch(1)
            val joinerThread = AtomicReference<Thread>()
            val joiner = executor.submit<Throwable?> {
                runBlocking {
                    joinerThread.set(Thread.currentThread())
                    joinerEntered.countDown()
                    runCatching {
                        VoiceAgentAudioRouteResolver(joinerGateway, registry, 100).resolve()
                    }.exceptionOrNull()
                }
            }

            check(joinerEntered.await(1, TimeUnit.SECONDS)) {
                "joining resolver did not enter route resolution"
            }
            awaitBlocked(joinerThread)
            assertFalse(joiner.isDone)
            assertEquals(0, ownerGateway.registerCalls.get())
            assertEquals(0, ownerGateway.startCalls.get())
            assertEquals(0, joinerGateway.registerCalls.get())
            assertEquals(0, joinerGateway.startCalls.get())
            assertEquals(
                null,
                registry.awaitOutcomeIfPresent(VoiceAgentTelecomAttemptId(previous.value + 1)),
            )
            releaseCleanup.countDown()

            assertSame(cleanupFailure, owner.get(1, TimeUnit.SECONDS))
            assertSame(cleanupFailure, joiner.get(1, TimeUnit.SECONDS))
            assertEquals(1, previousCall.disconnectCalls)

            assertCleanupFailure(registry.beginAttempt(), cleanupFailure)
            assertEquals(1, previousCall.disconnectCalls)

            cleanupFailureRef.set(null)
            lease.retire()
            assertEquals(2, previousCall.disconnectCalls)
            assertEquals(previous.value + 1, allocatedId(registry.beginAttempt()).value)
        } finally {
            releaseCleanup.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `resolver propagates route cleanup failure without allocating or reaching gateway`() = runBlocking {
        val cleanupFailure = IllegalStateException("dirty route cleanup")
        val cleanupFailureRef = AtomicReference<Throwable?>(cleanupFailure)
        val registry = VoiceAgentTelecomCallRegistry()
        val previous = allocatedId(registry.beginAttempt())
        val previousCall = ThrowingTelecomCall(cleanupFailureRef)
        assertTrue(registry.activate(previous, previousCall))
        val lease = registry.consumeActiveOutcome(previous).requireResolvedLease()
        val gateway = CountingGateway()

        val thrown = runCatching {
            VoiceAgentAudioRouteResolver(gateway, registry, 100).resolve()
        }.exceptionOrNull()

        assertSame(cleanupFailure, thrown)
        assertEquals(0, gateway.registerCalls.get())
        assertEquals(0, gateway.startCalls.get())
        assertEquals(1, previousCall.disconnectCalls)

        cleanupFailureRef.set(null)
        lease.retire()
        assertEquals(previous.value + 1, allocatedId(registry.beginAttempt()).value)
    }

    @Test
    fun `rejected cleanup scheduling stays joined until exact failure publication finishes`() = runBlocking {
        val schedulingFailure = IllegalStateException("cleanup scheduling rejected")
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry(
            probe = VoiceAgentTelecomRegistryProbe { event ->
                if (event is VoiceAgentTelecomRegistryProbeEvent.UndeliveredCleanupFailurePublishing) {
                    publicationEntered.countDown()
                    check(releasePublication.await(5, TimeUnit.SECONDS)) {
                        "rejected cleanup publication was not released"
                    }
                }
            },
        )
        val previous = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall()
        assertTrue(registry.activate(previous, call))
        val lease = registry.consumeActiveOutcome(previous).requireResolvedLease() as TelecomVoiceAgentRouteLease
        val claim = lease.claimUndeliveredCleanup().claim
        val publisherFailure = AtomicReference<Throwable>()
        val publisher = thread {
            runCatching {
                lease.rejectUndeliveredCleanupScheduling(claim, schedulingFailure)
            }.onFailure(publisherFailure::set)
        }
        val executor = Executors.newSingleThreadExecutor()

        try {
            assertTrue(publicationEntered.await(1, TimeUnit.SECONDS))
            val joinerThread = AtomicReference<Thread>()
            val joiner = executor.submit<VoiceAgentTelecomAttemptStartResult> {
                joinerThread.set(Thread.currentThread())
                registry.beginAttempt()
            }

            awaitBlocked(joinerThread)
            assertFalse(joiner.isDone)
            assertEquals(0, call.disconnectCalls)
            assertEquals(null, registry.awaitOutcomeIfPresent(VoiceAgentTelecomAttemptId(previous.value + 1)))

            releasePublication.countDown()
            val joined = joiner.get(1, TimeUnit.SECONDS)
            assertCleanupFailure(joined, schedulingFailure)
            publisher.join(1_000)
            assertFalse(publisher.isAlive)
            throwWorkerFailure(publisherFailure, "rejected cleanup publisher")
            assertEquals(0, call.disconnectCalls)

            val replacement = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(previous.value + 1, replacement.value)
            assertEquals(1, call.disconnectCalls)
        } finally {
            releasePublication.countDown()
            executor.shutdownNow()
            publisher.join(1_000)
        }
    }

    @Test
    fun `rejected JoinRetirement claim lets external success terminalize exact lease`() = runBlocking {
        val schedulingFailure = IllegalStateException("joined cleanup scheduling rejected")
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val replacementJoinEntered = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry(
            probe = VoiceAgentTelecomRegistryProbe { event ->
                if (event is VoiceAgentTelecomRegistryProbeEvent.RouteRetirementJoining) {
                    replacementJoinEntered.countDown()
                }
            },
        )
        val previous = registry.beginAttempt().requireAllocatedAttemptId()
        val call = BlockingTelecomCall(AtomicReference(null), cleanupEntered, releaseCleanup)
        assertTrue(registry.activate(previous, call))
        val lease = registry.consumeActiveOutcome(previous).requireResolvedLease() as
            TelecomVoiceAgentRouteLease
        val executor = Executors.newFixedThreadPool(2)
        val externalRetirement = executor.submit(lease::retire)

        try {
            assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS))
            val acquisition = lease.claimUndeliveredCleanup()
            assertTrue(acquisition.step is UndeliveredRouteCleanupStep.JoinRetirement)
            val blockedReplacement = executor.submit<VoiceAgentTelecomAttemptStartResult> {
                registry.beginAttempt()
            }
            assertTrue(replacementJoinEntered.await(1, TimeUnit.SECONDS))
            assertFalse(blockedReplacement.isDone)

            lease.rejectUndeliveredCleanupScheduling(acquisition.claim, schedulingFailure)

            assertSame(schedulingFailure, acquisition.claim.awaitResult().exceptionOrNull())
            assertCleanupFailure(blockedReplacement.get(1, TimeUnit.SECONDS), schedulingFailure)
            assertEquals(1, call.disconnectCalls)
            assertEquals(
                null,
                registry.awaitOutcomeIfPresent(VoiceAgentTelecomAttemptId(previous.value + 1)),
            )

            releaseCleanup.countDown()
            externalRetirement.get(1, TimeUnit.SECONDS)
            assertEquals(1, call.disconnectCalls)
            val replacement = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(previous.value + 1, replacement.value)
        } finally {
            releaseCleanup.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `rejected JoinFailurePublication claim retains exact lease for later successful retry`() = runBlocking {
        val cleanupFailure = IllegalStateException("external route cleanup failed")
        val schedulingFailure = IllegalStateException("publishing cleanup scheduling rejected")
        val cleanupFailureRef = AtomicReference<Throwable?>(cleanupFailure)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val replacementJoinEntered = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry(
            probe = VoiceAgentTelecomRegistryProbe { event ->
                when (event) {
                    VoiceAgentTelecomRegistryProbeEvent.FailedRetirementResultPublishing -> {
                        publicationEntered.countDown()
                        check(releasePublication.await(5, TimeUnit.SECONDS)) {
                            "external cleanup failure publication was not released"
                        }
                    }
                    VoiceAgentTelecomRegistryProbeEvent.RouteRetirementJoining -> {
                        replacementJoinEntered.countDown()
                    }
                    else -> Unit
                }
            },
        )
        val previous = registry.beginAttempt().requireAllocatedAttemptId()
        val call = BlockingTelecomCall(cleanupFailureRef, cleanupEntered, releaseCleanup)
        assertTrue(registry.activate(previous, call))
        val lease = registry.consumeActiveOutcome(previous).requireResolvedLease() as
            TelecomVoiceAgentRouteLease
        val executor = Executors.newFixedThreadPool(2)
        val externalRetirement = executor.submit<Throwable?> {
            runCatching(lease::retire).exceptionOrNull()
        }

        try {
            assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS))
            releaseCleanup.countDown()
            assertTrue(publicationEntered.await(1, TimeUnit.SECONDS))
            val acquisition = lease.claimUndeliveredCleanup()
            assertTrue(acquisition.step is UndeliveredRouteCleanupStep.JoinFailurePublication)
            val blockedReplacement = executor.submit<VoiceAgentTelecomAttemptStartResult> {
                registry.beginAttempt()
            }
            assertTrue(replacementJoinEntered.await(1, TimeUnit.SECONDS))
            assertFalse(blockedReplacement.isDone)

            lease.rejectUndeliveredCleanupScheduling(acquisition.claim, schedulingFailure)

            assertSame(schedulingFailure, acquisition.claim.awaitResult().exceptionOrNull())
            assertCleanupFailure(blockedReplacement.get(1, TimeUnit.SECONDS), schedulingFailure)
            assertEquals(1, call.disconnectCalls)
            assertEquals(
                null,
                registry.awaitOutcomeIfPresent(VoiceAgentTelecomAttemptId(previous.value + 1)),
            )

            releasePublication.countDown()
            assertSame(cleanupFailure, externalRetirement.get(1, TimeUnit.SECONDS))
            assertEquals(1, call.disconnectCalls)

            cleanupFailureRef.set(null)
            lease.retire()
            assertEquals(2, call.disconnectCalls)
            val replacement = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(previous.value + 1, replacement.value)
        } finally {
            releaseCleanup.countDown()
            releasePublication.countDown()
            cleanupFailureRef.set(null)
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed retirement result publication does not hold registry lock`() {
        val cleanupFailure = IllegalStateException("failed retirement publication")
        val publicationObserved = CountDownLatch(1)
        val probeFailure = AtomicReference<Throwable>()
        val registryRef = AtomicReference<VoiceAgentTelecomCallRegistry>()
        val attemptRef = AtomicReference<VoiceAgentTelecomAttemptId>()
        val lockExecutor = Executors.newSingleThreadExecutor()
        val registry = VoiceAgentTelecomCallRegistry(
            probe = VoiceAgentTelecomRegistryProbe { event ->
                if (event is VoiceAgentTelecomRegistryProbeEvent.FailedRetirementResultPublishedBeforeFinalization) {
                    runCatching {
                        val lockWasAvailable = lockExecutor.submit<Boolean> {
                            registryRef.get().isOwnedAttemptActive(attemptRef.get())
                        }.get(1, TimeUnit.SECONDS)
                        assertFalse(lockWasAvailable)
                    }.onFailure(probeFailure::set)
                    publicationObserved.countDown()
                }
            },
        )
        registryRef.set(registry)
        val previous = registry.beginAttempt().requireAllocatedAttemptId()
        attemptRef.set(previous)
        val call = FakeTelecomCall { throw cleanupFailure }
        assertTrue(registry.activate(previous, call))

        try {
            val result = registry.beginAttempt()

            assertCleanupFailure(result, cleanupFailure)
            assertTrue(publicationObserved.await(1, TimeUnit.SECONDS))
            throwWorkerFailure(probeFailure, "post-publication lock probe")
            assertEquals(1, call.disconnectCalls)
        } finally {
            lockExecutor.shutdownNow()
        }
    }

    @Test
    fun `retired undelivered tombstone derives ownership from one retry identity`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val id = registry.beginAttempt().requireAllocatedAttemptId()
        val lease = TelecomVoiceAgentRouteLease(id, registry)
        val attempt = SynchronousAttemptResult()
        val retry = RetryingUndeliveredRouteOwnership(lease, attempt)
        val phase = AttemptPhase.RetiredUndeliveredRoute(
            failure = VoiceAgentTelecomFailure("test", "typed tombstone"),
            retry = retry,
        )

        assertSame(lease, phase.ownership.lease)
        val delivery = phase.ownership.delivery as RouteLeaseDelivery.RetryingUndelivered
        assertSame(attempt, delivery.attempt)
        assertSame(attempt, phase.retry.attempt)
    }

    @Test
    fun `successful retained route retry stays joined until outer result publication`() = runBlocking {
        val firstFailure = IllegalStateException("initial undelivered cleanup failed")
        val cleanupFailure = AtomicReference<Throwable?>(firstFailure)
        val publicationEntered = CountDownLatch(1)
        val retryJoined = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry(
            probe = VoiceAgentTelecomRegistryProbe { event ->
                when (event) {
                    VoiceAgentTelecomRegistryProbeEvent.RouteRetirementJoining -> retryJoined.countDown()
                    VoiceAgentTelecomRegistryProbeEvent.UndeliveredRouteRetryResultPublishing -> {
                        publicationEntered.countDown()
                        check(releasePublication.await(5, TimeUnit.SECONDS)) {
                            "successful retained retry publication was not released"
                        }
                    }
                    else -> Unit
                }
            },
        )
        val previous = registry.beginAttempt().requireAllocatedAttemptId()
        val call = ThrowingTelecomCall(cleanupFailure)
        assertTrue(registry.activate(previous, call))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(previous))
        val lease = registry.consumeActiveOutcome(previous).requireResolvedLease()
        val firstRetirement = lease.retireUndelivered()
        assertTrue(firstRetirement is UndeliveredRouteRetirement.Retained)
        assertSame(firstFailure, (firstRetirement as UndeliveredRouteRetirement.Retained).error)
        cleanupFailure.set(null)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val owner = executor.submit<VoiceAgentTelecomAttemptStartResult>(registry::beginAttempt)
            assertTrue(publicationEntered.await(1, TimeUnit.SECONDS))
            val joiner = executor.submit<VoiceAgentTelecomAttemptStartResult>(registry::beginAttempt)

            assertTrue(retryJoined.await(1, TimeUnit.SECONDS))
            assertFalse(owner.isDone)
            assertFalse(joiner.isDone)
            assertEquals(2, call.disconnectCalls)

            releasePublication.countDown()
            val allocated = listOf(owner.get(1, TimeUnit.SECONDS), joiner.get(1, TimeUnit.SECONDS))
                .map(VoiceAgentTelecomAttemptStartResult::requireAllocatedAttemptId)
                .sortedBy(VoiceAgentTelecomAttemptId::value)

            assertEquals(listOf(previous.value + 1, previous.value + 2), allocated.map { it.value })
            assertEquals(2, allocated.distinct().size)
            assertEquals(2, call.disconnectCalls)
            val superseded = registry.awaitOutcome(allocated.first())
            assertTrue(superseded is VoiceAgentTelecomOutcome.Failed)
            registry.retireAttempt(
                allocated.last(),
                VoiceAgentTelecomFailure("test_cleanup", "test cleanup"),
            )
            registry.awaitOutcome(allocated.last())
        } finally {
            releasePublication.countDown()
            cleanupFailure.set(null)
            executor.shutdownNow()
        }
        Unit
    }

    @Test
    fun `begin joins synchronous failure while exact result publication is delayed`() {
        val failure = IllegalStateException("synchronous publication failure")
        val cleanupFailure = AtomicReference<Throwable?>(failure)
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry(
            probe = VoiceAgentTelecomRegistryProbe { event ->
                if (event is VoiceAgentTelecomRegistryProbeEvent.FailedRetirementResultPublishing) {
                    publicationEntered.countDown()
                    check(releasePublication.await(5, TimeUnit.SECONDS)) {
                        "synchronous failure publication was not released"
                    }
                }
            },
        )
        val previous = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall {
            cleanupFailure.get()?.let { throw it }
        }
        registry.activate(previous, call)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val owner = executor.submit<Throwable?> {
                runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull()
            }
            check(publicationEntered.await(1, TimeUnit.SECONDS)) {
                "synchronous failure publication did not start"
            }
            val joinerThread = AtomicReference<Thread>()
            val joiner = executor.submit<Throwable?> {
                joinerThread.set(Thread.currentThread())
                runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull()
            }

            awaitBlocked(joinerThread)
            assertEquals(1, call.disconnectCalls)
            releasePublication.countDown()

            assertSame(failure, owner.get(1, TimeUnit.SECONDS))
            assertSame(failure, joiner.get(1, TimeUnit.SECONDS))
            assertEquals(1, call.disconnectCalls)
            assertFalse(registry.isOwnedAttemptActive(VoiceAgentTelecomAttemptId(previous.value + 1)))

            cleanupFailure.set(null)
            val replacement = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(previous.value + 1, replacement.value)
            assertEquals(2, call.disconnectCalls)
        } finally {
            releasePublication.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `begin joins callback failure while exact result publication is delayed`() {
        val failure = IllegalStateException("callback publication failure")
        val cleanupFailure = AtomicReference<Throwable?>(failure)
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry(
            probe = VoiceAgentTelecomRegistryProbe { event ->
                if (event is VoiceAgentTelecomRegistryProbeEvent.FailedRetirementResultPublishing) {
                    publicationEntered.countDown()
                    check(releasePublication.await(5, TimeUnit.SECONDS)) {
                        "callback failure publication was not released"
                    }
                }
            },
        )
        val previous = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall {
            cleanupFailure.get()?.let { throw it }
        }
        registry.activate(previous, call)
        registry.retiring(call)
        val callbackFailure = AtomicReference<Throwable>()
        val callback = thread {
            runCatching {
                registry.retired(call, Result.failure(failure))
            }.onFailure(callbackFailure::set)
        }

        check(publicationEntered.await(1, TimeUnit.SECONDS)) {
            "callback failure publication did not start"
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val joinerThread = AtomicReference<Thread>()
            val joiner = executor.submit<Throwable?> {
                joinerThread.set(Thread.currentThread())
                runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull()
            }

            awaitBlocked(joinerThread)
            assertEquals(0, call.disconnectCalls)
            releasePublication.countDown()

            assertSame(failure, joiner.get(1, TimeUnit.SECONDS))
            callback.join(1_000)
            assertFalse(callback.isAlive)
            throwWorkerFailure(callbackFailure, "Telecom callback")
            assertEquals(0, call.disconnectCalls)
            assertFalse(registry.isOwnedAttemptActive(VoiceAgentTelecomAttemptId(previous.value + 1)))

            cleanupFailure.set(null)
            val replacement = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(previous.value + 1, replacement.value)
            assertEquals(1, call.disconnectCalls)
        } finally {
            releasePublication.countDown()
            executor.shutdownNow()
            callback.join(1_000)
        }
    }

    @Test
    fun `awakened synchronous joiner immediately retries finalized failure`() {
        assertAwakenedJoinerImmediatelyRetries(callbackCompletion = false)
    }

    @Test
    fun `awakened callback joiner immediately retries finalized failure`() {
        assertAwakenedJoinerImmediatelyRetries(callbackCompletion = true)
    }

    private fun allocatedId(result: VoiceAgentTelecomAttemptStartResult): VoiceAgentTelecomAttemptId {
        assertTrue(result is VoiceAgentTelecomAttemptStartResult.Allocated)
        return (result as VoiceAgentTelecomAttemptStartResult.Allocated).attemptId
    }

    private fun assertCleanupFailure(result: VoiceAgentTelecomAttemptStartResult, expected: Throwable) {
        assertTrue(result is VoiceAgentTelecomAttemptStartResult.CleanupFailed)
        assertSame(expected, (result as VoiceAgentTelecomAttemptStartResult.CleanupFailed).error)
    }

    private class BlockingTelecomCall(
        private val cleanupFailure: AtomicReference<Throwable?>,
        private val cleanupEntered: CountDownLatch,
        private val releaseCleanup: CountDownLatch,
    ) : VoiceAgentTelecomCall {
        var disconnectCalls = 0

        override fun disconnectFromApp() {
            disconnectCalls += 1
            cleanupEntered.countDown()
            check(releaseCleanup.await(5, TimeUnit.SECONDS)) { "route cleanup was not released" }
            cleanupFailure.get()?.let { throw it }
        }
    }

    private class ThrowingTelecomCall(
        private val cleanupFailure: AtomicReference<Throwable?>,
    ) : VoiceAgentTelecomCall {
        var disconnectCalls = 0

        override fun disconnectFromApp() {
            disconnectCalls += 1
            cleanupFailure.get()?.let { throw it }
        }
    }

    private class CountingGateway : VoiceAgentTelecomGateway {
        val registerCalls = AtomicInteger()
        val startCalls = AtomicInteger()

        override fun register(): Result<Unit> {
            registerCalls.incrementAndGet()
            return Result.success(Unit)
        }

        override fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit> {
            startCalls.incrementAndGet()
            return Result.success(Unit)
        }
    }
}
