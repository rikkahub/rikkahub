package me.rerere.rikkahub.web.a2a

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class A2aConcurrentTransitionTest {

    @Test
    fun `concurrent terminal transitions emit exactly one terminal event`() = runBlocking {
        val registry = A2aTaskRegistry()
        val entry = (registry.admit(Uuid.random(), Uuid.random(), "m-1") as A2aAdmission.Accepted).entry
        val collector = registry.startCollector(entry.taskId) { Job() }

        val initialTaskId = registry.activeByContext[entry.contextId]
        val terminalTransitionCount = AtomicInteger(0)
        val collectorCancelCount = AtomicInteger(0)
        val terminalEventStates = Collections.synchronizedList(mutableListOf<A2aTaskState>())

        val start = CountDownLatch(1)
        val ready = CountDownLatch(3)

        suspend fun transitionAndEmit(state: A2aTaskState, terminal: Boolean) {
            ready.countDown()
            start.await()
            val updated = registry.transition(entry.taskId, state, terminal = terminal) ?: return
            if (terminal) {
                terminalTransitionCount.incrementAndGet()
                val task = updated.toA2aTask(conversation = null)
                registry.emit(
                    task.id,
                    A2aStreamEvent.TaskStatusUpdateEvent(
                        taskId = task.id,
                        contextId = task.contextId,
                        status = task.status,
                        final = true,
                    ),
                )
                terminalEventStates += task.status.state
                collectorCancelCount.incrementAndGet()
                registry.cancelCollector(updated.taskId)
            }
        }

        val tasks = listOf(
            async(Dispatchers.Default) { transitionAndEmit(A2aTaskState.CANCELED, terminal = true) },
            async(Dispatchers.Default) { transitionAndEmit(A2aTaskState.COMPLETED, terminal = true) },
            async(Dispatchers.Default) { transitionAndEmit(A2aTaskState.WORKING, terminal = false) },
        )
        ready.await()
        start.countDown()
        tasks.awaitAll()

        assertEquals(1, terminalTransitionCount.get())
        assertEquals(1, terminalEventStates.size)
        assertEquals(1, collectorCancelCount.get())
        assertEquals(terminalEventStates.single(), registry.get(entry.taskId)!!.state)
        assertEquals(initialTaskId, entry.taskId)
        assertNull(registry.activeByContext[entry.contextId])
        assertTrue(collector.isCancelled)
    }

    @Test
    fun `concurrent working transition cannot override a terminal transition`() = runBlocking {
        repeat(250) {
            val registry = A2aTaskRegistry()
            val entry = (registry.admit(Uuid.random(), Uuid.random(), "m-$it") as A2aAdmission.Accepted).entry

            val start = CountDownLatch(1)
            val ready = CountDownLatch(2)

            val completion = async(Dispatchers.Default) {
                ready.countDown()
                start.await()
                registry.transition(entry.taskId, A2aTaskState.CANCELED, terminal = true)
            }
            val working = async(Dispatchers.Default) {
                ready.countDown()
                start.await()
                registry.transition(entry.taskId, A2aTaskState.WORKING, terminal = false)
            }

            ready.await()
            start.countDown()
            completion.await()
            working.await()

            assertEquals(A2aTaskState.CANCELED, registry.get(entry.taskId)!!.state)
        }
    }

}
