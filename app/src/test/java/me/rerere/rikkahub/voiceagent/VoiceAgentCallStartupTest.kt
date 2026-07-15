package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentCallStartupTest {
    @Test
    fun `matching active session retains immutable owner without resolving again`() = runTest {
        val session = StartupFakeManagedSession()
        val factory = StartupFakeCallFactory(session)
        val manager = VoiceAgentCallManager(factory)
        val registry = VoiceAgentTelecomCallRegistry()
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        manager.start(conversationId, config, VoiceAudioRouteOwner.Telecom, this)
        var resolveCalls = 0
        val startup = VoiceAgentCallStartup(manager, registry) {
            resolveCalls += 1
            VoiceAgentAudioRouteResolution(VoiceAudioRouteOwner.DirectFallback)
        }

        val result = startup.start(conversationId, config, this) { true }

        assertEquals(
            VoiceAgentCallStartupResult.Started(
                resolution = VoiceAgentAudioRouteResolution(VoiceAudioRouteOwner.Telecom),
                startedNewSession = false,
            ),
            result,
        )
        assertEquals(0, resolveCalls)
        assertEquals(1, factory.created.size)
        assertEquals(1, session.startCalls)
    }

    @Test
    fun `suspended resolution starts no manager session`() = runTest {
        val resolution = CompletableDeferred<VoiceAgentAudioRouteResolution>()
        val factory = StartupFakeCallFactory(StartupFakeManagedSession())
        val manager = VoiceAgentCallManager(factory)
        val startup = VoiceAgentCallStartup(manager, VoiceAgentTelecomCallRegistry()) {
            resolution.await()
        }

        val pending = async {
            startup.start(Uuid.random(), fakeLaunchConfig(), this@runTest) { true }
        }
        yield()

        assertFalse(pending.isCompleted)
        assertEquals(emptyList<StartupCreatedCall>(), factory.created)
        assertEquals(null, manager.activeConversationId.value)

        resolution.complete(VoiceAgentAudioRouteResolution(VoiceAudioRouteOwner.Telecom))
        pending.await()
    }

    @Test
    fun `active Telecom resolution starts exactly one Telecom session after resolution`() = runTest {
        val events = mutableListOf<String>()
        val session = StartupFakeManagedSession(events)
        val factory = StartupFakeCallFactory(session, events = events)
        val manager = VoiceAgentCallManager(factory)
        val startup = VoiceAgentCallStartup(manager, VoiceAgentTelecomCallRegistry()) {
            events += "resolved:Telecom"
            VoiceAgentAudioRouteResolution(VoiceAudioRouteOwner.Telecom)
        }

        val result = startup.start(Uuid.random(), fakeLaunchConfig(), this) {
            events += "generation-current"
            true
        }

        assertEquals(
            listOf("resolved:Telecom", "generation-current", "created:Telecom", "started"),
            events,
        )
        assertEquals(1, factory.created.size)
        assertEquals(VoiceAudioRouteOwner.Telecom, factory.created.single().routeOwner)
        assertEquals(
            VoiceAgentCallStartupResult.Started(
                resolution = VoiceAgentAudioRouteResolution(VoiceAudioRouteOwner.Telecom),
                startedNewSession = true,
            ),
            result,
        )
    }

    @Test
    fun `failed Telecom resolution starts exactly one fallback session and preserves failure`() = runTest {
        val failure = VoiceAgentTelecomFailure(
            diagnosticName = "telecom_connection_timeout",
            detail = "Android Telecom did not become active",
        )
        val resolution = VoiceAgentAudioRouteResolution(
            owner = VoiceAudioRouteOwner.DirectFallback,
            failure = failure,
        )
        val factory = StartupFakeCallFactory(StartupFakeManagedSession())
        val manager = VoiceAgentCallManager(factory)
        val startup = VoiceAgentCallStartup(manager, VoiceAgentTelecomCallRegistry()) { resolution }

        val result = startup.start(Uuid.random(), fakeLaunchConfig(), this) { true }

        assertEquals(1, factory.created.size)
        assertEquals(VoiceAudioRouteOwner.DirectFallback, factory.created.single().routeOwner)
        assertEquals(
            VoiceAgentCallStartupResult.Started(resolution, startedNewSession = true),
            result,
        )
        assertSame(failure, (result as VoiceAgentCallStartupResult.Started).resolution.failure)
    }

    @Test
    fun `generation-stale Telecom result starts no session and disconnects unclaimed live call`() = runTest {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val telecomCall = FakeTelecomCall()
        assertTrue(registry.activate(attempt, telecomCall))
        registry.acknowledgeOutcome(attempt)
        val factory = StartupFakeCallFactory(StartupFakeManagedSession())
        val manager = VoiceAgentCallManager(factory)
        val resolution = VoiceAgentAudioRouteResolution(VoiceAudioRouteOwner.Telecom)
        val startup = VoiceAgentCallStartup(manager, registry) { resolution }

        val result = startup.start(Uuid.random(), fakeLaunchConfig(), this) { false }

        assertEquals(VoiceAgentCallStartupResult.Stale(resolution), result)
        assertEquals(emptyList<StartupCreatedCall>(), factory.created)
        assertEquals(null, manager.activeConversationId.value)
        assertEquals(1, telecomCall.disconnectCalls)
        assertFalse(registry.hasActiveConnection())
    }
}

private class StartupFakeManagedSession(
    private val events: MutableList<String>? = null,
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    var startCalls = 0

    override fun start() {
        startCalls += 1
        events?.add("started")
    }

    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) = Unit
    override fun end() = Unit
    override suspend fun endAndDrain() = Unit
    override fun closeNow() = Unit
}

private class StartupFakeCallFactory(
    private vararg val sessions: ManagedVoiceCallSession,
    private val events: MutableList<String>? = null,
) : VoiceAgentCallFactory {
    val created = mutableListOf<StartupCreatedCall>()
    private var nextSession = 0

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeOwner: VoiceAudioRouteOwner,
        scope: CoroutineScope,
    ): ManagedVoiceCallSession {
        created += StartupCreatedCall(conversationId, config, routeOwner)
        events?.add("created:${routeOwner.name}")
        return sessions[nextSession++]
    }
}

private data class StartupCreatedCall(
    val conversationId: Uuid,
    val config: VoiceAgentLaunchConfig,
    val routeOwner: VoiceAudioRouteOwner,
)

private class FakeTelecomCall : VoiceAgentTelecomCall {
    var disconnectCalls = 0

    override fun disconnectFromApp() {
        disconnectCalls += 1
    }
}

private fun fakeLaunchConfig() = VoiceAgentLaunchConfig(
    hermesVoiceBaseUrl = "https://voice.test",
    credentials = HermesVoiceCredentials(deviceApiKey = "profile-key"),
    voiceModelId = "gemini-flash",
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
