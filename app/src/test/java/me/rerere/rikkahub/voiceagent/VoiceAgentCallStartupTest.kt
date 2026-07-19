package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
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
    fun `matching active session returns metadata before resolving`() = runTest {
        val manager = VoiceAgentCallManager(StartupFakeCallFactory(StartupFakeManagedSession()))
        val conversationId = Uuid.random()
        val config = fakeStartupLaunchConfig()
        val installedLiveLease = CountingTelecomLease()
        manager.start(conversationId, config, installedLiveLease.lease, this)
        var resolveCalls = 0
        val startup = VoiceAgentCallStartup(manager) {
            resolveCalls += 1
            DirectFallbackVoiceAgentRouteLease(VoiceAgentTelecomFailure("unused", "unused"))
        }

        val result = startup.start(conversationId, config, this) { true }

        assertEquals(VoiceAgentCallStartupResult.Started(installedLiveLease.lease.metadata, false), result)
        assertEquals(0, resolveCalls)
        assertEquals(0, installedLiveLease.retireCalls)
    }

    @Test
    fun `same conversation with different config replaces exact route lease`() = runTest {
        val firstSession = StartupFakeManagedSession()
        val secondSession = StartupFakeManagedSession()
        val factory = StartupFakeCallFactory(firstSession, secondSession)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val firstConfig = fakeStartupLaunchConfig(voiceModelId = "gemini-flash")
        val secondConfig = fakeStartupLaunchConfig(voiceModelId = "gemini-pro")
        val previousLease = CountingTelecomLease()
        val installedLiveLease = CountingTelecomLease()
        manager.start(conversationId, firstConfig, previousLease.lease, this)
        var resolveCalls = 0
        val startup = VoiceAgentCallStartup(manager) {
            resolveCalls += 1
            installedLiveLease.lease
        }

        val result = startup.start(conversationId, secondConfig, this) { true }

        assertEquals(VoiceAgentCallStartupResult.Started(installedLiveLease.lease.metadata, true), result)
        assertEquals(1, resolveCalls)
        assertEquals(2, factory.created.size)
        assertEquals(1, previousLease.retireCalls)
        assertEquals(0, installedLiveLease.retireCalls)
        assertEquals(VoiceAgentRouteMatchResult.NoMatch, manager.matchingRoute(conversationId, firstConfig))
        assertEquals(
            VoiceAgentRouteMatchResult.Existing(installedLiveLease.lease.metadata),
            manager.matchingRoute(conversationId, secondConfig),
        )
    }

    @Test
    fun `stale resolved lease is retired exactly once and metadata is returned`() = runTest {
        val staleLease = CountingTelecomLease()
        val factory = StartupFakeCallFactory(StartupFakeManagedSession())
        val startup = VoiceAgentCallStartup(VoiceAgentCallManager(factory)) { staleLease.lease }

        val result = startup.start(Uuid.random(), fakeStartupLaunchConfig(), this) { false }

        assertEquals(VoiceAgentCallStartupResult.Stale(staleLease.lease.metadata), result)
        assertEquals(1, staleLease.retireCalls)
        assertEquals(0, factory.created.size)
    }

    @Test
    fun `current resolved lease transfers through factory and remains owned by live session`() = runTest {
        val installedLiveLease = CountingTelecomLease()
        val events = mutableListOf<String>()
        val factory = StartupFakeCallFactory(StartupFakeManagedSession(events), events = events)
        val startup = VoiceAgentCallStartup(VoiceAgentCallManager(factory)) {
            events += "resolved"
            installedLiveLease.lease
        }

        val result = startup.start(Uuid.random(), fakeStartupLaunchConfig(), this) {
            events += "current"
            true
        }

        assertEquals(listOf("resolved", "current", "created:Telecom", "started"), events)
        assertEquals(VoiceAgentCallStartupResult.Started(installedLiveLease.lease.metadata, true), result)
        assertEquals(0, installedLiveLease.retireCalls)
    }

    @Test
    fun `duplicate installation race retires resolved lease`() = runTest {
        val session = StartupFakeManagedSession()
        val factory = StartupFakeCallFactory(session)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeStartupLaunchConfig()
        val installedLiveLease = CountingTelecomLease()
        val raceRejectedLease = CountingTelecomLease()
        val startup = VoiceAgentCallStartup(manager) {
            manager.start(conversationId, config, installedLiveLease.lease, this)
            raceRejectedLease.lease
        }

        val result = startup.start(conversationId, config, this) { true }

        assertEquals(VoiceAgentCallStartupResult.Started(installedLiveLease.lease.metadata, false), result)
        assertEquals(1, raceRejectedLease.retireCalls)
        assertEquals(0, installedLiveLease.retireCalls)
        assertEquals(1, factory.created.size)
    }

    @Test
    fun `factory failure is primary and lease is consumed once`() = runTest {
        val creationFailure = IllegalStateException("factory failed")
        val factoryFailureLease = CountingTelecomLease()
        val manager = VoiceAgentCallManager(StartupConsumingFailingFactory(creationFailure))
        val startup = VoiceAgentCallStartup(manager) { factoryFailureLease.lease }

        val thrown = runCatching {
            startup.start(Uuid.random(), fakeStartupLaunchConfig(), this) { true }
        }.exceptionOrNull()

        assertSame(creationFailure, thrown)
        assertEquals(1, factoryFailureLease.retireCalls)
    }

    @Test
    fun `suspended resolution starts no manager session`() = runTest {
        val resolved = CompletableDeferred<VoiceAgentRouteLease>()
        val factory = StartupFakeCallFactory(StartupFakeManagedSession())
        val manager = VoiceAgentCallManager(factory)
        val startup = VoiceAgentCallStartup(manager) { resolved.await() }

        val pending = async { startup.start(Uuid.random(), fakeStartupLaunchConfig(), this@runTest) { true } }
        yield()

        assertFalse(pending.isCompleted)
        assertEquals(0, factory.created.size)
        resolved.complete(DirectFallbackVoiceAgentRouteLease(VoiceAgentTelecomFailure("fallback", "fallback")))
        assertTrue((pending.await() as VoiceAgentCallStartupResult.Started).startedNewSession)
    }

    @Test
    fun `terminal route supersession maps to stale without resolving another route`() = runTest {
        val releaseFailure = CountDownLatch(1)
        val creationFailure = StartupNonCopyableException(Any(), "first factory failed")
        val newerSession = StartupFakeManagedSession()
        val factory = StartupBlockingFirstFailingFactory(
            releaseFirstFailure = releaseFailure,
            firstFailure = creationFailure,
            subsequentSession = newerSession,
        )
        val manager = VoiceAgentCallManager(factory)
        val failedConversation = Uuid.random()
        val failedConfig = fakeStartupLaunchConfig()
        val failedOwnerLease = CountingTelecomLease()
        val failedOwner = async(Dispatchers.Default) {
            manager.start(failedConversation, failedConfig, failedOwnerLease.lease, this@runTest)
        }
        assertTrue(factory.firstCreateEntered.await(1, TimeUnit.SECONDS))
        var resolveCalls = 0
        val startup = VoiceAgentCallStartup(manager) {
            resolveCalls += 1
            error("terminal supersession must not resolve another route")
        }
        val retryGate = StartupBlockedRetryDispatcher()
        try {
            val result = async(retryGate.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                startup.start(failedConversation, failedConfig, this@runTest) { true }
            }

            releaseFailure.countDown()
            assertSame(creationFailure, runCatching { failedOwner.await() }.exceptionOrNull())
            val newerConversation = Uuid.random()
            val newerLease = CountingTelecomLease()
            assertEquals(
                VoiceAgentManagerStartResult.Started(newerLease.lease.metadata),
                manager.start(newerConversation, fakeStartupLaunchConfig(), newerLease.lease, this),
            )

            retryGate.release()
            assertEquals(
                VoiceAgentCallStartupResult.Stale(failedOwnerLease.lease.metadata),
                result.await(),
            )
            assertEquals(0, resolveCalls)
            assertEquals(1, failedOwnerLease.retireCalls)
            assertEquals(0, newerLease.retireCalls)
            assertEquals(newerConversation, manager.activeConversationId.value)
        } finally {
            retryGate.close()
        }
    }
}

