package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskSpec
import me.rerere.rikkahub.data.ai.subagent.SubagentCompletion
import me.rerere.rikkahub.data.db.dao.AgentEventDAO
import me.rerere.rikkahub.data.db.entity.AgentEventEntity
import me.rerere.rikkahub.data.db.entity.AgentEventStatus
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import me.rerere.rikkahub.data.repository.fakes.FakeAgentEventDAO
import me.rerere.rikkahub.data.repository.fakes.FakeTaskRunDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Slice-2 atomicity for the background-subagent feature: a DETACHED background run's terminal
 * transition must, in ONE transaction, write the terminal state AND enqueue exactly one durable
 * SubagentCompletion (AT_MOST_ONCE, dedupeKey=taskId); a foreground run enqueues nothing; a failed
 * enqueue rolls the terminal write back. Mirrors RoomShellRunStoreAtomicityTest, at the
 * TaskRunRepository seam against in-memory fakes (CI runs no instrumented tests).
 */
class RoomTaskRunStoreBackgroundAtomicityTest {

    private class FakeTransactions(
        private val runDao: FakeTaskRunDAO,
        private val eventDao: FakeAgentEventDAO,
    ) : BoardTransactionRunner {
        private val mutex = Mutex()
        override suspend fun <T> inTransaction(block: suspend () -> T): T = mutex.withLock {
            val runSnap = runDao.snapshot()
            val eventSnap = eventDao.snapshot()
            try {
                block()
            } catch (t: Throwable) {
                runDao.restore(runSnap)
                eventDao.restore(eventSnap)
                throw t
            }
        }
    }

    private class ThrowingAgentEventDAO(
        private val delegate: FakeAgentEventDAO,
        private val onInsertIgnore: suspend () -> Unit,
    ) : AgentEventDAO {
        override suspend fun insertIgnore(event: AgentEventEntity) {
            onInsertIgnore(); delegate.insertIgnore(event)
        }
        override suspend fun getById(id: String) = delegate.getById(id)
        override suspend fun oldestPending(conversationId: String) = delegate.oldestPending(conversationId)
        override suspend fun listPending(conversationId: String) = delegate.listPending(conversationId)
        override suspend fun conversationsWithPending() = delegate.conversationsWithPending()
        override suspend fun markConsumed(id: String, syntheticNodeId: String, syntheticMessageId: String, consumedAt: Long) =
            delegate.markConsumed(id, syntheticNodeId, syntheticMessageId, consumedAt)
        override suspend fun markCancelled(id: String, cancelledAt: Long) = delegate.markCancelled(id, cancelledAt)
        override suspend fun markFailed(id: String, cancelledAt: Long) = delegate.markFailed(id, cancelledAt)
        override suspend fun nextEnqueueSeq(conversationId: String) = delegate.nextEnqueueSeq(conversationId)
        override suspend fun deleteByConversationId(conversationId: String) = delegate.deleteByConversationId(conversationId)
    }

    private var clock = 1L

    private fun store(
        runDao: FakeTaskRunDAO,
        eventDao: FakeAgentEventDAO,
        agentEventDao: AgentEventDAO = eventDao,
    ) = TaskRunRepository(
        dao = runDao,
        transactions = FakeTransactions(runDao, eventDao),
        agentEventDao = agentEventDao,
        now = { clock++ },
    )

    private fun spec(taskId: Uuid, conv: Uuid, background: Boolean) = TaskSpec(
        taskId = taskId,
        parentConversationId = conv,
        parentToolCallId = "call-1",
        agentTypeId = "general",
        prompt = "do it",
        isBackground = background,
    )

    /** Drive Created -> Queued -> Starting, leaving the run one event short of a terminal. */
    private suspend fun TaskRunRepository.driveToStarting(taskId: Uuid) {
        applyEvent(taskId, TaskEvent.Enqueued)
        applyEvent(taskId, TaskEvent.SlotClaimed)
    }

