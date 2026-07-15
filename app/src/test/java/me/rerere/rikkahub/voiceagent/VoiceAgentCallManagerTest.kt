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
    fun `start creates one active session and exposes its state`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val factory = FakeVoiceAgentCallFactory(session)
        val manager = VoiceAgentCallManager(factory = factory)
        val conversationId = Uuid.parse("33333333-3333-4333-8333-333333333333")
        val config = fakeLaunchConfig()

        val started = manager.start(
            conversationId = conversationId,
            config = config,
            routeOwner = VoiceAudioRouteOwner.DirectFallback,
            scope = this,
        )

        val observedState = manager.state
        session.state.value = VoiceAgentUiState(session = VoiceSessionStatus.Connected)
        yield()

        assertEquals(true, started)
        assertSame(observedState, manager.state)
        assertEquals(VoiceSessionStatus.Connected, manager.state.value.session)
        assertEquals(
            listOf(CreatedCall(conversationId, config, VoiceAudioRouteOwner.DirectFallback)),
            factory.created,
        )
        assertEquals(1, session.startCalls)
    }

    @Test
    fun `starting another conversation ends previous session before replacing it`() = runTest {
        val first = FakeManagedVoiceCallSession()
        val second = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(first, second))
        val firstConversationId = Uuid.parse("44444444-4444-4444-8444-444444444444")
        val secondConversationId = Uuid.parse("55555555-5555-4555-8555-555555555555")

        val startedFirst = manager.start(
            firstConversationId,
            fakeLaunchConfig(),
            VoiceAudioRouteOwner.DirectFallback,
            this,
        )
        val startedSecond = manager.start(
            secondConversationId,
            fakeLaunchConfig(),
            VoiceAudioRouteOwner.DirectFallback,
            this,
        )

        assertEquals(true, startedFirst)
        assertEquals(true, startedSecond)
        assertEquals(1, first.endCalls)
        assertEquals(0, first.closeNowCalls)
        assertEquals(1, second.startCalls)
        assertEquals(secondConversationId, manager.activeConversationId.value)
    }

    @Test
    fun `starting same conversation does not duplicate active session`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(session))
        val conversationId = Uuid.parse("66666666-6666-4666-8666-666666666666")

        val startedFirst = manager.start(
            conversationId,
            fakeLaunchConfig(),
            VoiceAudioRouteOwner.DirectFallback,
            this,
        )
        val startedDuplicate = manager.start(
            conversationId,
            fakeLaunchConfig(),
            VoiceAudioRouteOwner.DirectFallback,
            this,
        )

        assertEquals(true, startedFirst)
        assertEquals(false, startedDuplicate)
        assertEquals(1, session.startCalls)
        assertEquals(0, session.endCalls)
    }

    @Test
    fun `starting same conversation preserves existing degraded call status`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(session))
        val conversationId = Uuid.parse("77777777-7777-4777-8777-777777777777")
        val degraded = VoiceCallStatus.Degraded("Telecom unavailable")

        manager.start(
            conversationId,
            fakeLaunchConfig(),
            VoiceAudioRouteOwner.DirectFallback,
            this,
        )
        manager.updateCallStatus(degraded)
        val startedDuplicate = manager.start(
            conversationId,
            fakeLaunchConfig(),
            VoiceAudioRouteOwner.DirectFallback,
            this,
        )

        assertEquals(false, startedDuplicate)
        assertEquals(degraded, manager.state.value.call)
    }

    @Test
    fun `starting same conversation with different launch config replaces existing session`() = runTest {
        val first = FakeManagedVoiceCallSession()
        val second = FakeManagedVoiceCallSession()
        val factory = FakeVoiceAgentCallFactory(first, second)
        val manager = VoiceAgentCallManager(factory = factory)
        val conversationId = Uuid.parse("77777777-7777-4777-8777-777777777778")

        val startedFirst = manager.start(
            conversationId = conversationId,
            config = fakeLaunchConfig(voiceModelId = "gemini-flash"),
            routeOwner = VoiceAudioRouteOwner.DirectFallback,
            scope = this,
        )
        val startedSecond = manager.start(
            conversationId = conversationId,
            config = fakeLaunchConfig(voiceModelId = "gemini-pro"),
            routeOwner = VoiceAudioRouteOwner.DirectFallback,
            scope = this,
        )

        assertEquals(true, startedFirst)
        assertEquals(true, startedSecond)
        assertEquals(1, first.endCalls)
        assertEquals(1, second.startCalls)
        assertEquals("gemini-pro", factory.created.last().config.voiceModelId)
        assertEquals(conversationId, manager.activeConversationId.value)
    }

    @Test
    fun `starting same conversation with same launch config does not replace existing session`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(session))
        val conversationId = Uuid.parse("77777777-7777-4777-8777-777777777779")
        val config = fakeLaunchConfig()

        val startedFirst = manager.start(
            conversationId,
            config,
            VoiceAudioRouteOwner.DirectFallback,
            this,
        )
        val startedDuplicate = manager.start(
            conversationId,
            config,
            VoiceAudioRouteOwner.DirectFallback,
            this,
        )

        assertEquals(true, startedFirst)
        assertEquals(false, startedDuplicate)
        assertEquals(1, session.startCalls)
        assertEquals(0, session.endCalls)
    }

    @Test
    fun `reconnect forwards to active same conversation session`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(session))
        val conversationId = Uuid.parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

        manager.start(
            conversationId,
            fakeLaunchConfig(),
            VoiceAudioRouteOwner.DirectFallback,
            this,
        )
        manager.reconnect()

        assertEquals(1, session.reconnectCalls)
        assertEquals(1, session.startCalls)
        assertEquals(0, session.endCalls)
        assertEquals(conversationId, manager.activeConversationId.value)
    }

    @Test
    fun `record diagnostic forwards to active session`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(session))

        manager.start(
            Uuid.random(),
            fakeLaunchConfig(),
            VoiceAudioRouteOwner.DirectFallback,
            this,
        )
        manager.recordDiagnostic(name = "telecom_register_failed", detail = "registration error")

        assertEquals(listOf("telecom_register_failed" to "registration error"), session.diagnostics)
    }

    @Test
    fun `end forwards to active session and clears active call`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(session))

        manager.start(
            Uuid.random(),
            fakeLaunchConfig(),
            VoiceAudioRouteOwner.DirectFallback,
            this,
        )
        manager.end()

        assertEquals(1, session.endCalls)
        assertEquals(null, manager.activeConversationId.value)
    }

    @Test
    fun `detached end drain does not drain replacement session`() = runTest {
        val first = FakeManagedVoiceCallSession()
        val second = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(first, second))
        val firstConversationId = Uuid.parse("88888888-8888-4888-8888-888888888888")
        val secondConversationId = Uuid.parse("99999999-9999-4999-8999-999999999999")

        manager.start(
            firstConversationId,
            fakeLaunchConfig(),
            VoiceAudioRouteOwner.DirectFallback,
            this,
        )
        val detached = manager.detachForEndAndDrain()
        manager.start(
            secondConversationId,
            fakeLaunchConfig(),
            VoiceAudioRouteOwner.DirectFallback,
            this,
        )
        detached?.endAndDrain()

        assertEquals(1, first.endAndDrainCalls)
        assertEquals(0, second.endAndDrainCalls)
        assertEquals(secondConversationId, manager.activeConversationId.value)
    }

    @Test
    fun `start passes and retains immutable route owner`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val factory = FakeVoiceAgentCallFactory(session)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()

        manager.start(conversationId, config, VoiceAudioRouteOwner.Telecom, this)

        assertEquals(VoiceAudioRouteOwner.Telecom, factory.created.single().routeOwner)
        assertEquals(VoiceAudioRouteOwner.Telecom, manager.activeRouteOwner.value)
        assertEquals(
            VoiceAudioRouteOwner.Telecom,
            manager.routeOwnerForActiveSession(conversationId, config),
        )
    }

    @Test
    fun `different owner replaces otherwise matching session`() = runTest {
        val first = FakeManagedVoiceCallSession()
        val second = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(first, second))
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()

        manager.start(conversationId, config, VoiceAudioRouteOwner.DirectFallback, this)
        val replaced = manager.start(conversationId, config, VoiceAudioRouteOwner.Telecom, this)

        assertEquals(true, replaced)
        assertEquals(1, first.endCalls)
        assertEquals(VoiceAudioRouteOwner.Telecom, manager.activeRouteOwner.value)
    }
}