private class StartupFakeManagedSession(
    private val events: MutableList<String>? = null,
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    override fun start() { events?.add("started") }
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
    val created = mutableListOf<VoiceAudioRouteOwner>()
    private var nextSession = 0
    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        created += routeLease.metadata.owner
        events?.add("created:${routeLease.metadata.owner.name}")
        return RouteOwnedVoiceCallSession(sessions[nextSession++], routeLease)
    }
}

private class StartupConsumingFailingFactory(private val failure: Throwable) : VoiceAgentCallFactory {
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

private class StartupBlockingFirstFailingFactory(
    private val releaseFirstFailure: CountDownLatch,
    private val firstFailure: Throwable,
    private val subsequentSession: ManagedVoiceCallSession,
) : VoiceAgentCallFactory {
    val firstCreateEntered = CountDownLatch(1)
    private val createdCalls = AtomicInteger()

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        if (createdCalls.getAndIncrement() == 0) {
            firstCreateEntered.countDown()
            check(releaseFirstFailure.await(1, TimeUnit.SECONDS)) {
                "timed out waiting to release first startup factory failure"
            }
            routeLease.retire()
            throw firstFailure
        }
        return RouteOwnedVoiceCallSession(subsequentSession, routeLease)
    }
}

private class StartupBlockedRetryDispatcher {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val releaseLatch = CountDownLatch(1)
    private val blockerEntered = CountDownLatch(1)

    init {
        dispatcher.executor.execute {
            blockerEntered.countDown()
            check(releaseLatch.await(5, TimeUnit.SECONDS)) {
                "timed out waiting to release startup retry dispatcher"
            }
        }
        check(blockerEntered.await(1, TimeUnit.SECONDS)) { "startup retry dispatcher blocker did not start" }
    }

    fun release() = releaseLatch.countDown()

    fun close() {
        release()
        dispatcher.close()
    }
}

private class StartupNonCopyableException(
    @Suppress("unused") private val identityMarker: Any,
    message: String,
) : IllegalStateException(message)

private fun fakeStartupLaunchConfig(voiceModelId: String = "gemini-flash") = VoiceAgentLaunchConfig(
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
