package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentCallManagerTest {
    @Test
    fun `manager exposes idle state before start`() {
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(FakeManagedVoiceCallSession()))

        assertEquals(VoiceSessionStatus.Idle, manager.state.value.session)
        assertEquals(null, manager.activeConversationId.value)
    }

    @Test
    fun `start transfers lease to one active session and exposes exact metadata`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val factory = FakeVoiceAgentCallFactory(session)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        val failure = VoiceAgentTelecomFailure("fallback", "exact failure")
        val installedLiveLease = DirectFallbackVoiceAgentRouteLease(failure)

        val started = manager.start(conversationId, config, installedLiveLease, this)
        val observedState = manager.state
        session.state.value = VoiceAgentUiState(session = VoiceSessionStatus.Connected)
        yield()

        assertTrue(started)
        assertSame(observedState, manager.state)
        assertEquals(VoiceSessionStatus.Connected, manager.state.value.session)
        assertEquals(listOf(CreatedCall(conversationId, config, VoiceAudioRouteOwner.DirectFallback)), factory.created)
        assertEquals(VoiceAgentRouteMetadata(VoiceAudioRouteOwner.DirectFallback, failure), manager.matchingRouteMetadata(conversationId, config))
        assertEquals(1, session.startCalls)
    }

    @Test
    fun `duplicate race retires unused incoming lease exactly once`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val factory = FakeVoiceAgentCallFactory(session)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        val installedLiveLease = CountingTelecomLease()
        val raceRejectedLease = CountingTelecomLease()
        manager.start(conversationId, config, installedLiveLease.lease, this)

        val started = manager.start(conversationId, config, raceRejectedLease.lease, this)

        assertEquals(false, started)
        assertEquals(1, raceRejectedLease.retireCalls)
        assertEquals(0, installedLiveLease.retireCalls)
        assertEquals(1, factory.created.size)
        assertEquals(0, session.endCalls)
    }

    @Test
    fun `replacement ends previous route-owned session before factory consumes incoming lease`() = runTest {
        val first = FakeManagedVoiceCallSession()
        val second = FakeManagedVoiceCallSession()
        val factory = FakeVoiceAgentCallFactory(first, second)
        val manager = VoiceAgentCallManager(factory)
        val previousSessionLease = CountingTelecomLease()
        val installedLiveLease = CountingTelecomLease()
        manager.start(Uuid.random(), fakeLaunchConfig(), previousSessionLease.lease, this)

        val nextConversation = Uuid.random()
        manager.start(nextConversation, fakeLaunchConfig(), installedLiveLease.lease, this)

        assertEquals(1, previousSessionLease.retireCalls)
        assertEquals(1, first.endCalls)
        assertEquals(0, installedLiveLease.retireCalls)
        assertEquals(1, second.startCalls)
        assertEquals(nextConversation, manager.activeConversationId.value)
    }

    @Test
    fun `previous session end failure retires incoming lease before factory and rethrows`() = runTest {
        val endFailure = IllegalStateException("previous end failed")
        val first = FakeManagedVoiceCallSession(endFailure = endFailure)
        val factory = FakeVoiceAgentCallFactory(first, FakeManagedVoiceCallSession())
        val manager = VoiceAgentCallManager(factory)
        val previousSessionLease = CountingTelecomLease()
        val incomingLease = CountingTelecomLease()
        manager.start(Uuid.random(), fakeLaunchConfig(), previousSessionLease.lease, this)

        val thrown = runCatching {
            manager.start(Uuid.random(), fakeLaunchConfig(), incomingLease.lease, this)
        }.exceptionOrNull()

        assertSame(endFailure, thrown)
        assertEquals(1, previousSessionLease.retireCalls)
        assertEquals(1, incomingLease.retireCalls)
        assertEquals(1, factory.created.size)
        assertEquals(null, manager.activeConversationId.value)
    }

    @Test
    fun `factory failure consumes lease and manager does not retire it twice`() = runTest {
        val creationFailure = IllegalStateException("factory failed")
        val factoryFailureLease = CountingTelecomLease()
        val manager = VoiceAgentCallManager(ConsumingFailingVoiceAgentCallFactory(creationFailure))

        val thrown = runCatching {
            manager.start(Uuid.random(), fakeLaunchConfig(), factoryFailureLease.lease, this)
        }.exceptionOrNull()

        assertSame(creationFailure, thrown)
        assertEquals(1, factoryFailureLease.retireCalls)
        assertEquals(null, manager.activeConversationId.value)
    }

    @Test
    fun `exact route usability controls active session preservation`() = runTest {
        val lease = CountingTelecomLease()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(FakeManagedVoiceCallSession()))
        val conversationId = Uuid.random()
        manager.start(conversationId, fakeLaunchConfig(), lease.lease, this)

        assertTrue(manager.canPreserveActiveSession(conversationId))
        lease.lease.retire()
        assertEquals(false, manager.canPreserveActiveSession(conversationId))
        assertEquals(false, manager.canPreserveActiveSession(Uuid.random()))
    }

    @Test
    fun `end clears aggregate and retires installed lease once`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val lease = CountingTelecomLease()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(session))
        manager.start(Uuid.random(), fakeLaunchConfig(), lease.lease, this)

        manager.end()

        assertEquals(1, lease.retireCalls)
        assertEquals(1, session.endCalls)
        assertEquals(null, manager.activeConversationId.value)
    }

    @Test
    fun `detached end drain retires only detached lease and leaves replacement live`() = runTest {
        val first = FakeManagedVoiceCallSession()
        val second = FakeManagedVoiceCallSession()
        val firstLease = CountingTelecomLease()
        val secondLease = CountingTelecomLease()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(first, second))
        manager.start(Uuid.random(), fakeLaunchConfig(), firstLease.lease, this)
        val detached = manager.detachForEndAndDrain()
        val secondConversation = Uuid.random()
        manager.start(secondConversation, fakeLaunchConfig(), secondLease.lease, this)

        detached?.endAndDrain()

        assertEquals(1, firstLease.retireCalls)
        assertEquals(0, secondLease.retireCalls)
        assertEquals(1, first.endAndDrainCalls)
        assertEquals(0, second.endAndDrainCalls)
        assertEquals(secondConversation, manager.activeConversationId.value)
    }
}

