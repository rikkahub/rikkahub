package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    fun `previous end and incoming retirement failure preserve primary error and clear aggregate`() = runTest {
        val endFailure = IllegalStateException("previous end failed")
        val retirementFailure = IllegalArgumentException("incoming retirement failed")
        val first = FakeManagedVoiceCallSession(endFailure = endFailure)
        val factory = FakeVoiceAgentCallFactory(first, FakeManagedVoiceCallSession())
        val manager = VoiceAgentCallManager(factory)
        val previousSessionLease = CountingTelecomLease()
        val incomingLease = CountingTelecomLease(disconnectFailure = retirementFailure)
        manager.start(Uuid.random(), fakeLaunchConfig(), previousSessionLease.lease, this)

        val thrown = runCatching {
            manager.start(Uuid.random(), fakeLaunchConfig(), incomingLease.lease, this)
        }.exceptionOrNull()

        assertSame(endFailure, thrown)
        assertEquals(listOf(retirementFailure), thrown?.suppressed?.toList())
        assertEquals(1, previousSessionLease.retireCalls)
        assertEquals(1, incomingLease.retireCalls)
        assertEquals(1, factory.created.size)
        assertEquals(null, manager.activeConversationId.value)
    }

    @Test
    fun `concurrent matching starts install one session and retire rejected exact lease`() = runTest {
        val releaseFactory = CountDownLatch(1)
        val factory = BlockingFirstVoiceAgentCallFactory(releaseFactory)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        val installedLiveLease = CountingTelecomLease()
        val raceRejectedLease = CountingTelecomLease()
        val firstResult = AtomicReference<Boolean?>()
        val secondResult = AtomicReference<Boolean?>()
        val workerFailure = AtomicReference<Throwable?>()
        val secondStarted = CountDownLatch(1)
        val scope = this
        val firstWorker = thread(isDaemon = true, name = "voice-manager-first-start") {
            runCatching {
                manager.start(conversationId, config, installedLiveLease.lease, scope)
            }.onSuccess(firstResult::set).onFailure { workerFailure.compareAndSet(null, it) }
        }
        var secondWorker: Thread? = null
        try {
            assertTrue(factory.factoryEntered.await(1, TimeUnit.SECONDS))
            secondWorker = thread(isDaemon = true, name = "voice-manager-duplicate-start") {
                secondStarted.countDown()
                runCatching {
                    manager.start(conversationId, config, raceRejectedLease.lease, scope)
                }.onSuccess(secondResult::set).onFailure { workerFailure.compareAndSet(null, it) }
            }
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS))
        } finally {
            releaseFactory.countDown()
            firstWorker.join(1_000)
            secondWorker?.join(1_000)
        }

        assertFalse("first start worker hung", firstWorker.isAlive)
        assertFalse("duplicate start worker hung", checkNotNull(secondWorker).isAlive)
        workerFailure.get()?.let { throw AssertionError("manager start worker failed", it) }
        assertEquals(true, firstResult.get())
        assertEquals(false, secondResult.get())
        assertEquals(1, factory.createdCalls.get())
        assertEquals(1, factory.session.startCalls)
        assertEquals(0, installedLiveLease.retireCalls)
        assertEquals(1, raceRejectedLease.retireCalls)
    }

    @Test
    fun `synchronous session start failure clears aggregate and closes exact route once`() = runTest {
        val startFailure = IllegalStateException("session start failed")
        val cleanupFailure = IllegalArgumentException("route cleanup failed")
        val blockedState = ManagerLockBlockingStateFlow(
            VoiceAgentUiState(session = VoiceSessionStatus.Error("stale in-flight state")),
        )
        val session = BlockingCollectorFailingStartSession(blockedState, startFailure)
        val factory = FakeVoiceAgentCallFactory(session)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        val lease = CountingTelecomLease(disconnectFailure = cleanupFailure)
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val thrown = try {
            runCatching {
                manager.start(conversationId, config, lease.lease, collectorScope)
            }.exceptionOrNull()
        } finally {
            collectorScope.cancel()
        }
        assertTrue(blockedState.awaitCollectorReturned())

        assertSame(startFailure, thrown)
        assertEquals(listOf(cleanupFailure), thrown?.suppressed?.toList())
        assertEquals(1, lease.retireCalls)
        assertEquals(1, session.closeNowCalls)
        assertEquals(null, manager.activeConversationId.value)
        assertEquals(null, manager.matchingRouteMetadata(conversationId, config))
        assertEquals(VoiceAgentUiState(), manager.state.value)
        assertEquals(1, factory.created.size)
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
    initialState: VoiceAgentUiState = VoiceAgentUiState(),
    private val startFailure: Throwable? = null,
    private val endFailure: Throwable? = null,
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(initialState)
    var startCalls = 0
    var reconnectCalls = 0
    var endCalls = 0
    var endAndDrainCalls = 0
    var closeNowCalls = 0
    val diagnostics = mutableListOf<Pair<String, String>>()

    override fun start() { startCalls += 1; startFailure?.let { throw it } }
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() { reconnectCalls += 1 }
    override fun recordDiagnostic(name: String, detail: String) { diagnostics += name to detail }
    override fun end() { endCalls += 1; endFailure?.let { throw it } }
    override suspend fun endAndDrain() { endAndDrainCalls += 1 }
    override fun closeNow() { closeNowCalls += 1 }
}

private class BlockingCollectorFailingStartSession(
    override val state: StateFlow<VoiceAgentUiState>,
    private val startFailure: Throwable,
) : ManagedVoiceCallSession {
    var closeNowCalls = 0

    override fun start() {
        check((state as ManagerLockBlockingStateFlow).awaitCollectorBlockedOnManagerLock())
        throw startFailure
    }

    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) = Unit
    override fun end() = Unit
    override suspend fun endAndDrain() = Unit
    override fun closeNow() { closeNowCalls += 1 }
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ManagerLockBlockingStateFlow(
    private val staleState: VoiceAgentUiState,
) : StateFlow<VoiceAgentUiState> {
    private val emissionStarted = CountDownLatch(1)
    private val collectorReturned = CountDownLatch(1)
    private val collectorThread = AtomicReference<Thread?>()

    override val value: VoiceAgentUiState = staleState
    override val replayCache: List<VoiceAgentUiState> = listOf(staleState)

    override suspend fun collect(collector: FlowCollector<VoiceAgentUiState>): Nothing {
        collectorThread.set(Thread.currentThread())
        emissionStarted.countDown()
        try {
            collector.emit(staleState)
            awaitCancellation()
        } finally {
            collectorReturned.countDown()
        }
    }

    fun awaitCollectorBlockedOnManagerLock(): Boolean {
        check(emissionStarted.await(1, TimeUnit.SECONDS)) { "collector did not start emission" }
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < deadlineNanos) {
            if (collectorThread.get()?.state == Thread.State.BLOCKED) return true
            Thread.yield()
        }
        return collectorThread.get()?.state == Thread.State.BLOCKED
    }

    fun awaitCollectorReturned(): Boolean = collectorReturned.await(1, TimeUnit.SECONDS)
}

private class BlockingFirstVoiceAgentCallFactory(
    private val releaseFactory: CountDownLatch,
) : VoiceAgentCallFactory {
    val factoryEntered = CountDownLatch(1)
    val createdCalls = AtomicInteger()
    val session = FakeManagedVoiceCallSession()

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        createdCalls.incrementAndGet()
        factoryEntered.countDown()
        check(releaseFactory.await(1, TimeUnit.SECONDS)) { "timed out waiting to release call factory" }
        return RouteOwnedVoiceCallSession(session, routeLease)
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
