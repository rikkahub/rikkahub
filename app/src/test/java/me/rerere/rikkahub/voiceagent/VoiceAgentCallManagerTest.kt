package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
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
        val config = fakeManagerLaunchConfig()
        val failure = VoiceAgentTelecomFailure("fallback", "exact failure")
        val installedLiveLease = DirectFallbackVoiceAgentRouteLease(failure)

        val started = manager.start(conversationId, config, installedLiveLease, this)
        val observedState = manager.state
        session.state.value = VoiceAgentUiState(session = VoiceSessionStatus.Connected)
        yield()

        assertEquals(VoiceAgentManagerStartResult.Started(installedLiveLease.metadata), started)
        assertSame(observedState, manager.state)
        assertEquals(VoiceSessionStatus.Connected, manager.state.value.session)
        assertEquals(listOf(CreatedCall(conversationId, config, VoiceAudioRouteOwner.DirectFallback)), factory.created)
        assertEquals(
            VoiceAgentRouteMatchResult.Existing(
                VoiceAgentRouteMetadata(VoiceAudioRouteOwner.DirectFallback, failure),
            ),
            manager.matchingRoute(conversationId, config),
        )
        assertEquals(1, session.startCalls)
    }

    @Test
    fun `duplicate race retires unused incoming lease exactly once`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val factory = FakeVoiceAgentCallFactory(session)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val installedLiveLease = CountingTelecomLease()
        val raceRejectedLease = CountingTelecomLease()
        manager.start(conversationId, config, installedLiveLease.lease, this)

        val started = manager.start(conversationId, config, raceRejectedLease.lease, this)

        assertEquals(VoiceAgentManagerStartResult.Existing(installedLiveLease.lease.metadata), started)
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
        manager.start(Uuid.random(), fakeManagerLaunchConfig(), previousSessionLease.lease, this)

        val nextConversation = Uuid.random()
        manager.start(nextConversation, fakeManagerLaunchConfig(), installedLiveLease.lease, this)

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
        manager.start(Uuid.random(), fakeManagerLaunchConfig(), previousSessionLease.lease, this)

        val thrown = runCatching {
            manager.start(Uuid.random(), fakeManagerLaunchConfig(), incomingLease.lease, this)
        }.exceptionOrNull()

        assertSame(endFailure, thrown)
        assertEquals(listOf(retirementFailure), thrown?.suppressed?.toList())
        assertEquals(1, previousSessionLease.retireCalls)
        assertEquals(1, incomingLease.retireCalls)
        assertEquals(1, factory.created.size)
        assertEquals(null, manager.activeConversationId.value)
    }

    @Test
    fun `matching start suspends without blocking manager then reuses published call`() = runTest {
        val releaseFactory = CountDownLatch(1)
        val factory = BlockingFirstVoiceAgentCallFactory(releaseFactory)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val installed = CountingTelecomLease()
        val duplicate = CountingTelecomLease()

        val owner = async(Dispatchers.Default) {
            manager.start(conversationId, config, installed.lease, this@runTest)
        }
        assertTrue(factory.factoryEntered.await(1, TimeUnit.SECONDS))
        val waiter = async(Dispatchers.Default) {
            manager.start(conversationId, config, duplicate.lease, this@runTest)
        }

        manager.updateCallStatus(VoiceCallStatus.ForegroundStarting)
        assertFalse(waiter.isCompleted)
        releaseFactory.countDown()

        assertTrue(owner.await() is VoiceAgentManagerStartResult.Started)
        assertTrue(waiter.await() is VoiceAgentManagerStartResult.Existing)
        assertEquals(1, factory.createdCalls.get())
        assertEquals(0, installed.retireCalls)
        assertEquals(1, duplicate.retireCalls)
    }

    @Test
    fun `matching route suspends then returns exact published route`() = runTest {
        val releaseFactory = CountDownLatch(1)
        val factory = BlockingFirstVoiceAgentCallFactory(releaseFactory)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val installed = CountingTelecomLease()

        val owner = async(Dispatchers.Default) {
            manager.start(conversationId, config, installed.lease, this@runTest)
        }
        assertTrue(factory.factoryEntered.await(1, TimeUnit.SECONDS))
        val waiter = async(Dispatchers.Default) {
            manager.matchingRoute(conversationId, config)
        }

        assertFalse(waiter.isCompleted)
        releaseFactory.countDown()

        assertTrue(owner.await() is VoiceAgentManagerStartResult.Started)
        assertEquals(VoiceAgentRouteMatchResult.Existing(installed.lease.metadata), waiter.await())
        assertEquals(1, factory.createdCalls.get())
        assertEquals(0, installed.retireCalls)
    }

    @Test
    fun `matching route failure chain ending idle remains retryable`() = runTest {
        repeat(50) { attempt ->
            val releaseFirstFactory = CountDownLatch(1)
            val creationFailure = IllegalStateException("factory failure $attempt")
            val factory = BlockingFirstThenFailingVoiceAgentCallFactory(
                releaseFirstFactory = releaseFirstFactory,
                failure = creationFailure,
            )
            val manager = VoiceAgentCallManager(factory)
            val conversationId = Uuid.random()
            val config = fakeManagerLaunchConfig()
            val ownerLease = CountingTelecomLease()
            val retryLeases = List(8) { CountingTelecomLease() }

            val owner = async(Dispatchers.Default) {
                runCatching { manager.start(conversationId, config, ownerLease.lease, this@runTest) }
            }
            assertTrue(factory.firstFactoryEntered.await(1, TimeUnit.SECONDS))
            val retryOwners = retryLeases.take(4).map { retryLease ->
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    runCatching { manager.start(conversationId, config, retryLease.lease, this@runTest) }
                }
            }.toMutableList()
            val matching = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                manager.matchingRoute(conversationId, config)
            }
            retryOwners += retryLeases.drop(4).map { retryLease ->
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    runCatching { manager.start(conversationId, config, retryLease.lease, this@runTest) }
                }
            }

            releaseFirstFactory.countDown()

            assertEquals(
                "failure-chain attempt $attempt",
                VoiceAgentRouteMatchResult.NoMatch,
                matching.await(),
            )
            assertSame(creationFailure, owner.await().exceptionOrNull())
            retryOwners.forEach { retryOwner ->
                assertSame(creationFailure, retryOwner.await().exceptionOrNull())
            }
            assertEquals(1, ownerLease.retireCalls)
            retryLeases.forEach { retryLease -> assertEquals(1, retryLease.retireCalls) }
            assertEquals(1 + retryLeases.size, factory.createdCalls.get())
            assertEquals(null, manager.activeConversationId.value)
            assertEquals(VoiceAgentUiState(), manager.state.value)
        }
    }

    @Test
    fun `matching route follows failed owner to matching published replacement`() = runTest {
        val releaseFailure = CountDownLatch(1)
        val releaseReplacement = CountDownLatch(1)
        val creationFailure = NonCopyableCleanupException(Any(), "first factory failed")
        val replacementSession = FakeManagedVoiceCallSession()
        val factory = BlockingFirstFailingVoiceAgentCallFactory(
            releaseFirstFailure = releaseFailure,
            firstFailure = creationFailure,
            releaseSecondCreate = releaseReplacement,
            subsequentSessions = arrayOf(replacementSession),
        )
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val failedLease = CountingTelecomLease()
        val replacementLease = CountingTelecomLease()
        val failedOwner = async(Dispatchers.Default) {
            manager.start(conversationId, config, failedLease.lease, this@runTest)
        }
        assertTrue(factory.firstCreateEntered.await(1, TimeUnit.SECONDS))
        val retryGate = BlockedRetryDispatcher()
        try {
            val matching = async(retryGate.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                manager.matchingRoute(conversationId, config)
            }

            releaseFailure.countDown()
            assertSame(creationFailure, runCatching { failedOwner.await() }.exceptionOrNull())
            val replacement = async(Dispatchers.Default) {
                manager.start(conversationId, config, replacementLease.lease, this@runTest)
            }
            assertTrue(factory.secondCreateEntered.await(1, TimeUnit.SECONDS))

            retryGate.release()
            assertFalse(matching.isCompleted)
            releaseReplacement.countDown()

            assertEquals(
                VoiceAgentManagerStartResult.Started(replacementLease.lease.metadata),
                replacement.await(),
            )
            assertEquals(
                VoiceAgentRouteMatchResult.Existing(replacementLease.lease.metadata),
                matching.await(),
            )
            assertEquals(1, failedLease.retireCalls)
            assertEquals(0, replacementLease.retireCalls)
            assertEquals(1, replacementSession.startCalls)
        } finally {
            releaseReplacement.countDown()
            retryGate.close()
        }
    }

    @Test
    fun `directly superseded matching route returns original immutable route`() = runTest {
        val releaseFactory = CountDownLatch(1)
        val factory = BlockingFirstVoiceAgentCallFactory(releaseFactory)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val originalLease = CountingTelecomLease()
        val owner = async(Dispatchers.Default) {
            manager.start(conversationId, config, originalLease.lease, this@runTest)
        }
        assertTrue(factory.factoryEntered.await(1, TimeUnit.SECONDS))
        val matching = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.matchingRoute(conversationId, config)
        }
        var resolveCalls = 0
        val startup = VoiceAgentCallStartup(manager) {
            resolveCalls += 1
            error("direct supersession must not resolve another route")
        }
        val startupMatch = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            startup.start(conversationId, config, this@runTest) { true }
        }

        manager.end()

        assertEquals(
            VoiceAgentRouteMatchResult.Superseded(originalLease.lease.metadata),
            matching.await(),
        )
        assertEquals(
            VoiceAgentCallStartupResult.Stale(originalLease.lease.metadata),
            startupMatch.await(),
        )
        assertEquals(0, resolveCalls)
        assertEquals(1, factory.createdCalls.get())
        assertEquals(0, factory.session.startCalls)

        releaseFactory.countDown()
        assertEquals(VoiceAgentManagerStartResult.Superseded, owner.await())
        assertEquals(1, originalLease.retireCalls)
        assertEquals(1, factory.session.closeNowCalls)
    }

    @Test
    fun `cancelled matching waiter retires exact lease and preserves cancellation`() = runTest {
        val releaseFactory = CountDownLatch(1)
        val factory = BlockingFirstVoiceAgentCallFactory(releaseFactory)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val installed = CountingTelecomLease()
        val retirementFailure = IllegalStateException("waiter retirement failed")
        val duplicate = CountingTelecomLease(disconnectFailure = retirementFailure)

        val owner = async(Dispatchers.Default) {
            manager.start(conversationId, config, installed.lease, this@runTest)
        }
        assertTrue(factory.factoryEntered.await(1, TimeUnit.SECONDS))
        val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(conversationId, config, duplicate.lease, this@runTest)
        }
        val cancellation = CanonicalCancellationException(Any())

        waiter.cancel(cancellation)
        val thrown = runCatching { waiter.await() }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(listOf(retirementFailure), thrown?.suppressed?.toList())
        assertEquals(1, duplicate.retireCalls)
        assertFalse(owner.isCompleted)
        assertEquals(1, factory.createdCalls.get())
        assertEquals(0, installed.retireCalls)

        releaseFactory.countDown()
        assertTrue(owner.await() is VoiceAgentManagerStartResult.Started)
    }

    @Test
    fun `cancelled matching waiter ignores self suppression from exact retirement failure`() = runTest {
        val releaseFactory = CountDownLatch(1)
        val factory = BlockingFirstVoiceAgentCallFactory(releaseFactory)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val installed = CountingTelecomLease()
        val cancellation = CanonicalCancellationException(Any())
        val duplicate = CountingTelecomLease(disconnectFailure = cancellation)

        val owner = async(Dispatchers.Default) {
            manager.start(conversationId, config, installed.lease, this@runTest)
        }
        assertTrue(factory.factoryEntered.await(1, TimeUnit.SECONDS))
        val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(conversationId, config, duplicate.lease, this@runTest)
        }

        waiter.cancel(cancellation)
        val thrown = runCatching { waiter.await() }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(emptyList<Throwable>(), thrown?.suppressed?.toList())
        assertEquals(1, duplicate.retireCalls)
        assertFalse(owner.isCompleted)
        assertEquals(1, factory.createdCalls.get())
        assertEquals(0, installed.retireCalls)

        releaseFactory.countDown()
        assertTrue(owner.await() is VoiceAgentManagerStartResult.Started)
    }

    @Test
    fun `end invalidates starting before and after factory transfer`() = runTest {
        assertTerminalInvalidatesBlockedFactory(StartingTerminalAction.End, this)
        assertTerminalInvalidatesBlockedSessionStart(StartingTerminalAction.End, this)
    }

    @Test
    fun `detach for drain invalidates starting before and after factory transfer`() = runTest {
        assertTerminalInvalidatesBlockedFactory(StartingTerminalAction.DetachForEndAndDrain, this)
        assertTerminalInvalidatesBlockedSessionStart(StartingTerminalAction.DetachForEndAndDrain, this)
    }

    @Test
    fun `close now invalidates starting before and after factory transfer`() = runTest {
        assertTerminalInvalidatesBlockedFactory(StartingTerminalAction.CloseNow, this)
        assertTerminalInvalidatesBlockedSessionStart(StartingTerminalAction.CloseNow, this)
    }

    @Test
    fun `cancelled reservation owner before factory transfer preserves cancellation and idle`() = runTest {
        val releaseEnd = CountDownLatch(1)
        val predecessor = BlockingEndManagedVoiceCallSession(releaseEnd)
        val factory = FakeVoiceAgentCallFactory(predecessor, FakeManagedVoiceCallSession())
        val manager = VoiceAgentCallManager(factory)
        val predecessorLease = CountingTelecomLease()
        manager.start(Uuid.random(), fakeManagerLaunchConfig(), predecessorLease.lease, this)
        val ownerLease = CountingTelecomLease()
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val owner = async(Dispatchers.Default) {
            manager.start(conversationId, config, ownerLease.lease, this@runTest)
        }
        assertTrue(predecessor.endEntered.await(1, TimeUnit.SECONDS))
        val cancellation = CanonicalCancellationException(Any())

        owner.cancel(cancellation)
        releaseEnd.countDown()
        val thrown = runCatching { owner.await() }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(1, predecessorLease.retireCalls)
        assertEquals(1, ownerLease.retireCalls)
        assertEquals(1, factory.created.size)
        assertEquals(null, manager.activeConversationId.value)
        assertEquals(VoiceAgentRouteMatchResult.NoMatch, manager.matchingRoute(conversationId, config))
    }

    @Test
    fun `cancelled reservation owner after factory creation completes failed for waiter and closes exact session`() = runTest {
        val releaseStart = CountDownLatch(1)
        val cancelledSession = BlockingStartManagedVoiceCallSession(releaseStart)
        val installedSession = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(cancelledSession, installedSession))
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val ownerLease = CountingTelecomLease()
        val waiterLease = CountingTelecomLease()
        val owner = async(Dispatchers.Default) {
            manager.start(conversationId, config, ownerLease.lease, this@runTest)
        }
        assertTrue(cancelledSession.startEntered.await(1, TimeUnit.SECONDS))
        val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(conversationId, config, waiterLease.lease, this@runTest)
        }
        val cancellation = CanonicalCancellationException(Any())

        owner.cancel(cancellation)
        releaseStart.countDown()
        val thrown = runCatching { owner.await() }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(1, cancelledSession.closeNowCalls)
        assertEquals(1, ownerLease.retireCalls)
        assertEquals(VoiceAgentManagerStartResult.Started(waiterLease.lease.metadata), waiter.await())
        assertEquals(0, waiterLease.retireCalls)
        assertEquals(1, installedSession.startCalls)
        assertEquals(conversationId, manager.activeConversationId.value)
    }

    @Test
    fun `failed owner keeps matching callers behind blocked lease cleanup`() = runTest {
        val releaseEnd = CountDownLatch(1)
        val releaseLeaseCleanup = CountDownLatch(1)
        val endFailure = NonCopyableCleanupException(Any(), "predecessor end failed")
        val predecessor = BlockingEndManagedVoiceCallSession(releaseEnd, endFailure)
        val retrySession = FakeManagedVoiceCallSession()
        val factory = SignalingVoiceAgentCallFactory(predecessor, retrySession)
        val manager = VoiceAgentCallManager(factory)
        val predecessorLease = CountingTelecomLease()
        manager.start(Uuid.random(), fakeManagerLaunchConfig(), predecessorLease.lease, this)
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val leaseRetirementEntered = CountDownLatch(1)
        val failedLease = CountingTelecomLease(
            disconnectEntered = leaseRetirementEntered,
            releaseRetirement = releaseLeaseCleanup,
        )
        val failedOwner = async(Dispatchers.Default) {
            manager.start(conversationId, config, failedLease.lease, this@runTest)
        }
        assertTrue(predecessor.endEntered.await(1, TimeUnit.SECONDS))
        val existingWaiterLease = CountingTelecomLease()
        val existingWaiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(conversationId, config, existingWaiterLease.lease, this@runTest)
        }

        releaseEnd.countDown()
        assertTrue(leaseRetirementEntered.await(1, TimeUnit.SECONDS))
        val freshCallerLease = CountingTelecomLease()
        val freshCaller = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(conversationId, config, freshCallerLease.lease, this@runTest)
        }
        try {
            assertFalse(factory.secondCreateEntered.await(200, TimeUnit.MILLISECONDS))
            assertFalse(existingWaiter.isCompleted)
            assertFalse(freshCaller.isCompleted)
        } finally {
            releaseLeaseCleanup.countDown()
        }

        assertSame(endFailure, runCatching { failedOwner.await() }.exceptionOrNull())
        val retryResults = listOf(existingWaiter.await(), freshCaller.await())
        assertEquals(1, retryResults.count { it is VoiceAgentManagerStartResult.Started })
        assertEquals(1, retryResults.count { it is VoiceAgentManagerStartResult.Existing })
        assertEquals(1, failedLease.retireCalls)
        assertEquals(1, predecessorLease.retireCalls)
        assertEquals(1, existingWaiterLease.retireCalls + freshCallerLease.retireCalls)
        assertEquals(2, factory.createCalls.get())
        assertEquals(1, retrySession.startCalls)
    }

    @Test
    fun `cancelled owner keeps matching callers behind blocked session cleanup`() = runTest {
        val releaseStart = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val cancelledSession = BlockingStartManagedVoiceCallSession(
            releaseStart = releaseStart,
            releaseClose = releaseClose,
        )
        val retrySession = FakeManagedVoiceCallSession()
        val factory = SignalingVoiceAgentCallFactory(cancelledSession, retrySession)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val cancelledOwnerLease = CountingTelecomLease()
        val cancelledOwner = async(Dispatchers.Default) {
            manager.start(conversationId, config, cancelledOwnerLease.lease, this@runTest)
        }
        assertTrue(cancelledSession.startEntered.await(1, TimeUnit.SECONDS))
        val existingWaiterLease = CountingTelecomLease()
        val existingWaiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(conversationId, config, existingWaiterLease.lease, this@runTest)
        }
        val cancellation = CanonicalCancellationException(Any())

        cancelledOwner.cancel(cancellation)
        releaseStart.countDown()
        assertTrue(cancelledSession.closeEntered.await(1, TimeUnit.SECONDS))
        val freshCallerLease = CountingTelecomLease()
        val freshCaller = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(conversationId, config, freshCallerLease.lease, this@runTest)
        }
        try {
            assertFalse(factory.secondCreateEntered.await(200, TimeUnit.MILLISECONDS))
            assertFalse(existingWaiter.isCompleted)
            assertFalse(freshCaller.isCompleted)
        } finally {
            releaseClose.countDown()
        }

        assertSame(cancellation, runCatching { cancelledOwner.await() }.exceptionOrNull())
        val retryResults = listOf(existingWaiter.await(), freshCaller.await())
        assertEquals(1, retryResults.count { it is VoiceAgentManagerStartResult.Started })
        assertEquals(1, retryResults.count { it is VoiceAgentManagerStartResult.Existing })
        assertEquals(1, cancelledSession.closeNowCalls)
        assertEquals(1, cancelledOwnerLease.retireCalls)
        assertEquals(1, existingWaiterLease.retireCalls + freshCallerLease.retireCalls)
        assertEquals(2, factory.createCalls.get())
        assertEquals(1, retrySession.startCalls)
    }

    @Test
    fun `cancelled superseded owner closes exact session without clearing newer active slot`() = runTest {
        val releaseStart = CountDownLatch(1)
        val staleSession = BlockingStartManagedVoiceCallSession(releaseStart)
        val installedSession = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(staleSession, installedSession))
        val staleLease = CountingTelecomLease()
        val installedLease = CountingTelecomLease()
        val staleOwner = async(Dispatchers.Default) {
            manager.start(Uuid.random(), fakeManagerLaunchConfig(), staleLease.lease, this@runTest)
        }
        assertTrue(staleSession.startEntered.await(1, TimeUnit.SECONDS))
        val installedConversation = Uuid.random()

        assertEquals(
            VoiceAgentManagerStartResult.Started(installedLease.lease.metadata),
            manager.start(installedConversation, fakeManagerLaunchConfig(), installedLease.lease, this),
        )
        val cancellation = CanonicalCancellationException(Any())
        staleOwner.cancel(cancellation)
        releaseStart.countDown()
        val thrown = runCatching { staleOwner.await() }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(1, staleSession.closeNowCalls)
        assertEquals(1, staleLease.retireCalls)
        assertEquals(0, installedLease.retireCalls)
        assertEquals(installedConversation, manager.activeConversationId.value)
        assertEquals(1, installedSession.startCalls)
    }

    @Test
    fun `failed matching retry is terminally superseded by newer active slot`() = runTest {
        val releaseFailure = CountDownLatch(1)
        val creationFailure = NonCopyableCleanupException(Any(), "first factory failed")
        val installedSession = FakeManagedVoiceCallSession()
        val factory = BlockingFirstFailingVoiceAgentCallFactory(
            releaseFirstFailure = releaseFailure,
            firstFailure = creationFailure,
            subsequentSessions = arrayOf(installedSession),
        )
        val manager = VoiceAgentCallManager(factory)
        val failedConversation = Uuid.random()
        val failedConfig = fakeManagerLaunchConfig()
        val failedOwnerLease = CountingTelecomLease()
        val retryLease = CountingTelecomLease()
        val failedOwner = async(Dispatchers.Default) {
            manager.start(failedConversation, failedConfig, failedOwnerLease.lease, this@runTest)
        }
        assertTrue(factory.firstCreateEntered.await(1, TimeUnit.SECONDS))
        val retryGate = BlockedRetryDispatcher()
        try {
            val retry = async(retryGate.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                manager.start(failedConversation, failedConfig, retryLease.lease, this@runTest)
            }

            releaseFailure.countDown()
            assertSame(creationFailure, runCatching { failedOwner.await() }.exceptionOrNull())
            val installedConversation = Uuid.random()
            val installedLease = CountingTelecomLease()
            assertEquals(
                VoiceAgentManagerStartResult.Started(installedLease.lease.metadata),
                manager.start(installedConversation, fakeManagerLaunchConfig(), installedLease.lease, this),
            )

            retryGate.release()
            assertEquals(VoiceAgentManagerStartResult.Superseded, retry.await())
            assertEquals(1, failedOwnerLease.retireCalls)
            assertEquals(1, retryLease.retireCalls)
            assertEquals(0, installedLease.retireCalls)
            assertEquals(installedConversation, manager.activeConversationId.value)
        } finally {
            retryGate.close()
        }
    }

    @Test
    fun `failed matching start and route match are terminally superseded by newer starting slot`() = runTest {
        val releaseFailure = CountDownLatch(1)
        val releaseNewerCreate = CountDownLatch(1)
        val creationFailure = NonCopyableCleanupException(Any(), "first factory failed")
        val newerSession = FakeManagedVoiceCallSession()
        val factory = BlockingFirstFailingVoiceAgentCallFactory(
            releaseFirstFailure = releaseFailure,
            firstFailure = creationFailure,
            releaseSecondCreate = releaseNewerCreate,
            subsequentSessions = arrayOf(newerSession),
        )
        val manager = VoiceAgentCallManager(factory)
        val failedConversation = Uuid.random()
        val failedConfig = fakeManagerLaunchConfig()
        val failedOwnerLease = CountingTelecomLease()
        val retryLease = CountingTelecomLease()
        val failedOwner = async(Dispatchers.Default) {
            manager.start(failedConversation, failedConfig, failedOwnerLease.lease, this@runTest)
        }
        assertTrue(factory.firstCreateEntered.await(1, TimeUnit.SECONDS))
        val retryGate = BlockedRetryDispatcher()
        try {
            val retry = async(retryGate.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                manager.start(failedConversation, failedConfig, retryLease.lease, this@runTest)
            }
            val matchingRoute = async(retryGate.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                manager.matchingRoute(failedConversation, failedConfig)
            }

            releaseFailure.countDown()
            assertSame(creationFailure, runCatching { failedOwner.await() }.exceptionOrNull())
            val newerLease = CountingTelecomLease()
            val newerOwner = async(Dispatchers.Default) {
                manager.start(Uuid.random(), fakeManagerLaunchConfig(), newerLease.lease, this@runTest)
            }
            assertTrue(factory.secondCreateEntered.await(1, TimeUnit.SECONDS))

            retryGate.release()
            assertEquals(VoiceAgentManagerStartResult.Superseded, retry.await())
            assertEquals(
                VoiceAgentRouteMatchResult.Superseded(failedOwnerLease.lease.metadata),
                matchingRoute.await(),
            )
            assertEquals(1, retryLease.retireCalls)
            assertFalse(newerOwner.isCompleted)

            releaseNewerCreate.countDown()
            assertTrue(newerOwner.await() is VoiceAgentManagerStartResult.Started)
            assertEquals(0, newerLease.retireCalls)
        } finally {
            releaseNewerCreate.countDown()
            retryGate.close()
        }
    }

    @Test
    fun `superseded unconsumed lease retirement failure stays primary and is attempted once`() = runTest {
        val releaseEnd = CountDownLatch(1)
        val firstSession = BlockingEndManagedVoiceCallSession(releaseEnd)
        val installedSession = FakeManagedVoiceCallSession()
        val factory = FakeVoiceAgentCallFactory(firstSession, installedSession)
        val manager = VoiceAgentCallManager(factory)
        val activeLease = CountingTelecomLease()
        manager.start(Uuid.random(), fakeManagerLaunchConfig(), activeLease.lease, this)
        val retirementFailure = NonCopyableCleanupException(Any(), "stale lease retirement failed")
        val staleLease = CountingTelecomLease(disconnectFailure = retirementFailure)
        val installedLease = CountingTelecomLease()

        val staleOwner = async(Dispatchers.Default) {
            manager.start(Uuid.random(), fakeManagerLaunchConfig(), staleLease.lease, this@runTest)
        }
        assertTrue(firstSession.endEntered.await(1, TimeUnit.SECONDS))
        val replacement = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(Uuid.random(), fakeManagerLaunchConfig(), installedLease.lease, this@runTest)
        }

        assertFalse(replacement.isCompleted)
        assertEquals(1, factory.created.size)

        releaseEnd.countDown()
        val thrown = runCatching { staleOwner.await() }.exceptionOrNull()

        assertSame(retirementFailure, thrown)
        assertEquals(emptyList<Throwable>(), thrown?.suppressed?.toList())
        assertEquals(1, staleLease.retireCalls)
        assertTrue(replacement.await() is VoiceAgentManagerStartResult.Started)
        assertEquals(0, installedLease.retireCalls)
        assertEquals(1, installedSession.startCalls)
    }

    @Test
    fun `superseded created session close failure stays primary and is attempted once`() = runTest {
        val releaseCreate = CountDownLatch(1)
        val closeFailure = NonCopyableCleanupException(Any(), "stale created session close failed")
        val staleSession = CloseFailingManagedVoiceCallSession(closeFailure)
        val installedSession = FakeManagedVoiceCallSession()
        val factory = BlockingFirstCreateVoiceAgentCallFactory(
            releaseFirstCreate = releaseCreate,
            firstSession = staleSession,
            secondSession = installedSession,
        )
        val manager = VoiceAgentCallManager(factory)
        val staleLease = CountingTelecomLease()
        val installedLease = CountingTelecomLease()

        val staleOwner = async(Dispatchers.Default) {
            manager.start(Uuid.random(), fakeManagerLaunchConfig(), staleLease.lease, this@runTest)
        }
        assertTrue(factory.firstCreateEntered.await(1, TimeUnit.SECONDS))

        val replacement = manager.start(Uuid.random(), fakeManagerLaunchConfig(), installedLease.lease, this)
        releaseCreate.countDown()
        val thrown = runCatching { staleOwner.await() }.exceptionOrNull()

        assertTrue(replacement is VoiceAgentManagerStartResult.Started)
        assertSame(closeFailure, thrown)
        assertEquals(emptyList<Throwable>(), thrown?.suppressed?.toList())
        assertEquals(0, staleSession.startCalls)
        assertEquals(1, staleSession.closeNowCalls)
        assertEquals(1, staleLease.retireCalls)
        assertEquals(0, installedLease.retireCalls)
        assertEquals(1, installedSession.startCalls)
    }

    @Test
    fun `superseded started session close failure stays primary and is attempted once`() = runTest {
        val releaseStart = CountDownLatch(1)
        val closeFailure = NonCopyableCleanupException(Any(), "stale session close failed")
        val staleSession = BlockingStartManagedVoiceCallSession(releaseStart, closeFailure)
        val installedSession = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(staleSession, installedSession))
        val staleLease = CountingTelecomLease()
        val installedLease = CountingTelecomLease()

        val staleOwner = async(Dispatchers.Default) {
            manager.start(Uuid.random(), fakeManagerLaunchConfig(), staleLease.lease, this@runTest)
        }
        assertTrue(staleSession.startEntered.await(1, TimeUnit.SECONDS))

        val replacement = manager.start(Uuid.random(), fakeManagerLaunchConfig(), installedLease.lease, this)
        releaseStart.countDown()
        val thrown = runCatching { staleOwner.await() }.exceptionOrNull()

        assertTrue(replacement is VoiceAgentManagerStartResult.Started)
        assertSame(closeFailure, thrown)
        assertEquals(emptyList<Throwable>(), thrown?.suppressed?.toList())
        assertEquals(1, staleSession.closeNowCalls)
        assertEquals(1, staleLease.retireCalls)
        assertEquals(0, installedLease.retireCalls)
        assertEquals(1, installedSession.startCalls)
    }

    @Test
    fun `synchronous session start failure clears aggregate and closes exact route once`() = runTest {
        val startFailure = IllegalStateException("session start failed")
        val cleanupFailure = IllegalArgumentException("route cleanup failed")
        val session = FakeManagedVoiceCallSession(
            initialState = VoiceAgentUiState(session = VoiceSessionStatus.Error("stale in-flight state")),
            startFailure = startFailure,
        )
        val factory = FakeVoiceAgentCallFactory(session)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val lease = CountingTelecomLease(disconnectFailure = cleanupFailure)
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val thrown = try {
            runCatching {
                manager.start(conversationId, config, lease.lease, collectorScope)
            }.exceptionOrNull()
        } finally {
            collectorScope.cancel()
        }

        assertSame(startFailure, thrown)
        assertEquals(listOf(cleanupFailure), thrown?.suppressed?.toList())
        assertEquals(1, lease.retireCalls)
        assertEquals(1, session.closeNowCalls)
        assertEquals(null, manager.activeConversationId.value)
        assertEquals(VoiceAgentRouteMatchResult.NoMatch, manager.matchingRoute(conversationId, config))
        assertEquals(VoiceAgentUiState(), manager.state.value)
        assertEquals(1, factory.created.size)
    }

    @Test
    fun `factory failure consumes lease and manager does not retire it twice`() = runTest {
        val creationFailure = IllegalStateException("factory failed")
        val factoryFailureLease = CountingTelecomLease()
        val manager = VoiceAgentCallManager(ConsumingFailingVoiceAgentCallFactory(creationFailure))

        val thrown = runCatching {
            manager.start(Uuid.random(), fakeManagerLaunchConfig(), factoryFailureLease.lease, this)
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
        manager.start(conversationId, fakeManagerLaunchConfig(), lease.lease, this)

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
        manager.start(Uuid.random(), fakeManagerLaunchConfig(), lease.lease, this)

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
        manager.start(Uuid.random(), fakeManagerLaunchConfig(), firstLease.lease, this)
        val detached = manager.detachForEndAndDrain()
        val secondConversation = Uuid.random()
        manager.start(secondConversation, fakeManagerLaunchConfig(), secondLease.lease, this)

        detached?.endAndDrain()

        assertEquals(1, firstLease.retireCalls)
        assertEquals(0, secondLease.retireCalls)
        assertEquals(1, first.endAndDrainCalls)
        assertEquals(0, second.endAndDrainCalls)
        assertEquals(secondConversation, manager.activeConversationId.value)
    }
}

