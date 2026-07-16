package me.rerere.rikkahub.voiceagent

import java.util.Collections
import java.util.concurrent.CountDownLatch
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
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentTelecomCallRegistryTest {
    @Test
    fun `matching connection completes pending attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val call = FakeTelecomCall()

        assertEquals(true, registry.activate(attempt, call))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))
        assertTrue(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `acknowledged active outcome still retires exact connection`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val call = FakeTelecomCall()
        assertTrue(registry.activate(attempt, call))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))

        registry.retireOwnedAttempt(attempt)

        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(attempt))
        assertAttemptWasConsumed(registry, attempt)
    }

    @Test
    fun `retiring old attempt leaves newer active connection untouched`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val oldAttempt = registry.beginAttempt()
        val oldCall = FakeTelecomCall()
        assertTrue(registry.activate(oldAttempt, oldCall))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(oldAttempt))
        val newerAttempt = registry.beginAttempt()
        val newerCall = FakeTelecomCall()
        assertTrue(registry.activate(newerAttempt, newerCall))

        registry.retireOwnedAttempt(oldAttempt)

        assertEquals(1, oldCall.disconnectCalls)
        assertEquals(0, newerCall.disconnectCalls)
        assertTrue(registry.isOwnedAttemptActive(newerAttempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(newerAttempt))
    }

    @Test
    fun `old connection clear after newer activation leaves newer attempt usable`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val oldAttempt = registry.beginAttempt()
        val oldCall = FakeTelecomCall()
        assertTrue(registry.activate(oldAttempt, oldCall))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(oldAttempt))
        registry.retiring(oldCall)
        val newerAttempt = registry.beginAttempt()
        val newerCall = FakeTelecomCall()
        assertTrue(registry.activate(newerAttempt, newerCall))

        registry.clear(oldCall)

        assertEquals(0, newerCall.disconnectCalls)
        assertTrue(registry.isOwnedAttemptActive(newerAttempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(newerAttempt))
        registry.retireOwnedAttempt(newerAttempt)
        assertEquals(1, newerCall.disconnectCalls)
    }

    @Test
    fun `retiring callback releases old ownership before clear and preserves replacement`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val oldAttempt = registry.beginAttempt()
        val oldCall = FakeTelecomCall()
        assertTrue(registry.activate(oldAttempt, oldCall))

        registry.retiring(oldCall)

        assertFalse(registry.isOwnedAttemptActive(oldAttempt))

        val replacementAttempt = registry.beginAttempt()
        val replacementCall = FakeTelecomCall()
        assertTrue(registry.activate(replacementAttempt, replacementCall))

        registry.clear(oldCall)

        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(oldAttempt))
        assertAttemptWasConsumed(registry, oldAttempt)
        assertEquals(0, replacementCall.disconnectCalls)
        assertTrue(registry.isOwnedAttemptActive(replacementAttempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(replacementAttempt))
    }

    @Test
    fun `acknowledging active outcome retains connection ownership`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val call = FakeTelecomCall()
        registry.activate(attempt, call)

        assertEquals(VoiceAgentTelecomOutcome.Active, registry.observeOutcome(attempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.observeOutcome(attempt))
        assertTrue(registry.isOwnedAttemptActive(attempt))

        registry.acknowledgeOutcome(attempt)

        assertTrue(registry.isOwnedAttemptActive(attempt))
        registry.retireOwnedAttempt(attempt)
        assertFalse(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `acknowledging pending attempt does not remove it`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val call = FakeTelecomCall()

        registry.acknowledgeOutcome(attempt)

        assertTrue(registry.activate(attempt, call))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))
    }

    @Test
    fun `acknowledging active outcome while retiring consumes after disconnect`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
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
        val retirement = thread {
            runCatching { registry.retireOwnedAttempt(attempt) }
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
        val attempt = registry.beginAttempt()
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
        val staleAttempt = registry.beginAttempt()
        val currentAttempt = registry.beginAttempt()
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
        val attempt = registry.beginAttempt()
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
        val attempt = registry.beginAttempt()
        val callbackAcquiredRegistry = CountDownLatch(1)
        val callbackFailure = AtomicReference<Throwable>()
        var callbackThread: Thread? = null
        val call = object : VoiceAgentTelecomCall {
            var disconnectCalls = 0

            override fun disconnectFromApp() {
                disconnectCalls++
                callbackThread = thread {
                    runCatching { registry.clear(this) }
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
                registry.retireOwnedAttempt(attempt)
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
        val registry = VoiceAgentTelecomCallRegistry { selectedAttempt, selectedOutcome ->
            committedAttempt.set(selectedAttempt)
            committedOutcome.set(selectedOutcome)
            selectionCommitted.countDown()
            check(releaseNotification.await(1, TimeUnit.SECONDS)) {
                "outcome notification was not released"
            }
        }
        attempt = registry.beginAttempt()
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
            val retirementThread = thread {
                runCatching { registry.retireOwnedAttempt(requireNotNull(attempt)) }
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
        val retirementFirstAttempt = retirementFirstRegistry.beginAttempt()
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
            retirementFirstRegistry.retireOwnedAttempt(retirementFirstAttempt)
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
        val publicationFirstAttempt = publicationFirstRegistry.beginAttempt()
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

        publicationFirstRegistry.retireOwnedAttempt(publicationFirstAttempt)

        assertEquals(1, publicationFirstCall.disconnectCalls)
        assertEquals(
            VoiceAgentTelecomOutcome.Active,
            publicationFirstRegistry.awaitOutcome(publicationFirstAttempt),
        )
        assertAttemptWasConsumed(publicationFirstRegistry, publicationFirstAttempt)
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
    fun `matching active clear releases ownership and preserves selected outcome`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val call = FakeTelecomCall()
        assertTrue(registry.activate(attempt, call))

        registry.clear(call)

        assertFalse(registry.isOwnedAttemptActive(attempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))
        assertAttemptWasConsumed(registry, attempt)
    }

    @Test
    fun `matching activating clear publishes disconnected failure and cleans exact call`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
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
                            "matching activation clear was not released"
                        }
                    },
                )
            }.onFailure(activationFailure::set)
        }
        var primaryFailure: Throwable? = null

        try {
            assertTrue(activationEntered.await(1, TimeUnit.SECONDS))
            registry.clear(call)

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
                description = "matching-clear activation",
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
        val attempt = registry.beginAttempt()
        val call = FakeTelecomCall()
        assertTrue(registry.activate(attempt, call))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))

        registry.retireOwnedAttempt(attempt)

        assertEquals(null, registry.awaitOutcomeIfPresent(attempt))
        assertAttemptWasConsumed(registry, attempt)
    }

    @Test
    fun `synchronous retirement callbacks preserve unacknowledged active outcome`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val call = CallbackFaithfulTelecomCall(registry)

        assertTrue(registry.activate(attempt, call))

        registry.retireOwnedAttempt(attempt)

        assertEquals(1, call.disconnectCalls)
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))
        assertAttemptWasConsumed(registry, attempt)
    }

    @Test
    fun `synchronous retirement callbacks preserve unacknowledged failed outcome`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
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
        val activeAttempt = registry.beginAttempt()
        val call = CallbackFaithfulTelecomCall(registry)
        registry.activate(activeAttempt, call)

        val newerAttempt = registry.beginAttempt()
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
        val attempt = registry.beginAttempt()
        val failure = VoiceAgentTelecomFailure("telecom_outgoing_failed", "rejected")

        registry.fail(attempt, failure)
        registry.beginAttempt()

        assertEquals(
            VoiceAgentTelecomOutcome.Failed(failure),
            withTimeoutOrNull(100) { registry.awaitOutcome(attempt) },
        )
    }

    @Test
    fun `new attempt completes superseded pending attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val superseded = registry.beginAttempt()

        val replacement = registry.beginAttempt()

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
        val oldAttempt = registry.beginAttempt()
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
        val replacementCall = FakeTelecomCall()
        val oldActivation = thread {
            runCatching {
                oldAccepted.set(
                    registry.activate(oldAttempt, oldCall) {
                        activationEntered.countDown()
                        check(releaseActivation.await(1, TimeUnit.SECONDS)) {
                            "old activation was not released"
                        }
                    },
                )
            }.onFailure(oldActivationFailure::set)
        }

        try {
            assertTrue(activationEntered.await(1, TimeUnit.SECONDS))
            replacementAttempt = registry.beginAttempt()
            assertTrue(registry.activate(requireNotNull(replacementAttempt), replacementCall))
            assertFalse(oldOutcome.isCompleted)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            releaseActivation.countDown()
            if (primaryFailure != null) oldOutcome.cancel()
            runCatching {
                finishWorker(
                    worker = oldActivation,
                    workerFailure = oldActivationFailure,
                    description = "old activation",
                    primaryFailure = primaryFailure,
                )
            }.onFailure { oldOutcome.cancel() }.getOrThrow()
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
        val attempt = registry.beginAttempt()
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
        registry.retireOwnedAttempt(attempt)

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
        val attempt = registry.beginAttempt()
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
        val attempt = registry.beginAttempt()
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

        val firstAttempt = registry.beginAttempt()
        assertFalse(registry.isOwnedAttemptActive(firstAttempt))
        registry.activate(firstAttempt, first)
        assertTrue(registry.isOwnedAttemptActive(firstAttempt))

        val secondAttempt = registry.beginAttempt()
        registry.activate(secondAttempt, second)

        assertEquals(1, first.disconnectCalls)
        assertEquals(0, second.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(firstAttempt))
        assertTrue(registry.isOwnedAttemptActive(secondAttempt))
    }

    @Test
    fun `throwing active supersession terminalizes exact replacement attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val previous = registry.beginAttempt()
        val disconnectError = IllegalStateException("framework retirement failed")
        val previousCall = FakeTelecomCall { throw disconnectError }
        registry.activate(previous, previousCall)
        registry.awaitOutcome(previous)

        val thrown = runCatching { registry.beginAttempt() }.exceptionOrNull()

        assertTrue(thrown is VoiceAgentTelecomAttemptStartException)
        val startError = thrown as VoiceAgentTelecomAttemptStartException
        assertEquals(VoiceAgentTelecomAttemptId(2), startError.attemptId)
        assertEquals(
            VoiceAgentTelecomFailure(
                diagnosticName = "telecom_supersession_cleanup_failed",
                detail = "framework retirement failed",
            ),
            startError.failure,
        )
        assertEquals(disconnectError, startError.cause)
        assertEquals(1, previousCall.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(previous))
        assertFalse(registry.isOwnedAttemptActive(startError.attemptId))
        assertEquals(
            VoiceAgentTelecomOutcome.Failed(startError.failure),
            registry.awaitOutcome(startError.attemptId),
        )
        assertAttemptWasConsumed(registry, startError.attemptId)
    }

    @Test
    fun `clear only removes the matching active call`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val first = FakeTelecomCall()
        val second = FakeTelecomCall()

        val attempt = registry.beginAttempt()
        registry.activate(attempt, first)
        registry.clear(second)
        registry.retireOwnedAttempt(attempt)

        assertEquals(1, first.disconnectCalls)
        assertEquals(0, second.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `owned attempt retirement clears call before disconnect callback can reenter`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = FakeTelecomCall {
            registry.clear(it)
        }

        val attempt = registry.beginAttempt()
        registry.activate(attempt, call)
        registry.retireOwnedAttempt(attempt)
        registry.retireOwnedAttempt(attempt)

        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(attempt))
    }

    private suspend fun assertAttemptWasConsumed(
        registry: VoiceAgentTelecomCallRegistry,
        attemptId: VoiceAgentTelecomAttemptId,
    ) {
        val error = runCatching { registry.awaitOutcome(attemptId) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    private fun finishWorker(
        worker: Thread,
        workerFailure: AtomicReference<Throwable>,
        description: String,
        primaryFailure: Throwable? = null,
    ) {
        var cleanupFailure = runCatching { worker.join(1_000) }.exceptionOrNull()
        if (worker.isAlive) {
            cleanupFailure = cleanupFailure.append(
                AssertionError("$description did not finish"),
            )
        }
        workerFailure.get()?.let { failure ->
            cleanupFailure = cleanupFailure.append(
                AssertionError("$description failed", failure),
            )
        }
        if (primaryFailure != null) {
            cleanupFailure?.let(primaryFailure::addSuppressed)
        } else {
            cleanupFailure?.let { throw it }
        }
    }

    private fun throwWorkerFailure(
        workerFailure: AtomicReference<Throwable>,
        description: String,
    ) {
        workerFailure.get()?.let { failure ->
            throw AssertionError("$description failed", failure)
        }
    }

    private fun Throwable?.append(additional: Throwable): Throwable =
        this?.also { it.addSuppressed(additional) } ?: additional

    private class FakeTelecomCall(
        private val onDisconnect: (FakeTelecomCall) -> Unit = {},
    ) : VoiceAgentTelecomCall {
        var disconnectCalls = 0

        override fun disconnectFromApp() {
            disconnectCalls += 1
            onDisconnect(this)
        }
    }

    private class CallbackFaithfulTelecomCall(
        private val registry: VoiceAgentTelecomCallRegistry,
    ) : VoiceAgentTelecomCall {
        var disconnectCalls = 0

        override fun disconnectFromApp() {
            disconnectCalls += 1
            registry.retiring(this)
            registry.clear(this)
        }
    }
}