    @Test
    fun `background terminal enqueues exactly one completion`(): Unit = runBlocking {
        val runDao = FakeTaskRunDAO(); val eventDao = FakeAgentEventDAO()
        val st = store(runDao, eventDao)
        val conv = Uuid.random(); val task = Uuid.random()
        st.create(spec(task, conv, background = true))
        st.driveToStarting(task)
        st.applyEvent(task, TaskEvent.FinalResult("all done"))

        val pending = eventDao.listPending(conv.toString())
        assertEquals("one durable completion", 1, pending.size)
        val e = pending.single()
        assertEquals(task.toString(), e.dedupeKey)
        assertEquals(SubagentCompletion.KIND, e.kind)
        assertEquals(AgentEventStatus.PENDING.name, e.status)
        val p = Json.parseToJsonElement(e.payloadJson).jsonObject
        assertEquals("SUCCEEDED", p["status"]!!.jsonPrimitive.content)
        assertEquals("all done", p["summary"]!!.jsonPrimitive.content)
        assertEquals(TaskRunStateTag.SUCCEEDED.name, runDao.getById(task.toString())!!.latestState)
    }

    @Test
    fun `foreground terminal enqueues nothing`(): Unit = runBlocking {
        val runDao = FakeTaskRunDAO(); val eventDao = FakeAgentEventDAO()
        val st = store(runDao, eventDao)
        val conv = Uuid.random(); val task = Uuid.random()
        st.create(spec(task, conv, background = false))
        st.driveToStarting(task)
        st.applyEvent(task, TaskEvent.FinalResult("done"))
        assertEquals(0, eventDao.listPending(conv.toString()).size)
        assertEquals(TaskRunStateTag.SUCCEEDED.name, runDao.getById(task.toString())!!.latestState)
    }

    @Test
    fun `re-applying the absorbing terminal does not double-enqueue`(): Unit = runBlocking {
        val runDao = FakeTaskRunDAO(); val eventDao = FakeAgentEventDAO()
        val st = store(runDao, eventDao)
        val conv = Uuid.random(); val task = Uuid.random()
        st.create(spec(task, conv, background = true))
        st.driveToStarting(task)
        st.applyEvent(task, TaskEvent.FinalResult("done"))
        // Redeliver the terminal: absorbing -> no-op -> no second enqueue.
        st.applyEvent(task, TaskEvent.FinalResult("done again"))
        st.applyEvent(task, TaskEvent.ExecutionFailed("late"))
        assertEquals("still exactly one completion", 1, eventDao.listPending(conv.toString()).size)
    }

    @Test
    fun `execution failed terminal enqueues a FAILED completion`(): Unit = runBlocking {
        val runDao = FakeTaskRunDAO(); val eventDao = FakeAgentEventDAO()
        val st = store(runDao, eventDao)
        val conv = Uuid.random(); val task = Uuid.random()
        st.create(spec(task, conv, background = true))
        st.driveToStarting(task)
        st.applyEvent(task, TaskEvent.ExecutionFailed("boom"))
        val e = eventDao.listPending(conv.toString()).single()
        val p = Json.parseToJsonElement(e.payloadJson).jsonObject
        assertEquals("FAILED", p["status"]!!.jsonPrimitive.content)
        assertEquals("boom", p["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `failed completion insert rolls back the terminal write`(): Unit = runBlocking {
        val runDao = FakeTaskRunDAO(); val eventDao = FakeAgentEventDAO()
        val throwing = ThrowingAgentEventDAO(eventDao) { error("injected enqueue failure") }
        val st = store(runDao, eventDao, agentEventDao = throwing)
        val conv = Uuid.random(); val task = Uuid.random()
        st.create(spec(task, conv, background = true))
        st.driveToStarting(task)

        var thrown: Throwable? = null
        try {
            st.applyEvent(task, TaskEvent.FinalResult("done"))
        } catch (t: Throwable) {
            thrown = t
        }
        assertNotNull("transaction failure propagates", thrown)
        // The terminal upsert was rolled back with the failed enqueue: the row stays pre-terminal.
        assertEquals(TaskRunStateTag.STARTING.name, runDao.getById(task.toString())!!.latestState)
        assertEquals(0, eventDao.listPending(conv.toString()).size)
    }

    @Test
    fun `applyEvent on a missing run is null and enqueues nothing`(): Unit = runBlocking {
        val runDao = FakeTaskRunDAO(); val eventDao = FakeAgentEventDAO()
        val st = store(runDao, eventDao)
        assertNull(st.applyEvent(Uuid.random(), TaskEvent.FinalResult("x")))
        assertEquals(0, eventDao.listPending(Uuid.random().toString()).size)
    }
}