private enum class StartingTerminalAction {
    End,
    DetachForEndAndDrain,
    CloseNow,
}

private suspend fun assertTerminalInvalidatesBlockedFactory(
    action: StartingTerminalAction,
    scope: CoroutineScope,
) {
    val releaseFactory = CountDownLatch(1)
    val factory = BlockingFirstVoiceAgentCallFactory(releaseFactory)
    val manager = VoiceAgentCallManager(factory)
    val conversationId = Uuid.random()
    val config = fakeManagerLaunchConfig()
    val lease = CountingTelecomLease()
    val waiterLease = CountingTelecomLease()
    val owner = scope.async(Dispatchers.Default) {
        manager.start(conversationId, config, lease.lease, scope)
    }
    assertTrue(factory.factoryEntered.await(1, TimeUnit.SECONDS))
    val waiter = scope.async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        manager.start(conversationId, config, waiterLease.lease, scope)
    }

    invokeTerminal(action, manager)
    assertEquals(VoiceAgentManagerStartResult.Superseded, waiter.await())
    releaseFactory.countDown()

    assertEquals(VoiceAgentManagerStartResult.Superseded, owner.await())
    assertEquals(1, lease.retireCalls)
    assertEquals(1, waiterLease.retireCalls)
    assertEquals(0, factory.session.startCalls)
    assertEquals(1, factory.session.closeNowCalls)
    assertEquals(null, manager.activeConversationId.value)
    assertEquals(VoiceAgentRouteMatchResult.NoMatch, manager.matchingRoute(conversationId, config))
}

