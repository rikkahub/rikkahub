package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentRouteLeaseTest {
    @Test
    fun `Telecom lease exposes metadata and retires its exact attempt once`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = RecordingTelecomCall()
        assertTrue(registry.activate(attempt, call))
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()

        lease.retire()
        lease.retire()

        assertEquals(VoiceAgentRouteMetadata(VoiceAudioRouteOwner.Telecom), lease.metadata)
        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `failed exact Telecom retirement can retry same connection`() {
        val firstFailure = IllegalStateException("first retirement failed")
        val retirementFailure = AtomicReference<Throwable?>(firstFailure)
        val retryEntered = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = object : VoiceAgentTelecomCall {
            var disconnectCalls = 0

            override fun disconnectFromApp() {
                disconnectCalls += 1
                if (disconnectCalls == 2) {
                    retryEntered.countDown()
                    check(releaseRetry.await(1, TimeUnit.SECONDS)) { "retry disconnect was not released" }
                }
                retirementFailure.get()?.let { throw it }
            }
        }
        assertTrue(registry.activate(attempt, call))
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()

        assertSame(firstFailure, runCatching(lease::retire).exceptionOrNull())
        assertEquals(1, call.disconnectCalls)
        assertFalse(lease.isUsable)
        assertSame(
            firstFailure,
            runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull(),
        )

        retirementFailure.set(null)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val retry = executor.submit { lease.retire() }
            check(retryEntered.await(1, TimeUnit.SECONDS)) { "retry disconnect did not start" }
            assertFalse(lease.isUsable)
            releaseRetry.countDown()
            retry.get(1, TimeUnit.SECONDS)
            assertEquals(2, call.disconnectCalls)

            val replacementAttempt = registry.beginAttempt().requireAllocatedAttemptId()
            val replacementCall = RecordingTelecomCall()
            assertTrue(registry.activate(replacementAttempt, replacementCall))
            lease.retire()
            assertEquals(2, call.disconnectCalls)
            assertEquals(0, replacementCall.disconnectCalls)
        } finally {
            releaseRetry.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `lease joins concurrent supersession failure before retrying exact connection`() {
        val firstFailure = IllegalStateException("first retirement failed")
        val retirementFailure = AtomicReference<Throwable?>(firstFailure)
        val disconnectEntered = CountDownLatch(1)
        val releaseDisconnect = CountDownLatch(1)
        val leaseRetirementStarted = CountDownLatch(1)
        val leaseRetirementThread = AtomicReference<Thread>()
        val leaseRetirementInterrupted = AtomicBoolean()
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = object : VoiceAgentTelecomCall {
            var disconnectCalls = 0

            override fun disconnectFromApp() {
                disconnectCalls += 1
                disconnectEntered.countDown()
                check(releaseDisconnect.await(1, TimeUnit.SECONDS)) { "supersession disconnect was not released" }
                retirementFailure.get()?.let { throw it }
            }
        }
        assertTrue(registry.activate(attempt, call))
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val supersession = executor.submit<Throwable?> {
                runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull()
            }
            check(disconnectEntered.await(1, TimeUnit.SECONDS)) { "supersession disconnect did not start" }
            val leaseRetirement = executor.submit<Throwable?> {
                leaseRetirementThread.set(Thread.currentThread())
                leaseRetirementStarted.countDown()
                val failure = runCatching(lease::retire).exceptionOrNull()
                leaseRetirementInterrupted.set(Thread.currentThread().isInterrupted)
                failure
            }
            check(leaseRetirementStarted.await(1, TimeUnit.SECONDS)) { "lease retirement did not start" }
            assertTrue(
                "lease retirement did not join the supersession disconnect",
                runCatching { leaseRetirement.get(100, TimeUnit.MILLISECONDS) }
                    .exceptionOrNull() is TimeoutException,
            )
            leaseRetirementThread.get().interrupt()
            assertTrue(
                "interrupted lease retirement returned before the supersession result",
                runCatching { leaseRetirement.get(100, TimeUnit.MILLISECONDS) }
                    .exceptionOrNull() is TimeoutException,
            )
            releaseDisconnect.countDown()

            assertSame(firstFailure, supersession.get(1, TimeUnit.SECONDS))
            assertSame(firstFailure, leaseRetirement.get(1, TimeUnit.SECONDS))
            assertEquals(1, call.disconnectCalls)
            assertFalse(lease.isUsable)
            assertTrue(leaseRetirementInterrupted.get())

            retirementFailure.set(null)
            lease.retire()
            val replacementAttempt = registry.beginAttempt().requireAllocatedAttemptId()
            val replacementCall = RecordingTelecomCall()
            assertTrue(registry.activate(replacementAttempt, replacementCall))
            lease.retire()
            assertEquals(2, call.disconnectCalls)
            assertEquals(0, replacementCall.disconnectCalls)
        } finally {
            releaseDisconnect.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `stale Telecom lease retirement leaves replacement attempt untouched`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val ownedAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        val ownedCall = RecordingTelecomCall()
        assertTrue(registry.activate(ownedAttempt, ownedCall))
        val lease = registry.consumeActiveOutcome(ownedAttempt).requireResolvedLease()
        val replacementAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        val replacementCall = RecordingTelecomCall()
        assertTrue(registry.activate(replacementAttempt, replacementCall))
        assertEquals(1, ownedCall.disconnectCalls)

        lease.retire()

        assertEquals(1, ownedCall.disconnectCalls)
        assertEquals(0, replacementCall.disconnectCalls)
        assertTrue(registry.isOwnedAttemptActive(replacementAttempt))
    }

    @Test
    fun `Telecom lease usability follows external connection retirement`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = RecordingTelecomCall()
        assertTrue(registry.activate(attempt, call))
        val lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()

        assertTrue(lease.isUsable)
        registry.retiring(call)
        assertFalse(lease.isUsable)
    }

    @Test
    fun `direct fallback lease exposes failure and retirement is a no-op`() {
        val failure = VoiceAgentTelecomFailure("telecom_unavailable", "Telecom unavailable")
        val lease = DirectFallbackVoiceAgentRouteLease(failure)

        lease.retire()
        lease.retire()

        assertEquals(VoiceAgentRouteMetadata(VoiceAudioRouteOwner.DirectFallback, failure), lease.metadata)
        assertTrue(lease.isUsable)
    }

    @Test
    fun `owned session exposes route state and directly delegates nonterminal operations`() {
        val delegate = RecordingManagedSession()
        val lease = DirectFallbackVoiceAgentRouteLease(
            VoiceAgentTelecomFailure("telecom_unavailable", "Telecom unavailable"),
        )
        val owned = RouteOwnedVoiceCallSession(delegate, lease)

        owned.start()
        owned.interrupt()
        owned.setMuted(true)
        owned.reconnect()
        owned.recordDiagnostic("route", "ready")

        assertSame(delegate.state, owned.state)
        assertSame(lease.metadata, owned.routeMetadata)
        assertEquals(lease.isUsable, owned.isRouteUsable)
        assertEquals(
            listOf(
                "session-start",
                "session-interrupt",
                "session-muted-true",
                "session-reconnect",
                "session-route-ready",
            ),
            delegate.events,
        )
    }

    @Test
    fun `cleanup operation retires route before closing session`() = runTest {
        val events = mutableListOf<String>()
        val owned = RouteOwnedVoiceCallSession(
            delegate = RecordingManagedSession(events = events),
            routeLease = activeTelecomLease(events),
        )

        val result = owned.cleanupOperation.run(VoiceAgentCleanupMode.Immediate)

        assertSame(VoiceAgentCleanupResult.Completed, result)
        assertEquals(listOf("route-retire", "session-close-now"), events)
    }

    private fun activeTelecomLease(events: MutableList<String>): VoiceAgentRouteLease {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        assertTrue(registry.activate(attempt, RecordingTelecomCall(events)))
        return registry.consumeActiveOutcome(attempt).requireResolvedLease()
    }
}

private class RecordingTelecomCall(
    private val events: MutableList<String>? = null,
    private val retirementFailure: AtomicReference<Throwable?>,
) : VoiceAgentTelecomCall {
    constructor(
        events: MutableList<String>? = null,
        retirementFailure: Throwable? = null,
    ) : this(events, AtomicReference(retirementFailure))

    var disconnectCalls = 0

    override fun disconnectFromApp() {
        disconnectCalls += 1
        events?.add("route-retire")
        retirementFailure.get()?.let { throw it }
    }
}

private class RecordingManagedSession(
    val events: MutableList<String> = mutableListOf(),
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())

    override fun start() {
        events += "session-start"
    }

    override fun interrupt() {
        events += "session-interrupt"
    }

    override fun setMuted(value: Boolean) {
        events += "session-muted-$value"
    }

    override fun reconnect() {
        events += "session-reconnect"
    }

    override fun recordDiagnostic(name: String, detail: String) {
        events += "session-$name-$detail"
    }

    override fun end() {
        events += "session-end"
    }

    override suspend fun endAndDrain() {
        events += "session-end-and-drain"
    }

    override fun closeNow() {
        events += "session-close-now"
    }
}
