package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePersistenceQueueTest {

    @Test
    fun `jobs run strictly in enqueue order even when later jobs are faster`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = VoicePersistenceQueue(CoroutineScope(dispatcher))
        val order = mutableListOf<Int>()
        queue.enqueue { delay(100); order += 1 }
        queue.enqueue { order += 2 }
        queue.enqueue { delay(10); order += 3 }
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun `await returns after all enqueued jobs complete`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = VoicePersistenceQueue(CoroutineScope(dispatcher))
        var done = 0
        repeat(3) { queue.enqueue { delay(50); done += 1 } }
        queue.await()
        assertEquals(3, done)
    }

    @Test
    fun `await returns immediately when nothing is enqueued`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        VoicePersistenceQueue(CoroutineScope(dispatcher)).await()
    }

    @Test
    fun `a failing job does not block later jobs`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = VoicePersistenceQueue(CoroutineScope(dispatcher))
        val order = mutableListOf<Int>()
        queue.enqueue { error("boom") }
        queue.enqueue { order += 2 }
        advanceUntilIdle()
        assertEquals(listOf(2), order)
    }

    @Test
    fun `scope cancellation is not swallowed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val queue = VoicePersistenceQueue(scope)
        val job = queue.enqueue { delay(100) }
        advanceTimeBy(10)
        scope.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        queue.await()
    }
}