private suspend fun assertTerminalInvalidatesBlockedSessionStart(
    action: StartingTerminalAction,
    scope: CoroutineScope,
) {
    val releaseStart = CountDownLatch(1)
    val session = BlockingStartManagedVoiceCallSession(releaseStart)
    val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(session))
    val conversationId = Uuid.random()
    val config = fakeManagerLaunchConfig()
    val lease = CountingTelecomLease()
    val waiterLease = CountingTelecomLease()
    val owner = scope.async(Dispatchers.Default) {
        manager.start(conversationId, config, lease.lease, scope)
    }
    assertTrue(session.startEntered.await(1, TimeUnit.SECONDS))
    val waiter = scope.async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        manager.start(conversationId, config, waiterLease.lease, scope)
    }

    invokeTerminal(action, manager)
    assertEquals(VoiceAgentManagerStartResult.Superseded, waiter.await())
    releaseStart.countDown()

    assertEquals(VoiceAgentManagerStartResult.Superseded, owner.await())
    assertEquals(1, lease.retireCalls)
    assertEquals(1, waiterLease.retireCalls)
    assertEquals(1, session.closeNowCalls)
    assertEquals(null, manager.activeConversationId.value)
    assertEquals(VoiceAgentRouteMatchResult.NoMatch, manager.matchingRoute(conversationId, config))
}

private fun invokeTerminal(action: StartingTerminalAction, manager: VoiceAgentCallManager) {
    when (action) {
        StartingTerminalAction.End -> manager.end()
        StartingTerminalAction.DetachForEndAndDrain -> assertEquals(null, manager.detachForEndAndDrain())
        StartingTerminalAction.CloseNow -> manager.closeNow()
    }
}

private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
    val testScope = CoroutineScope(coroutineContext + SupervisorJob())
    try { testScope.block() } finally { testScope.cancel() }
}
