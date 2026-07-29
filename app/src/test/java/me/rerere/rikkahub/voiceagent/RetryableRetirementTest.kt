package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryableRetirementTest {
    @Test
    fun `synchronous attempt result rejects double publication`() {
        val attempt = SynchronousAttemptResult()

        attempt.publish(Result.success(Unit))
        val duplicate = runCatching {
            attempt.publish(Result.failure(IllegalStateException("duplicate")))
        }.exceptionOrNull()

        assertTrue(duplicate is IllegalStateException)
        assertTrue(attempt.awaitResult().isSuccess)
    }

    @Test
    fun `interrupted joiner waits for captured failure and restores interrupt status`() {
        val firstFailure = IllegalStateException("first retirement failed")
        val blockEntered = CountDownLatch(1)
        val releaseBlock = CountDownLatch(1)
        val joinerStarted = CountDownLatch(1)
        val joinerReturned = CountDownLatch(1)
        val joinerThread = AtomicReference<Thread>()
        val joinerInterrupted = AtomicBoolean()
        val blockCalls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val retirement = RetryableRetirement()
        try {
            val owner = executor.submit<Throwable?> {
                runCatching {
                    retirement.retire {
                        blockCalls.incrementAndGet()
                        blockEntered.countDown()
                        check(releaseBlock.await(1, TimeUnit.SECONDS)) {
                            "retirement block was not released"
                        }
                        throw firstFailure
                    }
                }.exceptionOrNull()
            }
            check(blockEntered.await(1, TimeUnit.SECONDS)) {
                "retirement block did not start"
            }
            val joiner = executor.submit<Throwable?> {
                joinerThread.set(Thread.currentThread())
                joinerStarted.countDown()
                val failure = runCatching {
                    retirement.retire {
                        blockCalls.incrementAndGet()
                    }
                }.exceptionOrNull()
                joinerInterrupted.set(Thread.currentThread().isInterrupted)
                joinerReturned.countDown()
                failure
            }
            check(joinerStarted.await(1, TimeUnit.SECONDS)) {
                "retirement joiner did not start"
            }
            awaitWaiting(joinerThread.get())

            joinerThread.get().interrupt()
            assertFalse(joinerReturned.await(100, TimeUnit.MILLISECONDS))
            releaseBlock.countDown()

            assertSame(firstFailure, owner.get(1, TimeUnit.SECONDS))
            assertSame(firstFailure, joiner.get(1, TimeUnit.SECONDS))
            assertEquals(1, blockCalls.get())
            assertTrue(joinerInterrupted.get())
        } finally {
            releaseBlock.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent callers join one failed retirement attempt`() {
        val firstFailure = IllegalStateException("first retirement failed")
        val blockEntered = CountDownLatch(1)
        val releaseBlock = CountDownLatch(1)
        val joinerStarted = CountDownLatch(1)
        val joinerThread = AtomicReference<Thread>()
        val blockCalls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val retirement = RetryableRetirement()
        try {
            val owner = executor.submit<Throwable?> {
                runCatching {
                    retirement.retire {
                        blockCalls.incrementAndGet()
                        blockEntered.countDown()
                        check(releaseBlock.await(1, TimeUnit.SECONDS)) {
                            "retirement block was not released"
                        }
                        throw firstFailure
                    }
                }.exceptionOrNull()
            }
            check(blockEntered.await(1, TimeUnit.SECONDS)) {
                "retirement block did not start"
            }
            val joiner = executor.submit<Throwable?> {
                joinerThread.set(Thread.currentThread())
                joinerStarted.countDown()
                runCatching {
                    retirement.retire {
                        blockCalls.incrementAndGet()
                    }
                }.exceptionOrNull()
            }
            check(joinerStarted.await(1, TimeUnit.SECONDS)) {
                "retirement joiner did not start"
            }
            awaitWaiting(joinerThread.get())

            releaseBlock.countDown()

            assertSame(firstFailure, owner.get(1, TimeUnit.SECONDS))
            assertSame(firstFailure, joiner.get(1, TimeUnit.SECONDS))
            assertEquals(1, blockCalls.get())
        } finally {
            releaseBlock.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `later call retries after failure`() {
        val firstFailure = IllegalStateException("first retirement failed")
        val blockCalls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val retirement = RetryableRetirement()
        try {
            val first = executor.submit<Throwable?> {
                runCatching {
                    retirement.retire {
                        if (blockCalls.incrementAndGet() == 1) throw firstFailure
                    }
                }.exceptionOrNull()
            }

            assertSame(firstFailure, first.get(1, TimeUnit.SECONDS))
            executor.submit {
                retirement.retire {
                    blockCalls.incrementAndGet()
                }
            }.get(1, TimeUnit.SECONDS)

            assertEquals(2, blockCalls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `success is permanently replayed`() {
        val blockCalls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val retirement = RetryableRetirement()
        try {
            executor.submit {
                retirement.retire {
                    blockCalls.incrementAndGet()
                }
            }.get(1, TimeUnit.SECONDS)
            executor.submit {
                retirement.retire {
                    blockCalls.incrementAndGet()
                }
            }.get(1, TimeUnit.SECONDS)

            assertEquals(1, blockCalls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `same thread reentry does not deadlock`() {
        val blockCalls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val retirement = RetryableRetirement()
        try {
            executor.submit {
                retirement.retire {
                    blockCalls.incrementAndGet()
                    retirement.retire {
                        blockCalls.incrementAndGet()
                    }
                }
            }.get(1, TimeUnit.SECONDS)

            assertEquals(1, blockCalls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun awaitWaiting(thread: Thread) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (thread.state != Thread.State.WAITING && System.nanoTime() < deadlineNanos) {
            Thread.yield()
        }
        check(thread.state == Thread.State.WAITING) {
            "retirement joiner did not wait for the active attempt"
        }
    }
}
