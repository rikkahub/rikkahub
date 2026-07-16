package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
        val attempt = registry.beginAttempt()
        val call = RecordingTelecomCall()
        assertTrue(registry.activate(attempt, call))
        val lease = TelecomVoiceAgentRouteLease(attempt, registry)

        lease.retire()
        lease.retire()

        assertEquals(VoiceAgentRouteMetadata(VoiceAudioRouteOwner.Telecom), lease.metadata)
        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `stale Telecom lease retirement leaves replacement attempt untouched`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val ownedAttempt = registry.beginAttempt()
        val ownedCall = RecordingTelecomCall()
        assertTrue(registry.activate(ownedAttempt, ownedCall))
        val lease = TelecomVoiceAgentRouteLease(ownedAttempt, registry)
        val replacementAttempt = registry.beginAttempt()
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
        val attempt = registry.beginAttempt()
        val call = RecordingTelecomCall()
        assertTrue(registry.activate(attempt, call))
        val lease = TelecomVoiceAgentRouteLease(attempt, registry)

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

        assertEquals(
            VoiceAgentRouteMetadata(VoiceAudioRouteOwner.DirectFallback, failure),
            lease.metadata,
        )
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
    fun `end retires route before ending session`() {
        val events = mutableListOf<String>()
        val owned = RouteOwnedVoiceCallSession(
            delegate = RecordingManagedSession(events = events),
            routeLease = activeTelecomLease(events),
        )

        owned.end()

        assertEquals(listOf("route-retire", "session-end"), events)
    }

    @Test
    fun `end preserves route failure and suppresses delegate failure`() {
        val events = mutableListOf<String>()
        val routeFailure = IllegalStateException("route retirement failed")
        val sessionFailure = IllegalArgumentException("session end failed")
        val owned = RouteOwnedVoiceCallSession(
            delegate = RecordingManagedSession(events = events, endFailure = sessionFailure),
            routeLease = activeTelecomLease(events, routeFailure),
        )

        val thrown = runCatching { owned.end() }.exceptionOrNull()

        assertSame(routeFailure, thrown)
        assertEquals(listOf(sessionFailure), thrown?.suppressed?.toList())
        assertEquals(listOf("route-retire", "session-end"), events)
    }

    @Test
    fun `endAndDrain preserves route failure and suppresses delegate failure`() = runBlocking {
        val events = mutableListOf<String>()
        val routeFailure = IllegalStateException("route retirement failed")
        val sessionFailure = IllegalArgumentException("session drain failed")
        val owned = RouteOwnedVoiceCallSession(
            delegate = RecordingManagedSession(events = events, drainFailure = sessionFailure),
            routeLease = activeTelecomLease(events, routeFailure),
        )

        val thrown = runCatching { owned.endAndDrain() }.exceptionOrNull()

        assertSame(routeFailure, thrown)
        assertEquals(listOf(sessionFailure), thrown?.suppressed?.toList())
        assertEquals(listOf("route-retire", "session-end-and-drain"), events)
    }

    @Test
    fun `closeNow retires route before closing session`() {
        val events = mutableListOf<String>()
        val owned = RouteOwnedVoiceCallSession(
            delegate = RecordingManagedSession(events = events),
            routeLease = activeTelecomLease(events),
        )

        owned.closeNow()

        assertEquals(listOf("route-retire", "session-close-now"), events)
    }

    private fun activeTelecomLease(
        events: MutableList<String>,
        retirementFailure: Throwable? = null,
    ): VoiceAgentRouteLease {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        assertTrue(
            registry.activate(
                attempt,
                RecordingTelecomCall(events, retirementFailure),
            ),
        )
        return TelecomVoiceAgentRouteLease(attempt, registry)
    }
}

private class RecordingTelecomCall(
    private val events: MutableList<String>? = null,
    private val retirementFailure: Throwable? = null,
) : VoiceAgentTelecomCall {
    var disconnectCalls = 0

    override fun disconnectFromApp() {
        disconnectCalls += 1
        events?.add("route-retire")
        retirementFailure?.let { throw it }
    }
}

private class RecordingManagedSession(
    val events: MutableList<String> = mutableListOf(),
    private val endFailure: Throwable? = null,
    private val drainFailure: Throwable? = null,
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
        endFailure?.let { throw it }
    }

    override suspend fun endAndDrain() {
        events += "session-end-and-drain"
        drainFailure?.let { throw it }
    }

    override fun closeNow() {
        events += "session-close-now"
    }
}
