package me.rerere.rikkahub.voiceagent

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentTelecomCallRegistryTest {
    @Test
    fun `automation route returns false without an active routable connection`() {
        val registry = VoiceAgentTelecomCallRegistry()

        assertNull(registry.readActiveAutomationRoutes())
        assertFalse(registry.requestActiveAudioRoute(VoiceAgentCallEndpointType.Speaker))

        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        assertTrue(registry.activate(attempt, FakeTelecomCall()))
        assertNull(registry.readActiveAutomationRoutes())
        assertFalse(registry.requestActiveAudioRoute(VoiceAgentCallEndpointType.Earpiece))
    }

    @Test
    fun `automation route targets only the active routable connection`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val requestedRoutes = mutableListOf<VoiceAgentCallEndpointType>()
        val call = object : VoiceAgentTelecomCall, VoiceAgentAutomationRoutableCall {
            override fun requestAutomationRoute(type: VoiceAgentCallEndpointType): Boolean {
                requestedRoutes += type
                return true
            }

            override fun availableAutomationRoutes(): Set<VoiceAgentCallEndpointType> =
                setOf(VoiceAgentCallEndpointType.Bluetooth, VoiceAgentCallEndpointType.WiredHeadset)

            override fun disconnectFromApp() = Unit
        }
        assertNull(registry.readActiveAutomationRoutes())
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()

        assertTrue(registry.activate(attempt, call))
        assertEquals(
            setOf(VoiceAgentCallEndpointType.Bluetooth, VoiceAgentCallEndpointType.WiredHeadset),
            registry.readActiveAutomationRoutes(),
        )
        assertTrue(registry.requestActiveAudioRoute(VoiceAgentCallEndpointType.Speaker))
        assertEquals(listOf(VoiceAgentCallEndpointType.Speaker), requestedRoutes)
    }

    @Test
    fun `registry exposes only result aware retirement completion`() {
        assertFalse(
            VoiceAgentTelecomCallRegistry::class.java.declaredMethods.any { method ->
                method.name == "clear"
            },
        )
    }

    @Test
    fun `matching connection completes pending attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall()

        assertEquals(true, registry.activate(attempt, call))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))
        assertTrue(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `acknowledged active outcome still retires exact connection`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall()
        assertTrue(registry.activate(attempt, call))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()

        lease.retire()

        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(attempt))
        assertAttemptWasConsumed(registry, attempt)
    }

    @Test
    fun `failed exact Telecom retirement can retry same connection`() {
        val firstFailure = IllegalStateException("first retirement failed")
        val retirementFailure = AtomicReference<Throwable?>(firstFailure)
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall {
            retirementFailure.get()?.let { failure -> throw failure }
        }
        assertTrue(registry.activate(attempt, call))
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()

        assertSame(firstFailure, runCatching(lease::retire).exceptionOrNull())
        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(attempt))

        assertSame(
            firstFailure,
            runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull(),
        )
        retirementFailure.set(null)

        lease.retire()
        assertEquals(2, call.disconnectCalls)
        val replacementAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        val replacementCall = FakeTelecomCall()
        assertTrue(registry.activate(replacementAttempt, replacementCall))
        assertEquals(0, replacementCall.disconnectCalls)

        lease.retire()
        assertEquals(2, call.disconnectCalls)
        assertEquals(0, replacementCall.disconnectCalls)
    }

    @Test
    fun `retiring old attempt leaves newer active connection untouched`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val oldAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        val oldCall = FakeTelecomCall()
        assertTrue(registry.activate(oldAttempt, oldCall))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(oldAttempt))
        val oldLease = registry.consumeActiveOutcome(oldAttempt).requireResolvedLease()
        val newerAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        val newerCall = FakeTelecomCall()
        assertTrue(registry.activate(newerAttempt, newerCall))

        oldLease.retire()

        assertEquals(1, oldCall.disconnectCalls)
        assertEquals(0, newerCall.disconnectCalls)
        assertTrue(registry.isOwnedAttemptActive(newerAttempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(newerAttempt))
    }

    @Test
    fun `old pre-lease callback completes before newer attempt allocation`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val oldAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        val oldCall = FakeTelecomCall()
        assertTrue(registry.activate(oldAttempt, oldCall))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(oldAttempt))
        registry.retiring(oldCall)
        val beginThread = AtomicReference<Thread>()
        val newerAttempt = async(Dispatchers.Default) {
            beginThread.set(Thread.currentThread())
            registry.beginAttempt().requireAllocatedAttemptId()
        }

        awaitWaiting(beginThread)
        assertFalse(newerAttempt.isCompleted)
        registry.retired(oldCall, Result.success(Unit))

        val allocatedAttempt = newerAttempt.await()
        val newerCall = FakeTelecomCall()
        assertTrue(registry.activate(allocatedAttempt, newerCall))

        assertEquals(0, newerCall.disconnectCalls)
        assertTrue(registry.isOwnedAttemptActive(allocatedAttempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(allocatedAttempt))
        registry.consumeActiveOutcome(allocatedAttempt).requireResolvedLease().retire()
        assertEquals(1, newerCall.disconnectCalls)
    }

    @Test
    fun `retiring pre-lease callback retains ownership until completion`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val oldAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        val oldCall = FakeTelecomCall()
        assertTrue(registry.activate(oldAttempt, oldCall))

        registry.retiring(oldCall)

        assertFalse(registry.isOwnedAttemptActive(oldAttempt))
        val beginThread = AtomicReference<Thread>()
        val replacementAttempt = async(Dispatchers.Default) {
            beginThread.set(Thread.currentThread())
            registry.beginAttempt().requireAllocatedAttemptId()
        }

        awaitWaiting(beginThread)
        assertFalse(replacementAttempt.isCompleted)
        registry.retired(oldCall, Result.success(Unit))

        val allocatedAttempt = replacementAttempt.await()
        val replacementCall = FakeTelecomCall()
        assertTrue(registry.activate(allocatedAttempt, replacementCall))

        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(oldAttempt))
        assertAttemptWasConsumed(registry, oldAttempt)
        assertEquals(0, replacementCall.disconnectCalls)
        assertTrue(registry.isOwnedAttemptActive(allocatedAttempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(allocatedAttempt))
    }

    @Test
    fun `acknowledging active outcome retains connection ownership`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall()
        registry.activate(attempt, call)

        assertEquals(VoiceAgentTelecomOutcome.Active, registry.observeOutcome(attempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.observeOutcome(attempt))
        assertTrue(registry.isOwnedAttemptActive(attempt))

        registry.acknowledgeOutcome(attempt)

        assertTrue(registry.isOwnedAttemptActive(attempt))
        registry.consumeActiveOutcome(attempt).requireResolvedLease().retire()
        assertFalse(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `acknowledging pending attempt does not remove it`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall()

        registry.acknowledgeOutcome(attempt)

        assertTrue(registry.activate(attempt, call))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))
    }

    @Test
    fun `acknowledging active outcome while retiring consumes after disconnect`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val disconnectEntered = CountDownLatch(1)
        val releaseDisconnect = CountDownLatch(1)
        val retirementFailure = AtomicReference<Throwable>()
        val call = object : VoiceAgentTelecomCall {
            override fun disconnectFromApp() {
                disconnectEntered.countDown()
                check(releaseDisconnect.await(1, TimeUnit.SECONDS)) {
                    "disconnect was not released"
                }
            }
        }
        assertTrue(registry.activate(attempt, call))
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()
        val retirement = thread {
            runCatching(lease::retire)
                .onFailure(retirementFailure::set)
        }
        var primaryFailure: Throwable? = null

        try {
            assertTrue(disconnectEntered.await(1, TimeUnit.SECONDS))
            assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            releaseDisconnect.countDown()
            finishWorker(
                worker = retirement,
                workerFailure = retirementFailure,
                description = "retirement",
                primaryFailure = primaryFailure,
            )
        }

        assertAttemptWasConsumed(registry, attempt)
    }

    @Test
    fun `scoped retirement disconnects acknowledged active attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val events = mutableListOf<String>()
        val call = FakeTelecomCall { events += "disconnect" }
        val failure = VoiceAgentTelecomFailure("telecom_resolution_cancelled", "caller cancelled")
        registry.activate(attempt, call)
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))

        registry.retireAttempt(attempt, failure)

        assertEquals(listOf("disconnect"), events)
        assertFalse(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `scoped retirement of stale attempt leaves newer active call untouched`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val staleAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        val currentAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        val currentCall = FakeTelecomCall()
        val failure = VoiceAgentTelecomFailure("telecom_resolution_cancelled", "caller cancelled")
        assertEquals(
            "telecom_attempt_superseded",
            (registry.awaitOutcome(staleAttempt) as VoiceAgentTelecomOutcome.Failed).failure.diagnosticName,
        )
        registry.activate(currentAttempt, currentCall)

        registry.retireAttempt(staleAttempt, failure)

        assertAttemptWasConsumed(registry, staleAttempt)
        assertTrue(registry.isOwnedAttemptActive(currentAttempt))
        assertEquals(0, currentCall.disconnectCalls)
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(currentAttempt))
    }

    @Test
    fun `active outcome is published only after activation callback returns`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val accepted = AtomicBoolean()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitOutcome(attempt)
        }

        val activation = thread {
            accepted.set(
                registry.activate(attempt, call) {
                    callbackEntered.countDown()
                    releaseCallback.await()
                },
            )
        }

        callbackEntered.await()
        assertFalse(registry.isOwnedAttemptActive(attempt))
        assertFalse(outcome.isCompleted)

        releaseCallback.countDown()
        activation.join()

        assertTrue(accepted.get())
        assertEquals(VoiceAgentTelecomOutcome.Active, outcome.await())
        assertTrue(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `active outcome resumes unconfined waiter outside registry lock`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val callbackAcquiredRegistry = CountDownLatch(1)
        val callbackFailure = AtomicReference<Throwable>()
        var callbackThread: Thread? = null
        val call = object : VoiceAgentTelecomCall {
            var disconnectCalls = 0

            override fun disconnectFromApp() {
                disconnectCalls++
                callbackThread = thread {
                    runCatching { registry.retired(this, Result.success(Unit)) }
                        .onFailure(callbackFailure::set)
                    callbackAcquiredRegistry.countDown()
                }
                check(callbackAcquiredRegistry.await(1, TimeUnit.SECONDS)) {
                    "Telecom callback could not acquire the registry lock"
                }
            }
        }
        val outcome = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            registry.observeOutcome(attempt).also {
                registry.consumeActiveOutcome(attempt).requireResolvedLease().retire()
            }
        }

        assertFalse(outcome.isCompleted)
        var primaryFailure: Throwable? = null
        try {
            assertTrue(registry.activate(attempt, call))
        } catch (failure: Throwable) {
            primaryFailure = failure
            outcome.cancel()
            throw failure
        } finally {
            callbackThread?.let { callback ->
                finishWorker(
                    worker = callback,
                    workerFailure = callbackFailure,
                    description = "Telecom callback",
                    primaryFailure = primaryFailure,
                )
            }
        }

        assertEquals(
            VoiceAgentTelecomOutcome.Active,
            withTimeoutOrNull(1_000) { outcome.await() },
        )
        assertEquals(1, call.disconnectCalls)
        assertTrue(callbackAcquiredRegistry.await(0, TimeUnit.SECONDS))
    }

    @Test
    fun `retirement after activation selection cannot replace delayed active notification`() = runBlocking {
        val selectionCommitted = CountDownLatch(1)
        val releaseNotification = CountDownLatch(1)
        var attempt: VoiceAgentTelecomAttemptId? = null
        val committedAttempt = AtomicReference<VoiceAgentTelecomAttemptId>()
        val committedOutcome = AtomicReference<VoiceAgentTelecomOutcome>()
        val registry = VoiceAgentTelecomCallRegistry(
            probe = VoiceAgentTelecomRegistryProbe { event ->
                if (event is VoiceAgentTelecomRegistryProbeEvent.ActivationOutcomeSelected) {
                    committedAttempt.set(event.attemptId)
                    committedOutcome.set(event.outcome)
                    selectionCommitted.countDown()
                    check(releaseNotification.await(1, TimeUnit.SECONDS)) {
                        "outcome notification was not released"
                    }
                }
            }
        )
        attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall()
        val accepted = AtomicBoolean()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
            registry.observeOutcome(requireNotNull(attempt))
        }
        val activationFailure = AtomicReference<Throwable>()
        val activation = thread {
            runCatching {
                accepted.set(registry.activate(requireNotNull(attempt), call))
            }.onFailure(activationFailure::set)
        }
        var retirement: Thread? = null
        val retirementFailure = AtomicReference<Throwable>()
        var primaryFailure: Throwable? = null

        try {
            assertTrue(selectionCommitted.await(1, TimeUnit.SECONDS))
            assertEquals(attempt, committedAttempt.get())
            assertEquals(VoiceAgentTelecomOutcome.Active, committedOutcome.get())
            val lease = registry.consumeActiveOutcome(requireNotNull(attempt)).requireResolvedLease()
            val retirementThread = thread {
                runCatching(lease::retire)
                    .onFailure(retirementFailure::set)
            }
            retirement = retirementThread
            retirementThread.join(1_000)

            assertFalse("retirement waited for outcome notification", retirementThread.isAlive)
            throwWorkerFailure(retirementFailure, "retirement")
            assertEquals(1, call.disconnectCalls)
            assertFalse(outcome.isCompleted)
        } catch (failure: Throwable) {
            primaryFailure = failure
            outcome.cancel()
            throw failure
        } finally {
            releaseNotification.countDown()
            var cleanupFailure = retirement?.let { worker ->
                runCatching {
                    finishWorker(
                        worker = worker,
                        workerFailure = retirementFailure,
                        description = "retirement",
                    )
                }.exceptionOrNull()
            }
            runCatching {
                finishWorker(
                    worker = activation,
                    workerFailure = activationFailure,
                    description = "activation",
                )
            }.exceptionOrNull()?.let { failure ->
                cleanupFailure = cleanupFailure.append(failure)
            }
            if (primaryFailure != null) {
                cleanupFailure?.let(primaryFailure::addSuppressed)
            } else {
                cleanupFailure?.let { throw it }
            }
        }

        assertTrue(accepted.get())
        assertEquals(
            VoiceAgentTelecomOutcome.Active,
            withTimeoutOrNull(1_000) { outcome.await() },
        )
    }

    @Test
    fun `activation and retirement linearize to exactly one outcome`() = runBlocking {
        val retirementFirstRegistry = VoiceAgentTelecomCallRegistry()
        val retirementFirstAttempt = retirementFirstRegistry.beginAttempt().requireAllocatedAttemptId()
        val retirementFirstCall = FakeTelecomCall()
        val activationEntered = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        val retirementFirstAccepted = AtomicBoolean(true)
        val retirementFirstOutcome = async(start = CoroutineStart.UNDISPATCHED) {
            retirementFirstRegistry.awaitOutcome(retirementFirstAttempt)
        }
        val retirementFirstActivationFailure = AtomicReference<Throwable>()
        val retirementFirstActivation = thread {
            runCatching {
                retirementFirstAccepted.set(
                    retirementFirstRegistry.activate(retirementFirstAttempt, retirementFirstCall) {
                        activationEntered.countDown()
                        check(releaseActivation.await(1, TimeUnit.SECONDS)) {
                            "retirement-first activation was not released"
                        }
                    },
                )
            }.onFailure(retirementFirstActivationFailure::set)
        }
        var retirementFirstPrimaryFailure: Throwable? = null

        try {
            assertTrue(activationEntered.await(1, TimeUnit.SECONDS))
            retirementFirstRegistry.retireAttempt(
                retirementFirstAttempt,
                VoiceAgentTelecomFailure(
                    diagnosticName = "telecom_attempt_cancelled",
                    detail = "retirement won activation race",
                ),
            )
            assertFalse(retirementFirstOutcome.isCompleted)
        } catch (failure: Throwable) {
            retirementFirstPrimaryFailure = failure
            retirementFirstOutcome.cancel()
            throw failure
        } finally {
            releaseActivation.countDown()
            finishWorker(
                worker = retirementFirstActivation,
                workerFailure = retirementFirstActivationFailure,
                description = "retirement-first activation",
                primaryFailure = retirementFirstPrimaryFailure,
            )
        }

        assertFalse(retirementFirstAccepted.get())
        assertEquals(1, retirementFirstCall.disconnectCalls)
        assertEquals(
            "telecom_attempt_cancelled",
            (withTimeoutOrNull(1_000) { retirementFirstOutcome.await() }
                as VoiceAgentTelecomOutcome.Failed).failure.diagnosticName,
        )

        val publicationFirstRegistry = VoiceAgentTelecomCallRegistry()
        val publicationFirstAttempt = publicationFirstRegistry.beginAttempt().requireAllocatedAttemptId()
        val publicationFirstCall = FakeTelecomCall()
        val publicationFirstAccepted = AtomicBoolean()
        val publicationFirstActivationFailure = AtomicReference<Throwable>()
        val publicationFirstActivation = thread {
            runCatching {
                publicationFirstAccepted.set(
                    publicationFirstRegistry.activate(publicationFirstAttempt, publicationFirstCall),
                )
            }.onFailure(publicationFirstActivationFailure::set)
        }

        finishWorker(
            worker = publicationFirstActivation,
            workerFailure = publicationFirstActivationFailure,
            description = "publication-first activation",
        )
        assertTrue(publicationFirstAccepted.get())
        assertEquals(
            VoiceAgentTelecomOutcome.Active,
            publicationFirstRegistry.observeOutcome(publicationFirstAttempt),
        )

        publicationFirstRegistry.consumeActiveOutcome(publicationFirstAttempt).requireResolvedLease().retire()

        assertEquals(1, publicationFirstCall.disconnectCalls)
        assertAttemptWasConsumed(publicationFirstRegistry, publicationFirstAttempt)
    }

    @Test
    fun `matching failure completes pending attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val failure = VoiceAgentTelecomFailure("telecom_outgoing_failed", "rejected")

        registry.fail(attempt, failure)

        assertEquals(VoiceAgentTelecomOutcome.Failed(failure), registry.awaitOutcome(attempt))
    }

    @Test
    fun `matching active retirement completion releases ownership and preserves selected outcome`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall()
        assertTrue(registry.activate(attempt, call))

        registry.retired(call, Result.success(Unit))

        assertFalse(registry.isOwnedAttemptActive(attempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))
        assertAttemptWasConsumed(registry, attempt)
    }

    @Test
    fun `matching activating retirement completion publishes failure and cleans exact call`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall()
        val activationEntered = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        val activationFailure = AtomicReference<Throwable>()
        val activation = thread {
            runCatching {
                accepted.set(
                    registry.activate(attempt, call) {
                        activationEntered.countDown()
                        check(releaseActivation.await(1, TimeUnit.SECONDS)) {
                            "matching activation retirement completion was not released"
                        }
                    },
                )
            }.onFailure(activationFailure::set)
        }
        var primaryFailure: Throwable? = null

        try {
            assertTrue(activationEntered.await(1, TimeUnit.SECONDS))
            registry.retired(call, Result.success(Unit))

            val failed = registry.observeOutcome(attempt) as VoiceAgentTelecomOutcome.Failed
            assertEquals("telecom_connection_disconnected", failed.failure.diagnosticName)
            assertEquals(
                "Telecom connection disconnected during activation",
                failed.failure.detail,
            )
            assertFalse(registry.isOwnedAttemptActive(attempt))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            releaseActivation.countDown()
            finishWorker(
                worker = activation,
                workerFailure = activationFailure,
                description = "matching-retirement-completion activation",
                primaryFailure = primaryFailure,
            )
        }

        assertFalse(accepted.get())
        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(attempt))
        assertEquals(
            "telecom_connection_disconnected",
            (registry.awaitOutcome(attempt) as VoiceAgentTelecomOutcome.Failed)
                .failure
                .diagnosticName,
        )
        assertAttemptWasConsumed(registry, attempt)
    }

    @Test
    fun `nullable await returns null only after attempt is consumed`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = FakeTelecomCall()
        assertTrue(registry.activate(attempt, call))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()

        lease.retire()

        assertEquals(null, registry.awaitOutcomeIfPresent(attempt))
        assertAttemptWasConsumed(registry, attempt)
    }

    @Test
    fun `synchronous retirement callbacks consume claimed active outcome`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = CallbackFaithfulTelecomCall(registry)

        assertTrue(registry.activate(attempt, call))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.observeOutcome(attempt))
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()

        lease.retire()

        assertEquals(1, call.disconnectCalls)
        assertAttemptWasConsumed(registry, attempt)
    }

    @Test
    fun `synchronous retirement callbacks preserve unacknowledged failed outcome`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = CallbackFaithfulTelecomCall(registry)

        assertFalse(
            registry.activate(attempt, call) {
                error("setActive failed")
            },
        )

        assertEquals(1, call.disconnectCalls)
        val outcome = registry.awaitOutcome(attempt) as VoiceAgentTelecomOutcome.Failed
        assertEquals("telecom_activation_failed", outcome.failure.diagnosticName)
        assertAttemptWasConsumed(registry, attempt)
    }

    @Test
    fun `superseded active outcome remains awaitable without retaining call`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val activeAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = CallbackFaithfulTelecomCall(registry)
        registry.activate(activeAttempt, call)

        val newerAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        val newerCall = FakeTelecomCall()
        assertTrue(registry.activate(newerAttempt, newerCall))

        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(activeAttempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(activeAttempt))
        assertAttemptWasConsumed(registry, activeAttempt)
        assertEquals(0, newerCall.disconnectCalls)
        assertTrue(registry.isOwnedAttemptActive(newerAttempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(newerAttempt))
    }

    @Test
    fun `failed outcome remains awaitable after cleanup and newer attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val failure = VoiceAgentTelecomFailure("telecom_outgoing_failed", "rejected")

        registry.fail(attempt, failure)
        registry.beginAttempt().requireAllocatedAttemptId()

        assertEquals(
            VoiceAgentTelecomOutcome.Failed(failure),
            withTimeoutOrNull(100) { registry.awaitOutcome(attempt) },
        )
    }

    @Test
    fun `new attempt completes superseded pending attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val superseded = registry.beginAttempt().requireAllocatedAttemptId()

        val replacement = registry.beginAttempt().requireAllocatedAttemptId()

        assertEquals(
            VoiceAgentTelecomOutcome.Failed(
                VoiceAgentTelecomFailure(
                    diagnosticName = "telecom_attempt_superseded",
                    detail = "Telecom attempt ${superseded.value} superseded by attempt ${replacement.value}",
                ),
            ),
            withTimeoutOrNull(100) { registry.awaitOutcome(superseded) },
        )
    }

    @Test
    fun `replacement supersedes activating attempt and remains usable`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val oldAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        val oldCall = FakeTelecomCall()
        val activationEntered = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        val oldAccepted = AtomicBoolean(true)
        val oldOutcome = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            registry.awaitOutcome(oldAttempt)
        }
        val oldActivationFailure = AtomicReference<Throwable>()
        var primaryFailure: Throwable? = null
        var replacementAttempt: VoiceAgentTelecomAttemptId? = null
        var replacementWorker: Future<VoiceAgentTelecomAttemptId>? = null
        val replacementCall = FakeTelecomCall()
        val activationExecutor = Executors.newFixedThreadPool(2)
        val oldActivation = activationExecutor.submit {
            runCatching {
                oldAccepted.set(
                    registry.activate(oldAttempt, oldCall) {
                        activationEntered.countDown()
                        releaseActivation.await()
                    },
                )
            }.onFailure(oldActivationFailure::set)
        }

        try {
            assertTrue(activationEntered.await(1, TimeUnit.SECONDS))
            val replacementThread = AtomicReference<Thread>()
            val replacement = activationExecutor.submit<VoiceAgentTelecomAttemptId> {
                replacementThread.set(Thread.currentThread())
                registry.beginAttempt().requireAllocatedAttemptId()
            }
            replacementWorker = replacement
            awaitWaiting(replacementThread)
            assertFalse(oldOutcome.isCompleted)
            releaseActivation.countDown()
            replacementAttempt = replacement.get(1, TimeUnit.SECONDS)
            assertTrue(registry.activate(requireNotNull(replacementAttempt), replacementCall))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            releaseActivation.countDown()
            if (primaryFailure != null) oldOutcome.cancel()
            val cleanupFailure = runCatching {
                oldActivation.get(1, TimeUnit.SECONDS)
                replacementWorker?.get(1, TimeUnit.SECONDS)
                throwWorkerFailure(oldActivationFailure, "old activation")
            }.exceptionOrNull()
            activationExecutor.shutdownNow()
            if (primaryFailure != null) {
                cleanupFailure?.let(primaryFailure::addSuppressed)
            } else {
                cleanupFailure?.let { throw it }
            }
        }

        assertFalse(oldAccepted.get())
        assertEquals(1, oldCall.disconnectCalls)
        assertEquals(0, replacementCall.disconnectCalls)
        val failed = withTimeoutOrNull(1_000) { oldOutcome.await() }
            as VoiceAgentTelecomOutcome.Failed
        assertEquals("telecom_attempt_superseded", failed.failure.diagnosticName)
        val activeReplacementAttempt = requireNotNull(replacementAttempt)
        assertTrue(registry.isOwnedAttemptActive(activeReplacementAttempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(activeReplacementAttempt))
    }

    @Test
    fun `cleanup during activation retires connection before completing failure`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val call = FakeTelecomCall { events += "disconnect" }
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        val outcome = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            registry.awaitOutcome(attempt).also { events += "outcome" }
        }
        val activation = thread {
            accepted.set(
                registry.activate(attempt, call) {
                    callbackEntered.countDown()
                    releaseCallback.await()
                    events += "setActive"
                },
            )
        }

        callbackEntered.await()
        registry.retireAttempt(
            attempt,
            VoiceAgentTelecomFailure(
                diagnosticName = "telecom_attempt_cancelled",
                detail = "cleanup during activation",
            ),
        )

        assertEquals(0, call.disconnectCalls)
        assertFalse(outcome.isCompleted)

        releaseCallback.countDown()
        activation.join()
        val failed = outcome.await() as VoiceAgentTelecomOutcome.Failed

        assertFalse(accepted.get())
        assertEquals("telecom_attempt_cancelled", failed.failure.diagnosticName)
        assertEquals(listOf("setActive", "disconnect", "outcome"), events)
        assertFalse(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `failure during activation is deferred until connection retires`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val failure = VoiceAgentTelecomFailure("telecom_outgoing_failed", "rejected")
        val call = FakeTelecomCall()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitOutcome(attempt)
        }
        val activation = thread {
            registry.activate(attempt, call) {
                callbackEntered.countDown()
                releaseCallback.await()
            }
        }

        callbackEntered.await()
        registry.fail(attempt, failure)

        assertEquals(0, call.disconnectCalls)
        assertFalse(outcome.isCompleted)

        releaseCallback.countDown()
        activation.join()

        assertEquals(1, call.disconnectCalls)
        assertEquals(VoiceAgentTelecomOutcome.Failed(failure), outcome.await())
        assertFalse(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `late connection after failure is disconnected`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val late = FakeTelecomCall()
        registry.fail(attempt, VoiceAgentTelecomFailure("telecom_connection_timeout", "timeout"))

        assertEquals(false, registry.activate(attempt, late))
        assertEquals(1, late.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `begin attempt disconnects previous active call`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val first = FakeTelecomCall()
        val second = FakeTelecomCall()

        val firstAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        assertFalse(registry.isOwnedAttemptActive(firstAttempt))
        registry.activate(firstAttempt, first)
        assertTrue(registry.isOwnedAttemptActive(firstAttempt))

        val secondAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        registry.activate(secondAttempt, second)

        assertEquals(1, first.disconnectCalls)
        assertEquals(0, second.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(firstAttempt))
        assertTrue(registry.isOwnedAttemptActive(secondAttempt))
    }

    @Test
    fun `throwing pre-lease supersession retains predecessor without replacement allocation`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val previous = registry.beginAttempt().requireAllocatedAttemptId()
        val disconnectError = IllegalStateException("framework retirement failed")
        val previousCall = FakeTelecomCall { throw disconnectError }
        registry.activate(previous, previousCall)
        registry.awaitOutcome(previous)

        val thrown = runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull()

        assertSame(disconnectError, thrown)
        assertEquals(1, previousCall.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(previous))
        assertFalse(registry.isOwnedAttemptActive(VoiceAgentTelecomAttemptId(2)))
    }

    @Test
    fun `retirement completion only removes the matching active call`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val first = FakeTelecomCall()
        val second = FakeTelecomCall()

        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        registry.activate(attempt, first)
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()
        registry.retired(second, Result.success(Unit))
        lease.retire()

        assertEquals(1, first.disconnectCalls)
        assertEquals(0, second.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `owned attempt retirement releases call before disconnect callback can reenter`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = FakeTelecomCall {
            registry.retired(it, Result.success(Unit))
        }

        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        registry.activate(attempt, call)
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()
        lease.retire()
        lease.retire()

        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(attempt))
    }

}
