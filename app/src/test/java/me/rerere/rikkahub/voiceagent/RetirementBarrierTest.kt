package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RetirementBarrierTest {
    @Test
    fun `reentrant retirement executes cleanup once`() {
        val barrier = RetirementBarrier()
        val cleanupCalls = AtomicInteger()

        barrier.retire {
            cleanupCalls.incrementAndGet()
            barrier.retire { cleanupCalls.incrementAndGet() }
        }

        assertEquals(1, cleanupCalls.get())
    }

    @Test
    fun `cleanup preserves primary failure and restores interruption`() {
        val calls = mutableListOf<String>()
        val primary = AssertionError("primary")
        val ownerJoinFailure = IllegalStateException("owner join failed")
        val waiterJoinInterruption = InterruptedException("waiter join interrupted")

        val thrown = runCatching {
            runWithNonMaskingCleanup(
                cleanupActions = listOf(
                    { calls += "release" },
                    {
                        calls += "owner"
                        throw ownerJoinFailure
                    },
                    {
                        calls += "waiter"
                        throw waiterJoinInterruption
                    },
                ),
            ) {
                throw primary
            }
        }.exceptionOrNull()
        val interrupted = Thread.interrupted()

        assertSame(primary, thrown)
        assertEquals(listOf(ownerJoinFailure, waiterJoinInterruption), primary.suppressed.toList())
        assertEquals(listOf("release", "owner", "waiter"), calls)
        assertTrue(interrupted)
    }

    @Test
    fun `cleanup rethrows primary interruption and restores then clears interrupt flag`() {
        val calls = mutableListOf<String>()
        val primary = InterruptedException("primary interruption")

        val thrown = runCatching {
            runWithNonMaskingCleanup(
                cleanupActions = listOf(
                    { calls += "release" },
                    { calls += "owner" },
                    { calls += "waiter" },
                ),
            ) {
                throw primary
            }
        }.exceptionOrNull()
        val interrupted = Thread.interrupted()

        assertSame(primary, thrown)
        assertEquals(listOf("release", "owner", "waiter"), calls)
        assertTrue(interrupted)
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun `cleanup preserves first cleanup failure`() {
        val calls = mutableListOf<String>()
        val releaseFailure = IllegalStateException("release failed")
        val ownerJoinFailure = IllegalArgumentException("owner join failed")

        val thrown = runCatching {
            runWithNonMaskingCleanup(
                cleanupActions = listOf(
                    {
                        calls += "release"
                        throw releaseFailure
                    },
                    {
                        calls += "owner"
                        throw ownerJoinFailure
                    },
                    { calls += "waiter" },
                ),
            ) {}
        }.exceptionOrNull()

        assertSame(releaseFailure, thrown)
        assertEquals(listOf(ownerJoinFailure), releaseFailure.suppressed.toList())
        assertEquals(listOf("release", "owner", "waiter"), calls)
    }

    @Test
    fun `interrupted waiter blocks until completion and restores interruption`() {
        val barrier = RetirementBarrier()
        val ownerEntered = CountDownLatch(1)
        val releaseOwner = CountDownLatch(1)
        val waiterAttemptingRetirement = CountDownLatch(1)
        val waiterReturned = CountDownLatch(1)
        val waiterInterrupted = AtomicBoolean()
        val cleanupCalls = AtomicInteger()
        var waiter: Thread? = null
        val owner = thread(name = "retirement-owner") {
            barrier.retire {
                cleanupCalls.incrementAndGet()
                ownerEntered.countDown()
                releaseOwner.await()
            }
        }
        runWithNonMaskingCleanup(
            cleanupActions = listOf(
                { releaseOwner.countDown() },
                { owner.join(5_000) },
                { waiter?.join(5_000) },
            ),
        ) {
            assertTrue(ownerEntered.await(5, TimeUnit.SECONDS))
            val startedWaiter = thread(name = "retirement-waiter") {
                waiterAttemptingRetirement.countDown()
                barrier.retire { cleanupCalls.incrementAndGet() }
                waiterInterrupted.set(Thread.currentThread().isInterrupted)
                waiterReturned.countDown()
            }
            waiter = startedWaiter

            assertTrue(waiterAttemptingRetirement.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (
                startedWaiter.state != Thread.State.WAITING &&
                startedWaiter.isAlive &&
                System.nanoTime() < deadline
            ) {
                Thread.yield()
            }
            assertEquals(Thread.State.WAITING, startedWaiter.state)
            startedWaiter.interrupt()
            assertFalse(waiterReturned.await(100, TimeUnit.MILLISECONDS))
        }

        val completedWaiter = requireNotNull(waiter)
        assertFalse(owner.isAlive)
        assertFalse(completedWaiter.isAlive)
        assertEquals(1, cleanupCalls.get())
        assertEquals(0L, waiterReturned.count)
        assertTrue(waiterInterrupted.get())
    }

    @Test
    fun `waiting caller replays owner failure`() {
        val barrier = RetirementBarrier()
        val failure = IllegalStateException("retirement failed")
        val ownerEntered = CountDownLatch(1)
        val releaseOwner = CountDownLatch(1)
        val waiterAttemptingRetirement = CountDownLatch(1)
        val waiterReturned = CountDownLatch(1)
        val waiterCleanupCalls = AtomicInteger()
        val ownerFailure = AtomicReference<Throwable?>()
        val waiterFailure = AtomicReference<Throwable?>()
        var waiter: Thread? = null
        val owner = thread(name = "failing-retirement-owner") {
            ownerFailure.set(
                runCatching {
                    barrier.retire {
                        ownerEntered.countDown()
                        releaseOwner.await()
                        throw failure
                    }
                }.exceptionOrNull(),
            )
        }
        runWithNonMaskingCleanup(
            cleanupActions = listOf(
                { releaseOwner.countDown() },
                { owner.join(5_000) },
                { waiter?.join(5_000) },
            ),
        ) {
            assertTrue(ownerEntered.await(5, TimeUnit.SECONDS))
            val startedWaiter = thread(name = "failing-retirement-waiter") {
                waiterAttemptingRetirement.countDown()
                waiterFailure.set(
                    runCatching {
                        barrier.retire { waiterCleanupCalls.incrementAndGet() }
                    }.exceptionOrNull(),
                )
                waiterReturned.countDown()
            }
            waiter = startedWaiter

            assertTrue(waiterAttemptingRetirement.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (
                startedWaiter.state != Thread.State.WAITING &&
                startedWaiter.isAlive &&
                System.nanoTime() < deadline
            ) {
                Thread.yield()
            }
            assertEquals(Thread.State.WAITING, startedWaiter.state)
            assertFalse(waiterReturned.await(100, TimeUnit.MILLISECONDS))
        }

        val completedWaiter = requireNotNull(waiter)
        assertFalse(owner.isAlive)
        assertFalse(completedWaiter.isAlive)
        assertSame(failure, ownerFailure.get())
        assertSame(failure, waiterFailure.get())
        assertEquals(0, waiterCleanupCalls.get())
    }

    @Test
    fun `failure is replayed to later callers`() {
        val barrier = RetirementBarrier()
        val failure = IllegalStateException("retirement failed")

        val first = runCatching { barrier.retire { throw failure } }.exceptionOrNull()
        val second = runCatching { barrier.retire { error("must not execute") } }.exceptionOrNull()

        assertSame(failure, first)
        assertSame(failure, second)
    }
}

private fun runWithNonMaskingCleanup(
    cleanupActions: List<() -> Unit>,
    block: () -> Unit,
) {
    val primaryFailure = runCatching(block).exceptionOrNull()
    val cleanupFailures = buildList {
        cleanupActions.forEach { action ->
            try {
                action()
            } catch (failure: Throwable) {
                add(failure)
            }
        }
    }
    if (primaryFailure is InterruptedException || cleanupFailures.any { it is InterruptedException }) {
        Thread.currentThread().interrupt()
    }
    val terminalFailure = primaryFailure ?: cleanupFailures.firstOrNull() ?: return
    val suppressedFailures = if (primaryFailure == null) cleanupFailures.drop(1) else cleanupFailures
    suppressedFailures
        .filter { it !== terminalFailure }
        .forEach(terminalFailure::addSuppressed)
    throw terminalFailure
}
