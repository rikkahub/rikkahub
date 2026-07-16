package me.rerere.rikkahub.voiceagent

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
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
    fun `matching failure completes pending attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val failure = VoiceAgentTelecomFailure("telecom_outgoing_failed", "rejected")

        registry.fail(attempt, failure)

        assertEquals(VoiceAgentTelecomOutcome.Failed(failure), registry.awaitOutcome(attempt))
    }

    @Test
    fun `matching clear removes exact active attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val call = FakeTelecomCall()

        registry.activate(attempt, call)
        registry.clear(call)

        assertFalse(registry.isOwnedAttemptActive(attempt))
        assertFalse(registry.hasActiveConnection())
        assertAttemptWasConsumed(registry, attempt)
    }

    @Test
    fun `superseded active outcome remains awaitable without retaining call`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val activeAttempt = registry.beginAttempt()
        val call = FakeTelecomCall()
        registry.activate(activeAttempt, call)

        registry.beginAttempt()

        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(activeAttempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.awaitOutcome(activeAttempt))
        assertAttemptWasConsumed(registry, activeAttempt)
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
}