private class FakeManagedVoiceCallSession(
    private val endFailure: Throwable? = null,
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    var startCalls = 0
    var reconnectCalls = 0
    var endCalls = 0
    var endAndDrainCalls = 0
    val diagnostics = mutableListOf<Pair<String, String>>()

    override fun start() { startCalls += 1 }
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() { reconnectCalls += 1 }
    override fun recordDiagnostic(name: String, detail: String) { diagnostics += name to detail }
    override fun end() { endCalls += 1; endFailure?.let { throw it } }
    override suspend fun endAndDrain() { endAndDrainCalls += 1 }
    override fun closeNow() = Unit
}

private class FakeVoiceAgentCallFactory(
    private vararg val sessions: ManagedVoiceCallSession,
) : VoiceAgentCallFactory {
    val created = mutableListOf<CreatedCall>()
    private var nextSession = 0

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        created += CreatedCall(conversationId, config, routeLease.metadata.owner)
        return RouteOwnedVoiceCallSession(sessions[nextSession++], routeLease)
    }
}

private class ConsumingFailingVoiceAgentCallFactory(
    private val failure: Throwable,
) : VoiceAgentCallFactory {
    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        routeLease.retire()
        throw failure
    }
}

private data class CreatedCall(
    val conversationId: Uuid,
    val config: VoiceAgentLaunchConfig,
    val routeOwner: VoiceAudioRouteOwner,
)

private fun fakeLaunchConfig(voiceModelId: String = "gemini-flash") = VoiceAgentLaunchConfig(
    hermesVoiceBaseUrl = "https://voice.test",
    credentials = HermesVoiceCredentials(deviceApiKey = "profile-key"),
    voiceModelId = voiceModelId,
    assistantName = "Hermes",
    assistantPrompt = "system",
)

private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
    val testScope = CoroutineScope(coroutineContext + SupervisorJob())
    try { testScope.block() } finally { testScope.cancel() }
}