private class FakeManagedVoiceCallSession : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    var startCalls = 0
    var reconnectCalls = 0
    var endCalls = 0
    var endAndDrainCalls = 0
    var closeNowCalls = 0
    val diagnostics = mutableListOf<Pair<String, String>>()

    override fun start() {
        startCalls += 1
    }

    override fun interrupt() = Unit

    override fun setMuted(value: Boolean) = Unit

    override fun reconnect() {
        reconnectCalls += 1
    }

    override fun recordDiagnostic(name: String, detail: String) {
        diagnostics += name to detail
    }

    override fun end() {
        endCalls += 1
    }

    override suspend fun endAndDrain() {
        endAndDrainCalls += 1
    }

    override fun closeNow() {
        closeNowCalls += 1
    }
}

private class FakeVoiceAgentCallFactory(
    private vararg val sessions: ManagedVoiceCallSession,
) : VoiceAgentCallFactory {
    val created = mutableListOf<CreatedCall>()
    private var nextSession = 0

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeOwner: VoiceAudioRouteOwner,
        scope: CoroutineScope,
    ): ManagedVoiceCallSession {
        created += CreatedCall(conversationId, config, routeOwner)
        return sessions[nextSession++]
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
    try {
        testScope.block()
    } finally {
        testScope.cancel()
    }
}
