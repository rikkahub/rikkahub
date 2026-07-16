package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    fun `failed Telecom startup preserves matching session and live call for retry`() = runTest {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val telecomCall = FakeTelecomCall()
        assertTrue(registry.activate(attempt, telecomCall))
        registry.acknowledgeOutcome(attempt)
        val session = StartupFakeManagedSession()
        val factory = StartupFakeCallFactory(session)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        manager.start(conversationId, config, VoiceAudioRouteOwner.Telecom, this)
        val cleanup = voiceAgentFailedStartCleanupPlan(
            preserveSessionRequested = true,
            routeOwner = manager.activeRouteOwner.value,
            hasActiveTelecomCall = registry.hasActiveConnection(),
        )
        if (cleanup.retireTelecomCall) registry.disconnectActive()
        if (!cleanup.preserveSession) manager.closeNow()
        var resolveCalls = 0
        val startup = VoiceAgentCallStartup(manager, registry) {
            resolveCalls += 1
            VoiceAgentAudioRouteResolution(VoiceAudioRouteOwner.DirectFallback)
        }

        val retry = startup.start(conversationId, config, this) { true }

        assertEquals(false, cleanup.retireTelecomCall)
        assertEquals(true, cleanup.preserveSession)
        assertEquals(0, telecomCall.disconnectCalls)
        assertTrue(registry.hasActiveConnection())
        assertEquals(0, resolveCalls)
        assertEquals(false, (retry as VoiceAgentCallStartupResult.Started).startedNewSession)
        assertEquals(VoiceAudioRouteOwner.Telecom, retry.resolution.owner)
    }

    @Test
    fun `failed Telecom startup without a live call closes session so retry resolves`() = runTest {
        val firstSession = StartupFakeManagedSession()
        val secondSession = StartupFakeManagedSession()
        val factory = StartupFakeCallFactory(firstSession, secondSession)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        manager.start(conversationId, config, VoiceAudioRouteOwner.Telecom, this)
        val cleanup = voiceAgentFailedStartCleanupPlan(
            preserveSessionRequested = true,
            routeOwner = manager.activeRouteOwner.value,
            hasActiveTelecomCall = false,
        )
        if (!cleanup.preserveSession) manager.closeNow()
        var resolveCalls = 0
        val startup = VoiceAgentCallStartup(manager, VoiceAgentTelecomCallRegistry()) {
            resolveCalls += 1
            VoiceAgentAudioRouteResolution(VoiceAudioRouteOwner.DirectFallback)
        }

        val retry = startup.start(conversationId, config, this) { true }

        assertEquals(false, cleanup.preserveSession)
        assertEquals(1, firstSession.closeNowCalls)
        assertEquals(1, resolveCalls)
        assertEquals(true, (retry as VoiceAgentCallStartupResult.Started).startedNewSession)
        assertEquals(VoiceAudioRouteOwner.DirectFallback, retry.resolution.owner)
    }

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
        val resolution = VoiceAgentAudioRouteResolution(
            owner = VoiceAudioRouteOwner.Telecom,
            telecomAttemptId = attempt,
        )
        val startup = VoiceAgentCallStartup(manager, registry) { resolution }

        val result = startup.start(Uuid.random(), fakeLaunchConfig(), this) { false }

        assertEquals(VoiceAgentCallStartupResult.Stale(resolution), result)
        assertEquals(emptyList<StartupCreatedCall>(), factory.created)
        assertEquals(null, manager.activeConversationId.value)
        assertEquals(1, telecomCall.disconnectCalls)
        assertFalse(registry.hasActiveConnection())
        assertFalse(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `older stale Telecom result retires only its attempt after newer attempt becomes active`() = runTest {
        val registry = VoiceAgentTelecomCallRegistry()
        val oldAttempt = registry.beginAttempt()
        val oldCall = FakeTelecomCall()
        assertTrue(registry.activate(oldAttempt, oldCall))
        registry.acknowledgeOutcome(oldAttempt)
        val oldResolution = VoiceAgentAudioRouteResolution(
            owner = VoiceAudioRouteOwner.Telecom,
            telecomAttemptId = oldAttempt,
        )

        val newerAttempt = registry.beginAttempt()
        val newerCall = FakeTelecomCall()
        assertTrue(registry.activate(newerAttempt, newerCall))
        val factory = StartupFakeCallFactory(StartupFakeManagedSession())
        val manager = VoiceAgentCallManager(factory)
        val startup = VoiceAgentCallStartup(manager, registry) { oldResolution }

        val result = startup.start(Uuid.random(), fakeLaunchConfig(), this) { false }

        assertEquals(VoiceAgentCallStartupResult.Stale(oldResolution), result)
        assertEquals(emptyList<StartupCreatedCall>(), factory.created)
        assertEquals(null, manager.activeConversationId.value)
        assertEquals(1, oldCall.disconnectCalls)
        assertEquals(0, newerCall.disconnectCalls)
        assertTrue(registry.hasActiveConnection())
        assertFalse(registry.isOwnedAttemptActive(oldAttempt))
        assertTrue(registry.isOwnedAttemptActive(newerAttempt))
        assertEquals(VoiceAgentTelecomOutcome.Active, registry.observeOutcome(newerAttempt))
        registry.acknowledgeOutcome(newerAttempt)
    }

    @Test
    fun `IllegalArgumentException from stale disconnect is not swallowed`() = runTest {
        val registry = VoiceAgentTelecomCallRegistry()
        val oldAttempt = registry.beginAttempt()
        val disconnectError = IllegalArgumentException("old Telecom disconnect failed")
        val oldCall = BlockingThrowingFakeTelecomCall(disconnectError)
        assertTrue(registry.activate(oldAttempt, oldCall))
        registry.acknowledgeOutcome(oldAttempt)
        val oldResolution = VoiceAgentAudioRouteResolution(
            owner = VoiceAudioRouteOwner.Telecom,
            telecomAttemptId = oldAttempt,
        )
        val factory = StartupFakeCallFactory(StartupFakeManagedSession())
        val manager = VoiceAgentCallManager(factory)
        val startup = VoiceAgentCallStartup(manager, registry) { oldResolution }
        val staleStartup = async(Dispatchers.Default) {
            startup.start(Uuid.random(), fakeLaunchConfig(), this@runTest) { false }
        }

        assertTrue(oldCall.disconnectEntered.await(1, TimeUnit.SECONDS))
        try {
            val newerAttempt = registry.beginAttempt()
            val newerCall = FakeTelecomCall()
            assertTrue(registry.activate(newerAttempt, newerCall))

            oldCall.releaseDisconnect.countDown()
            val thrown = runCatching { staleStartup.await() }.exceptionOrNull()

            assertTrue(thrown is IllegalArgumentException)
            assertEquals(disconnectError.message, thrown?.message)
            assertEquals(emptyList<StartupCreatedCall>(), factory.created)
            assertEquals(null, manager.activeConversationId.value)
            assertEquals(1, oldCall.disconnectCalls)
            assertFalse(registry.isOwnedAttemptActive(oldAttempt))
            assertEquals(0, newerCall.disconnectCalls)
            assertTrue(registry.hasActiveConnection())
            assertTrue(registry.isOwnedAttemptActive(newerAttempt))
            assertEquals(VoiceAgentTelecomOutcome.Active, registry.observeOutcome(newerAttempt))
            registry.acknowledgeOutcome(newerAttempt)
        } finally {
            oldCall.releaseDisconnect.countDown()
        }
    }

    @Test
    fun `stale retained Telecom resolution has no attempt and leaves claimed call connected`() = runTest {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt()
        val telecomCall = FakeTelecomCall()
        assertTrue(registry.activate(attempt, telecomCall))
        val session = StartupFakeManagedSession()
        val factory = StartupFakeCallFactory(session)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        manager.start(conversationId, config, VoiceAudioRouteOwner.Telecom, this)
        val startup = VoiceAgentCallStartup(manager, registry) {
            error("retained session must not resolve a new route")
        }

        val result = startup.start(conversationId, config, this) { false }

        val stale = result as VoiceAgentCallStartupResult.Stale
        assertEquals(VoiceAudioRouteOwner.Telecom, stale.resolution.owner)
        assertEquals(null, stale.resolution.telecomAttemptId)
        assertEquals(1, factory.created.size)
        assertEquals(0, telecomCall.disconnectCalls)
        assertTrue(registry.hasActiveConnection())
        assertTrue(registry.isOwnedAttemptActive(attempt))
        registry.acknowledgeOutcome(attempt)
    }
}

private class StartupFakeManagedSession(
    private val events: MutableList<String>? = null,
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    var startCalls = 0
    var closeNowCalls = 0

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
    override fun closeNow() {
        closeNowCalls += 1
    }
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

private class BlockingThrowingFakeTelecomCall(
    private val disconnectError: Throwable,
) : VoiceAgentTelecomCall {
    val disconnectEntered = CountDownLatch(1)
    val releaseDisconnect = CountDownLatch(1)
    var disconnectCalls = 0

    override fun disconnectFromApp() {
        disconnectCalls += 1
        disconnectEntered.countDown()
        check(releaseDisconnect.await(1, TimeUnit.SECONDS)) { "timed out waiting to throw disconnect error" }
        throw disconnectError
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
