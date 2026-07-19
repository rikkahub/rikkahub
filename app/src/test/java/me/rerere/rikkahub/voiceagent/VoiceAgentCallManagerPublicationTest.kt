package me.rerere.rikkahub.voiceagent

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentCallManagerPublicationTest {
    @Test
    fun `terminal detach before final publication supersedes owner and matching callers`() = runPublicationTest {
        val session = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(session))
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val ownerLease = CountingTelecomLease()
        val waiterLease = CountingTelecomLease()
        val collectorDispatcher = BlockingCollectorDispatcher()
        val collectorScope = CoroutineScope(SupervisorJob() + collectorDispatcher)
        try {
            val owner = async(Dispatchers.Default) {
                manager.start(conversationId, config, ownerLease.lease, collectorScope)
            }
            assertTrue(collectorDispatcher.dispatchEntered.await(1, TimeUnit.SECONDS))
            val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                manager.start(conversationId, config, waiterLease.lease, this@runPublicationTest)
            }
            var resolveCalls = 0
            val startup = VoiceAgentCallStartup(manager) {
                resolveCalls += 1
                error("pending matching publication must not resolve another route")
            }
            val startupWaiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                startup.start(conversationId, config, this@runPublicationTest) { true }
            }

            assertFalse(waiter.isCompleted)
            assertFalse(startupWaiter.isCompleted)
            manager.end()

            assertEquals(VoiceAgentManagerStartResult.Superseded, waiter.await())
            assertEquals(
                VoiceAgentCallStartupResult.Stale(ownerLease.lease.metadata),
                startupWaiter.await(),
            )
            assertEquals(0, resolveCalls)
            collectorDispatcher.release()
            assertEquals(VoiceAgentManagerStartResult.Superseded, owner.await())

            session.state.value = VoiceAgentUiState(session = VoiceSessionStatus.Connected)
            yield()
            assertEquals(null, manager.activeConversationId.value)
            assertEquals(VoiceCallStatus.Ending, manager.state.value.call)
            assertEquals(1, session.endCalls)
            assertEquals(0, session.closeNowCalls)
            assertEquals(1, ownerLease.retireCalls)
            assertEquals(1, waiterLease.retireCalls)
        } finally {
            collectorDispatcher.release()
            collectorScope.cancel()
            collectorDispatcher.close()
        }
    }

    @Test
    fun `replacement detach before final publication supersedes old callers and preserves new active`() =
        runPublicationTest {
            val staleSession = FakeManagedVoiceCallSession()
            val replacementSession = FakeManagedVoiceCallSession(
                initialState = VoiceAgentUiState(session = VoiceSessionStatus.PreparingContext),
            )
            val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(staleSession, replacementSession))
            val staleConversation = Uuid.random()
            val staleConfig = fakeManagerLaunchConfig("stale")
            val staleLease = CountingTelecomLease()
            val waiterLease = CountingTelecomLease()
            val collectorDispatcher = BlockingCollectorDispatcher()
            val collectorScope = CoroutineScope(SupervisorJob() + collectorDispatcher)
            try {
                val staleOwner = async(Dispatchers.Default) {
                    manager.start(staleConversation, staleConfig, staleLease.lease, collectorScope)
                }
                assertTrue(collectorDispatcher.dispatchEntered.await(1, TimeUnit.SECONDS))
                val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    manager.start(staleConversation, staleConfig, waiterLease.lease, this@runPublicationTest)
                }
                var resolveCalls = 0
                val startup = VoiceAgentCallStartup(manager) {
                    resolveCalls += 1
                    error("superseded matching publication must not resolve another route")
                }
                val startupWaiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    startup.start(staleConversation, staleConfig, this@runPublicationTest) { true }
                }

                assertFalse(waiter.isCompleted)
                assertFalse(startupWaiter.isCompleted)
                val replacementConversation = Uuid.random()
                val replacementLease = CountingTelecomLease()
                assertEquals(
                    VoiceAgentManagerStartResult.Started(replacementLease.lease.metadata),
                    manager.start(
                        replacementConversation,
                        fakeManagerLaunchConfig("replacement"),
                        replacementLease.lease,
                        this,
                    ),
                )

                assertEquals(VoiceAgentManagerStartResult.Superseded, waiter.await())
                assertEquals(
                    VoiceAgentCallStartupResult.Stale(staleLease.lease.metadata),
                    startupWaiter.await(),
                )
                assertEquals(0, resolveCalls)
                collectorDispatcher.release()
                assertEquals(VoiceAgentManagerStartResult.Superseded, staleOwner.await())

                staleSession.state.value = VoiceAgentUiState(session = VoiceSessionStatus.Error("stale"))
                yield()
                assertEquals(replacementConversation, manager.activeConversationId.value)
                assertEquals(VoiceSessionStatus.PreparingContext, manager.state.value.session)
                assertEquals(1, staleSession.endCalls)
                assertEquals(0, staleSession.closeNowCalls)
                assertEquals(1, staleLease.retireCalls)
                assertEquals(1, waiterLease.retireCalls)
                assertEquals(0, replacementLease.retireCalls)
            } finally {
                collectorDispatcher.release()
                collectorScope.cancel()
                collectorDispatcher.close()
            }
        }

    @Test
    fun `cancelled owner after active install fails publication and wakes matching retry`() = runPublicationTest {
        val cancelledSession = FakeManagedVoiceCallSession()
        val retrySession = FakeManagedVoiceCallSession(
            initialState = VoiceAgentUiState(session = VoiceSessionStatus.PreparingContext),
        )
        val factory = FakeVoiceAgentCallFactory(cancelledSession, retrySession)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val ownerLease = CountingTelecomLease()
        val waiterLease = CountingTelecomLease()
        val collectorDispatcher = BlockingCollectorDispatcher()
        val collectorScope = CoroutineScope(SupervisorJob() + collectorDispatcher)
        try {
            val owner = async(Dispatchers.Default) {
                manager.start(conversationId, config, ownerLease.lease, collectorScope)
            }
            assertTrue(collectorDispatcher.dispatchEntered.await(1, TimeUnit.SECONDS))
            val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                manager.start(conversationId, config, waiterLease.lease, this@runPublicationTest)
            }
            val cancellation = CanonicalCancellationException(Any())

            owner.cancel(cancellation)
            assertFalse(waiter.isCompleted)
            collectorDispatcher.release()

            assertSame(cancellation, runCatching { owner.await() }.exceptionOrNull())
            assertEquals(
                VoiceAgentManagerStartResult.Started(waiterLease.lease.metadata),
                waiter.await(),
            )
            cancelledSession.state.value = VoiceAgentUiState(session = VoiceSessionStatus.Error("stale"))
            yield()

            assertEquals(conversationId, manager.activeConversationId.value)
            assertEquals(VoiceSessionStatus.PreparingContext, manager.state.value.session)
            assertEquals(1, cancelledSession.startCalls)
            assertEquals(1, cancelledSession.closeNowCalls)
            assertEquals(0, cancelledSession.endCalls)
            assertEquals(1, ownerLease.retireCalls)
            assertEquals(1, retrySession.startCalls)
            assertEquals(0, retrySession.closeNowCalls)
            assertEquals(0, waiterLease.retireCalls)
            assertEquals(2, factory.created.size)
            assertEquals(
                VoiceAgentRouteMatchResult.Existing(waiterLease.lease.metadata),
                manager.matchingRoute(conversationId, config),
            )
        } finally {
            collectorDispatcher.release()
            collectorScope.cancel()
            collectorDispatcher.close()
        }
    }

    @Test
    fun `cancelled owner claims cleanup before blocked close and gates terminal retry`() = runPublicationTest {
        val releaseClose = java.util.concurrent.CountDownLatch(1)
        val closeFailure = NonCopyableCleanupException(Any(), "claimed session close failed")
        val cancelledSession = BlockingCloseManagedVoiceCallSession(releaseClose, closeFailure)
        val retrySession = FakeManagedVoiceCallSession(
            initialState = VoiceAgentUiState(session = VoiceSessionStatus.PreparingContext),
        )
        val factory = FakeVoiceAgentCallFactory(cancelledSession, retrySession)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val ownerLease = CountingTelecomLease()
        val waiterLease = CountingTelecomLease()
        val freshLease = CountingTelecomLease()
        val collectorDispatcher = BlockingCollectorDispatcher()
        val collectorScope = CoroutineScope(SupervisorJob() + collectorDispatcher)
        try {
            val owner = async(Dispatchers.Default) {
                manager.start(conversationId, config, ownerLease.lease, collectorScope)
            }
            assertTrue(collectorDispatcher.dispatchEntered.await(1, TimeUnit.SECONDS))
            val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                manager.start(conversationId, config, waiterLease.lease, this@runPublicationTest)
            }
            val cancellation = CanonicalCancellationException(Any())

            owner.cancel(cancellation)
            collectorDispatcher.release()
            assertTrue(cancelledSession.closeEntered.await(1, TimeUnit.SECONDS))

            manager.end()
            val fresh = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                manager.start(conversationId, config, freshLease.lease, this@runPublicationTest)
            }

            assertFalse(waiter.isCompleted)
            assertFalse(fresh.isCompleted)
            assertEquals(1, factory.created.size)
            assertEquals(1, cancelledSession.closeNowCalls)
            assertEquals(0, cancelledSession.endCalls)
            assertEquals(0, cancelledSession.lifecycleOverlapCalls)

            releaseClose.countDown()
            val ownerFailure = runCatching { owner.await() }.exceptionOrNull()
            assertSame(cancellation, ownerFailure)
            assertEquals(listOf(closeFailure), ownerFailure?.suppressed?.toList())
            val waiterResult = waiter.await()
            val freshResult = fresh.await()
            when (waiterResult) {
                is VoiceAgentManagerStartResult.Started -> {
                    assertEquals(waiterLease.lease.metadata, waiterResult.route)
                    assertEquals(VoiceAgentManagerStartResult.Existing(waiterResult.route), freshResult)
                    assertEquals(0, waiterLease.retireCalls)
                    assertEquals(1, freshLease.retireCalls)
                }
                is VoiceAgentManagerStartResult.Existing -> {
                    val started = freshResult as VoiceAgentManagerStartResult.Started
                    assertEquals(freshLease.lease.metadata, started.route)
                    assertEquals(VoiceAgentManagerStartResult.Existing(started.route), waiterResult)
                    assertEquals(1, waiterLease.retireCalls)
                    assertEquals(0, freshLease.retireCalls)
                }
                VoiceAgentManagerStartResult.Superseded -> error("matching waiter must retry after Failed")
            }

            assertEquals(1, cancelledSession.closeNowCalls)
            assertEquals(0, cancelledSession.endCalls)
            assertEquals(0, cancelledSession.lifecycleOverlapCalls)
            assertEquals(1, ownerLease.retireCalls)
            assertEquals(2, factory.created.size)
            assertEquals(1, retrySession.startCalls)
            assertEquals(conversationId, manager.activeConversationId.value)
            assertEquals(VoiceSessionStatus.PreparingContext, manager.state.value.session)
            assertEquals(VoiceCallStatus.Ending, manager.state.value.call)
        } finally {
            releaseClose.countDown()
            collectorDispatcher.release()
            collectorScope.cancel()
            collectorDispatcher.close()
        }
    }
}

private fun runPublicationTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
    val testScope = CoroutineScope(coroutineContext + SupervisorJob())
    try {
        testScope.block()
    } finally {
        testScope.cancel()
    }
}
