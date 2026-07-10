package me.rerere.ai.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCallbackFlowTest {
    @Test
    fun `fast callback delivery remains ordered and lossless for a slow consumer`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val itemCount = PROVIDER_CALLBACK_BUFFER_CAPACITY * 4

        try {
            val values = losslessProviderCallbackFlow<Int> {
                val producerScope = this
                executor.execute {
                    repeat(itemCount) { value ->
                        check(producerScope.sendFromProviderCallback(value).isSuccess)
                    }
                    producerScope.close()
                }
                awaitProviderClose()
            }.toList(mutableListOf())

            assertEquals((0 until itemCount).toList(), values)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `finite callback buffer stops producer progress while consumer is saturated`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val consumerGate = CompletableDeferred<Unit>()
        val attemptedBlockedSend = CountDownLatch(1)
        val producerFinished = CountDownLatch(1)
        val successfulSends = AtomicInteger()

        try {
            val flow = losslessProviderCallbackFlow<Int> {
                val producerScope = this
                executor.execute {
                    try {
                        repeat(PROVIDER_CALLBACK_BUFFER_CAPACITY * 4) { value ->
                            if (value == PROVIDER_CALLBACK_BUFFER_CAPACITY + 1) {
                                attemptedBlockedSend.countDown()
                            }
                            if (producerScope.sendFromProviderCallback(value).isSuccess) {
                                successfulSends.incrementAndGet()
                            }
                        }
                    } finally {
                        producerFinished.countDown()
                    }
                }
                awaitProviderClose()
            }

            val collector = launch(Dispatchers.Default) {
                flow.collect { consumerGate.await() }
            }

            assertTrue(attemptedBlockedSend.await(5, TimeUnit.SECONDS))
            assertFalse(producerFinished.await(100, TimeUnit.MILLISECONDS))
            assertTrue(successfulSends.get() < PROVIDER_CALLBACK_BUFFER_CAPACITY * 4)

            collector.cancelAndJoin()
            assertTrue(producerFinished.await(5, TimeUnit.SECONDS))
        } finally {
            consumerGate.complete(Unit)
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancelling saturated flow unblocks callback sender with failure`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val consumerGate = CompletableDeferred<Unit>()
        val producerBlocked = CountDownLatch(1)
        val producerFinished = CountDownLatch(1)
        val terminalFailure = AtomicBoolean()
        val senderInterruptedAfterSend = AtomicBoolean(true)

        try {
            val flow = losslessProviderCallbackFlow<Int> {
                val producerScope = this
                executor.execute {
                    try {
                        var value = 0
                        while (true) {
                            if (value == PROVIDER_CALLBACK_BUFFER_CAPACITY + 1) {
                                producerBlocked.countDown()
                            }
                            val result = producerScope.sendFromProviderCallback(value)
                            if (result.isFailure) {
                                terminalFailure.set(true)
                                break
                            }
                            value += 1
                        }
                    } finally {
                        senderInterruptedAfterSend.set(Thread.currentThread().isInterrupted)
                        producerFinished.countDown()
                    }
                }
                awaitProviderClose()
            }

            val collector = launch(Dispatchers.Default) {
                flow.collect { consumerGate.await() }
            }

            assertTrue(producerBlocked.await(5, TimeUnit.SECONDS))
            collector.cancelAndJoin()

            assertTrue(producerFinished.await(5, TimeUnit.SECONDS))
            assertTrue(terminalFailure.get())
            assertFalse(senderInterruptedAfterSend.get())
        } finally {
            consumerGate.complete(Unit)
            executor.shutdownNow()
        }
    }

    @Test
    fun `cleanup racing after send return does not poison callback thread`() {
        val senderRegistry = ProviderCallbackSenderRegistry()
        val sendReturned = CountDownLatch(1)
        val allowDeregister = AtomicBoolean()
        val senderFinished = CountDownLatch(1)
        val senderInterruptedAfterDeregister = AtomicBoolean(true)
        val executor = Executors.newSingleThreadExecutor()

        try {
            executor.execute {
                senderRegistry.withRegisteredSender {
                    sendReturned.countDown()
                    while (!allowDeregister.get()) {
                        Thread.onSpinWait()
                    }
                }
                senderInterruptedAfterDeregister.set(Thread.currentThread().isInterrupted)
                senderFinished.countDown()
            }

            assertTrue(sendReturned.await(5, TimeUnit.SECONDS))
            senderRegistry.interruptRegisteredSenders()
            allowDeregister.set(true)

            assertTrue(senderFinished.await(5, TimeUnit.SECONDS))
            assertFalse(senderInterruptedAfterDeregister.get())
        } finally {
            allowDeregister.set(true)
            executor.shutdownNow()
        }
    }
}
