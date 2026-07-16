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
    fun `interrupted waiter blocks until completion and restores interruption`() {
        val barrier = RetirementBarrier()
        val ownerEntered = CountDownLatch(1)
        val releaseOwner = CountDownLatch(1)
        val waiterReturned = CountDownLatch(1)
        val waiterInterrupted = AtomicBoolean()
        val cleanupCalls = AtomicInteger()
        val owner = thread(name = "retirement-owner") {
            barrier.retire {
                cleanupCalls.incrementAndGet()
                ownerEntered.countDown()
                releaseOwner.await()
            }
        }
        assertTrue(ownerEntered.await(5, TimeUnit.SECONDS))
        val waiter = thread(name = "retirement-waiter") {
            Thread.currentThread().interrupt()
            barrier.retire { cleanupCalls.incrementAndGet() }
            waiterInterrupted.set(Thread.currentThread().isInterrupted)
            waiterReturned.countDown()
        }

        try {
            assertFalse(waiterReturned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseOwner.countDown()
            owner.join(5_000)
            waiter.join(5_000)
        }

        assertFalse(owner.isAlive)
        assertFalse(waiter.isAlive)
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
        assertTrue(ownerEntered.await(5, TimeUnit.SECONDS))
        val waiter = thread(name = "failing-retirement-waiter") {
            waiterAttemptingRetirement.countDown()
            waiterFailure.set(
                runCatching {
                    barrier.retire { waiterCleanupCalls.incrementAndGet() }
                }.exceptionOrNull(),
            )
            waiterReturned.countDown()
        }

        assertTrue(waiterAttemptingRetirement.await(5, TimeUnit.SECONDS))
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (waiter.state != Thread.State.WAITING && waiter.isAlive && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.WAITING, waiter.state)
            assertFalse(waiterReturned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseOwner.countDown()
            owner.join(5_000)
            waiter.join(5_000)
        }

        assertFalse(owner.isAlive)
        assertFalse(waiter.isAlive)
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
