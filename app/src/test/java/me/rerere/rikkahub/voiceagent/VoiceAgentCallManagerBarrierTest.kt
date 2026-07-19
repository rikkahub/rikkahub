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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentCallManagerBarrierTest {
    @Test
    fun `failed predecessor cleanup is replayed identically to every inheriting reservation`() = runBarrierTest {
        val releaseEnd = CountDownLatch(1)
        val endFailure = NonCopyableCleanupException(Any(), "predecessor end failed")
        val predecessor = BlockingEndManagedVoiceCallSession(releaseEnd, endFailure)
        val factory = FakeVoiceAgentCallFactory(
            predecessor,
            FakeManagedVoiceCallSession(),
            FakeManagedVoiceCallSession(),
            FakeManagedVoiceCallSession(),
        )
        val manager = VoiceAgentCallManager(factory)
        val predecessorLease = CountingTelecomLease()
        manager.start(Uuid.random(), fakeManagerLaunchConfig(), predecessorLease.lease, this)
        val inheritingLeases = List(3) { CountingTelecomLease() }
        val first = async(Dispatchers.Default) {
            manager.start(
                Uuid.random(),
                fakeManagerLaunchConfig("first"),
                inheritingLeases[0].lease,
                this@runBarrierTest,
            )
        }
        assertTrue(predecessor.endEntered.await(1, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(
                Uuid.random(),
                fakeManagerLaunchConfig("second"),
                inheritingLeases[1].lease,
                this@runBarrierTest,
            )
        }
        val third = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(
                Uuid.random(),
                fakeManagerLaunchConfig("third"),
                inheritingLeases[2].lease,
                this@runBarrierTest,
            )
        }

        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertFalse(third.isCompleted)
        assertEquals(1, factory.created.size)

        releaseEnd.countDown()
        listOf(first, second, third).forEach { owner ->
            assertSame(endFailure, runCatching { owner.await() }.exceptionOrNull())
        }
        assertEquals(1, predecessorLease.retireCalls)
        inheritingLeases.forEach { assertEquals(1, it.retireCalls) }
        assertEquals(1, factory.created.size)
        assertEquals(null, manager.activeConversationId.value)
    }

    @Test
    fun `terminal invalidation preserves inherited predecessor cleanup until success`() = runBarrierTest {
        assertTerminalPreservesInheritedBarrier(endFailure = null, scope = this)
    }

    @Test
    fun `terminal invalidation replays inherited predecessor cleanup failure to fresh start`() = runBarrierTest {
        assertTerminalPreservesInheritedBarrier(
            endFailure = NonCopyableCleanupException(Any(), "predecessor end failed"),
            scope = this,
        )
    }

    @Test
    fun `inheriting owner cancellation preserves predecessor cleanup until success`() = runBarrierTest {
        assertCancellationPreservesInheritedBarrier(endFailure = null, scope = this)
    }

    @Test
    fun `inheriting owner cancellation replays predecessor cleanup failure to fresh start`() = runBarrierTest {
        assertCancellationPreservesInheritedBarrier(
            endFailure = NonCopyableCleanupException(Any(), "predecessor end failed"),
            scope = this,
        )
    }

    @Test
    fun `cancellation preserves barrier that fails during blocked lease retirement`() = runBarrierTest {
        val releaseEnd = CountDownLatch(1)
        val releaseRetirement = CountDownLatch(1)
        val endFailure = NonCopyableCleanupException(Any(), "predecessor end failed")
        val predecessor = BlockingEndManagedVoiceCallSession(releaseEnd, endFailure)
        val factory = FakeVoiceAgentCallFactory(predecessor, FakeManagedVoiceCallSession())
        val manager = VoiceAgentCallManager(factory)
        val predecessorLease = CountingTelecomLease()
        manager.start(Uuid.random(), fakeManagerLaunchConfig(), predecessorLease.lease, this)
        val cleanupOwnerLease = CountingTelecomLease()
        val cleanupOwner = async(Dispatchers.Default) {
            manager.start(
                Uuid.random(),
                fakeManagerLaunchConfig("cleanup-owner"),
                cleanupOwnerLease.lease,
                this@runBarrierTest,
            )
        }
        assertTrue(predecessor.endEntered.await(1, TimeUnit.SECONDS))
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig("cancelled-inheritor")
        val retirementEntered = CountDownLatch(1)
        val cancelledLease = CountingTelecomLease(
            disconnectEntered = retirementEntered,
            releaseRetirement = releaseRetirement,
        )
        val cancelled = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(conversationId, config, cancelledLease.lease, this@runBarrierTest)
        }
        val cancellation = CanonicalCancellationException(Any())

        cancelled.cancel(cancellation)
        assertTrue(retirementEntered.await(1, TimeUnit.SECONDS))
        releaseEnd.countDown()
        assertSame(endFailure, runCatching { cleanupOwner.await() }.exceptionOrNull())
        val freshLease = CountingTelecomLease()
        val fresh = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(conversationId, config, freshLease.lease, this@runBarrierTest)
        }
        try {
            assertFalse(fresh.isCompleted)
            assertEquals(1, factory.created.size)
        } finally {
            releaseRetirement.countDown()
        }

        assertSame(cancellation, runCatching { cancelled.await() }.exceptionOrNull())
        assertSame(endFailure, runCatching { fresh.await() }.exceptionOrNull())
        assertEquals(1, factory.created.size)
        assertEquals(1, predecessorLease.retireCalls)
        assertEquals(1, cleanupOwnerLease.retireCalls)
        assertEquals(1, cancelledLease.retireCalls)
        assertEquals(1, freshLease.retireCalls)
    }
}

