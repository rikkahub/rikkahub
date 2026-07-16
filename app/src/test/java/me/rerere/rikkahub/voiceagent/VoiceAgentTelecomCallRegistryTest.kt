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
        assertEquals(true, registry.hasActiveConnection())
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
        assertFalse(registry.hasActiveConnection())
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
    fun `acknowledging active outcome retains connection ownership`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val call = FakeTelecomCall()
        registry.activate(attempt, call)

        assertEquals(VoiceAgentTelecomOutcome.Active, registry.observeOutcome(attempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.observeOutcome(attempt))
        assertTrue(registry.hasActiveConnection())

        registry.acknowledgeOutcome(attempt)

        assertTrue(registry.isOwnedAttemptActive(attempt))
        assertTrue(registry.hasActiveConnection())
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
        val call = object : VoiceAgentTelecomCall {
            override fun disconnectFromApp() {
                disconnectEntered.countDown()
                releaseDisconnect.await()
            }
        }
        assertTrue(registry.activate(attempt, call))
        val retirement = thread {
            registry.retireOwnedAttempt(attempt)
        }

        try {
            assertTrue(disconnectEntered.await(1, TimeUnit.SECONDS))
            assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(attempt))
        } finally {
            releaseDisconnect.countDown()
        }
        retirement.join()

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
        assertFalse(registry.hasActiveConnection())
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
        assertTrue(registry.hasActiveConnection())
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
        assertFalse(registry.hasActiveConnection())
        assertFalse(outcome.isCompleted)

        releaseCallback.countDown()
        activation.join()

        assertTrue(accepted.get())
        assertEquals(VoiceAgentTelecomOutcome.Active, outcome.await())
        assertTrue(registry.hasActiveConnection())
    }

    @Test
    fun `active outcome resumes unconfined waiter outside registry lock`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val callbackAcquiredRegistry = CountDownLatch(1)
        var callbackThread: Thread? = null
        val call = object : VoiceAgentTelecomCall {
            var disconnectCalls = 0

            override fun disconnectFromApp() {
                disconnectCalls++
                callbackThread = thread {
                    registry.clear(this)
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
        assertTrue(registry.activate(attempt, call))
        callbackThread?.join()

        assertEquals(VoiceAgentTelecomOutcome.Active, outcome.await())
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
            releaseNotification.await()
        }
        attempt = registry.beginAttempt()
        val call = FakeTelecomCall()
        val accepted = AtomicBoolean()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
            registry.observeOutcome(requireNotNull(attempt))
        }
        val activation = thread {
            accepted.set(registry.activate(requireNotNull(attempt), call))
        }
        var retirement: Thread? = null

        try {
            assertTrue(selectionCommitted.await(1, TimeUnit.SECONDS))
            assertEquals(attempt, committedAttempt.get())
            assertEquals(VoiceAgentTelecomOutcome.Active, committedOutcome.get())
            val retirementThread = thread {
                registry.retireOwnedAttempt(requireNotNull(attempt))
            }
            retirement = retirementThread
            retirementThread.join(1_000)

            assertFalse("retirement waited for outcome notification", retirementThread.isAlive)
            assertEquals(1, call.disconnectCalls)
            assertFalse(outcome.isCompleted)
        } finally {
            releaseNotification.countDown()
            retirement?.join(1_000)
            activation.join(1_000)
        }

        assertFalse("activation did not finish after notification release", activation.isAlive)
        assertTrue(accepted.get())
        assertEquals(VoiceAgentTelecomOutcome.Active, outcome.await())
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
        val retirementFirstActivation = thread {
            retirementFirstAccepted.set(
                retirementFirstRegistry.activate(retirementFirstAttempt, retirementFirstCall) {
                    activationEntered.countDown()
                    releaseActivation.await()
                },
            )
        }

        activationEntered.await()
        retirementFirstRegistry.retireOwnedAttempt(retirementFirstAttempt)
        assertFalse(retirementFirstOutcome.isCompleted)
        releaseActivation.countDown()
        retirementFirstActivation.join()

        assertFalse(retirementFirstAccepted.get())
        assertEquals(1, retirementFirstCall.disconnectCalls)
        assertEquals(
            "telecom_attempt_cancelled",
            (retirementFirstOutcome.await() as VoiceAgentTelecomOutcome.Failed).failure.diagnosticName,
        )

        val publicationFirstRegistry = VoiceAgentTelecomCallRegistry()
        val publicationFirstAttempt = publicationFirstRegistry.beginAttempt()
        val publicationFirstCall = FakeTelecomCall()
        val publicationFirstAccepted = AtomicBoolean()
        val publicationFirstActivation = thread {
            publicationFirstAccepted.set(
                publicationFirstRegistry.activate(publicationFirstAttempt, publicationFirstCall),
            )
        }

        publicationFirstActivation.join()
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
        registry.disconnectActive()
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
        registry.disconnectActive()

        assertEquals(0, call.disconnectCalls)
        assertFalse(outcome.isCompleted)

        releaseCallback.countDown()
        activation.join()
        val failed = outcome.await() as VoiceAgentTelecomOutcome.Failed

        assertFalse(accepted.get())
        assertEquals("telecom_attempt_cancelled", failed.failure.diagnosticName)
        assertEquals(listOf("setActive", "disconnect", "outcome"), events)
        assertFalse(registry.hasActiveConnection())
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
        assertFalse(registry.hasActiveConnection())
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
        assertFalse(registry.hasActiveConnection())
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

    private suspend fun assertAttemptWasConsumed(
        registry: VoiceAgentTelecomCallRegistry,
        attemptId: VoiceAgentTelecomAttemptId,
    ) {
        val error = runCatching { registry.awaitOutcome(attemptId) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
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