private suspend fun assertTerminalPreservesInheritedBarrier(
    endFailure: Throwable?,
    scope: CoroutineScope,
) {
    val releaseEnd = CountDownLatch(1)
    val predecessor = BlockingEndManagedVoiceCallSession(releaseEnd, endFailure)
    val freshSession = FakeManagedVoiceCallSession()
    val factory = FakeVoiceAgentCallFactory(predecessor, freshSession)
    val manager = VoiceAgentCallManager(factory)
    val predecessorLease = CountingTelecomLease()
    manager.start(Uuid.random(), fakeManagerLaunchConfig(), predecessorLease.lease, scope)
    val cleanupOwnerLease = CountingTelecomLease()
    val cleanupOwner = scope.async(Dispatchers.Default) {
        manager.start(Uuid.random(), fakeManagerLaunchConfig("cleanup-owner"), cleanupOwnerLease.lease, scope)
    }
    assertTrue(predecessor.endEntered.await(1, TimeUnit.SECONDS))
    val invalidatedLease = CountingTelecomLease()
    val invalidated = scope.async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        manager.start(Uuid.random(), fakeManagerLaunchConfig("invalidated"), invalidatedLease.lease, scope)
    }

    manager.end()
    assertEquals(
        VoiceAgentRouteMatchResult.NoMatch,
        manager.matchingRoute(Uuid.random(), fakeManagerLaunchConfig("fenced-match")),
    )
    manager.closeNow()
    val freshLease = CountingTelecomLease()
    val fresh = scope.async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        manager.start(Uuid.random(), fakeManagerLaunchConfig("fresh"), freshLease.lease, scope)
    }
    try {
        assertFalse(fresh.isCompleted)
        assertEquals(1, factory.created.size)
    } finally {
        releaseEnd.countDown()
    }

    if (endFailure == null) {
        assertEquals(VoiceAgentManagerStartResult.Superseded, cleanupOwner.await())
        assertEquals(VoiceAgentManagerStartResult.Superseded, invalidated.await())
        assertEquals(
            VoiceAgentManagerStartResult.Started(freshLease.lease.metadata),
            fresh.await(),
        )
        assertEquals(2, factory.created.size)
        assertEquals(1, freshSession.startCalls)
        assertEquals(0, freshLease.retireCalls)
    } else {
        listOf(cleanupOwner, invalidated, fresh).forEach { owner ->
            assertSame(endFailure, runCatching { owner.await() }.exceptionOrNull())
        }
        assertEquals(1, factory.created.size)
        assertEquals(1, freshLease.retireCalls)
    }
    assertEquals(1, predecessorLease.retireCalls)
    assertEquals(1, cleanupOwnerLease.retireCalls)
    assertEquals(1, invalidatedLease.retireCalls)
}

private suspend fun assertCancellationPreservesInheritedBarrier(
    endFailure: Throwable?,
    scope: CoroutineScope,
) {
    val releaseEnd = CountDownLatch(1)
    val predecessor = BlockingEndManagedVoiceCallSession(releaseEnd, endFailure)
    val freshSession = FakeManagedVoiceCallSession()
    val factory = FakeVoiceAgentCallFactory(predecessor, freshSession)
    val manager = VoiceAgentCallManager(factory)
    val predecessorLease = CountingTelecomLease()
    manager.start(Uuid.random(), fakeManagerLaunchConfig(), predecessorLease.lease, scope)
    val cleanupOwnerLease = CountingTelecomLease()
    val cleanupOwner = scope.async(Dispatchers.Default) {
        manager.start(Uuid.random(), fakeManagerLaunchConfig("cleanup-owner"), cleanupOwnerLease.lease, scope)
    }
    assertTrue(predecessor.endEntered.await(1, TimeUnit.SECONDS))
    val cancelledLease = CountingTelecomLease()
    val cancelled = scope.async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        manager.start(Uuid.random(), fakeManagerLaunchConfig("cancelled"), cancelledLease.lease, scope)
    }
    val cancellation = CanonicalCancellationException(Any())

    cancelled.cancel(cancellation)
    assertSame(cancellation, runCatching { cancelled.await() }.exceptionOrNull())
    val freshLease = CountingTelecomLease()
    val fresh = scope.async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        manager.start(Uuid.random(), fakeManagerLaunchConfig("fresh"), freshLease.lease, scope)
    }
    try {
        assertFalse(fresh.isCompleted)
        assertEquals(1, factory.created.size)
    } finally {
        releaseEnd.countDown()
    }

    if (endFailure == null) {
        assertEquals(VoiceAgentManagerStartResult.Superseded, cleanupOwner.await())
        assertEquals(
            VoiceAgentManagerStartResult.Started(freshLease.lease.metadata),
            fresh.await(),
        )
        assertEquals(2, factory.created.size)
        assertEquals(1, freshSession.startCalls)
        assertEquals(0, freshLease.retireCalls)
    } else {
        assertSame(endFailure, runCatching { cleanupOwner.await() }.exceptionOrNull())
        assertSame(endFailure, runCatching { fresh.await() }.exceptionOrNull())
        assertEquals(1, factory.created.size)
        assertEquals(1, freshLease.retireCalls)
    }
    assertEquals(1, predecessorLease.retireCalls)
    assertEquals(1, cleanupOwnerLease.retireCalls)
    assertEquals(1, cancelledLease.retireCalls)
}

private fun runBarrierTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
    val testScope = CoroutineScope(coroutineContext + SupervisorJob())
    try {
        testScope.block()
    } finally {
        testScope.cancel()
    }
}
