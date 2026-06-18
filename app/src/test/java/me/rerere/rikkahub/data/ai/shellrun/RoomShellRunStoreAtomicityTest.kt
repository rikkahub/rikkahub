package me.rerere.rikkahub.data.ai.shellrun

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.dao.AgentEventDAO
import me.rerere.rikkahub.data.db.entity.AgentEventEntity
import me.rerere.rikkahub.data.db.entity.AgentEventStatus
import me.rerere.rikkahub.data.db.entity.ShellRunStatus
import me.rerere.rikkahub.data.repository.BoardTransactionRunner
import me.rerere.rikkahub.data.repository.fakes.FakeAgentEventDAO
import me.rerere.rikkahub.data.repository.fakes.FakeShellRunDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Shell-run store atomicity tests for issue #291 (C17): terminal write and durable completion event
 * enqueue for detached runs must be in one transaction, with exactly-once completion semantics.
 */
class RoomShellRunStoreAtomicityTest {

    private class FakeTransactions(
        private val shellRunDAO: FakeShellRunDAO,
        private val agentEventDAO: FakeAgentEventDAO,
    ) : BoardTransactionRunner {
        private val mutex = Mutex()

        override suspend fun <T> inTransaction(block: suspend () -> T): T = mutex.withLock {
            val shellSnapshot = shellRunDAO.snapshot()
            val eventSnapshot = agentEventDAO.snapshot()
            try {
                block()
            } catch (t: Throwable) {
                shellRunDAO.restore(shellSnapshot)
                agentEventDAO.restore(eventSnapshot)
                throw t
            }
        }
    }

    private class ThrowingAgentEventDAO(
        private val delegate: FakeAgentEventDAO,
        private val onInsertIgnore: suspend () -> Unit,
    ) : AgentEventDAO {
        override suspend fun insertIgnore(event: AgentEventEntity) {
            onInsertIgnore()
            delegate.insertIgnore(event)
        }

        override suspend fun getById(id: String) = delegate.getById(id)
        override suspend fun oldestPending(conversationId: String) = delegate.oldestPending(conversationId)
        override suspend fun listPending(conversationId: String) = delegate.listPending(conversationId)
        override suspend fun conversationsWithPending() = delegate.conversationsWithPending()
        override suspend fun markConsumed(
            id: String,
            syntheticNodeId: String,
            syntheticMessageId: String,
            consumedAt: Long,
        ) = delegate.markConsumed(id, syntheticNodeId, syntheticMessageId, consumedAt)

        override suspend fun markCancelled(
            id: String,
            cancelledAt: Long,
        ) = delegate.markCancelled(id, cancelledAt)

        override suspend fun markFailed(
            id: String,
            cancelledAt: Long,
        ) = delegate.markFailed(id, cancelledAt)

        override suspend fun nextEnqueueSeq(conversationId: String): Long = delegate.nextEnqueueSeq(conversationId)

        override suspend fun deleteByConversationId(conversationId: String): Int =
            delegate.deleteByConversationId(conversationId)
    }

    private var clock = 1L

    @Test
    fun `terminal detached run writes one terminal row and one pending completion event`(): Unit = runBlocking {
        val dao = FakeShellRunDAO()
        val eventDao = FakeAgentEventDAO()
        val st = RoomShellRunStore(
            dao = dao,
            agentEventDao = eventDao,
            transactions = FakeTransactions(dao, eventDao),
            now = { clock++ },
        )

        val conversationId = Uuid.random()
        val taskId = Uuid.random()
        st.create(taskId, conversationId, "ws", "cmd", "", "/tmp/out")
        st.detach(taskId, "pid")

        val completion = ShellCompletion.of(
            conversationId = conversationId,
            taskId = taskId,
            status = ShellRunStatus.SUCCEEDED,
            exitCode = 0,
            outputRef = "/tmp/out",
            tail = "",
            byteCount = 321,
        )
        val terminal = st.recordTerminal(
            taskId = taskId,
            status = ShellRunStatus.SUCCEEDED,
            exitCode = 0,
            byteCount = 321,
            killReason = null,
            buildCompletion = { completion },
        )

        val row = dao.getById(taskId.toString())
        assertNotNull(row)
        assertEquals(
            "terminal row is persisted",
            ShellRunStatus.SUCCEEDED.name,
            row!!.status,
        )
        assertEquals("terminalization won", TerminalOutcome.Won, terminal.outcome)
        assertTrue("run was detached when terminal wrote", terminal.wasDetached)
        assertEquals(
            "store hands back exactly the completion it persisted",
            completion.payloadJson,
            terminal.completion?.payloadJson,
        )

        val pending = eventDao.listPending(conversationId.toString())
        assertEquals("one durable completion event", 1, pending.size)
        val event = pending.single()
        assertEquals("dedupe key is taskId", taskId.toString(), event.dedupeKey)
        assertEquals("completion kind is workspace_shell.completed", ShellCompletion.KIND, event.kind)
        assertEquals("event is pending", AgentEventStatus.PENDING.name, event.status)
        assertEquals("payload comes from ShellCompletion", completion.payloadJson, event.payloadJson)
    }

    @Test
    fun `failed terminal write rolls back both terminal and completion event`(): Unit = runBlocking {
        val dao = FakeShellRunDAO()
        val eventDao = FakeAgentEventDAO()
        val throwing = ThrowingAgentEventDAO(
            delegate = eventDao,
            onInsertIgnore = {
                throw IllegalStateException("injected event persistence failure")
            },
        )
        val st = RoomShellRunStore(
            dao = dao,
            agentEventDao = throwing,
            transactions = FakeTransactions(dao, eventDao),
            now = { clock++ },
        )

        val conversationId = Uuid.random()
        val taskId = Uuid.random()
        st.create(taskId, conversationId, "ws", "cmd", "", "/tmp/out")
        st.detach(taskId, "pid")

        val completion = ShellCompletion.of(
            conversationId = conversationId,
            taskId = taskId,
            status = ShellRunStatus.SUCCEEDED,
            exitCode = 0,
            outputRef = "/tmp/out",
            tail = "",
            byteCount = 1,
        )

        var thrown: Throwable? = null
        try {
            st.recordTerminal(
                taskId = taskId,
                status = ShellRunStatus.SUCCEEDED,
                exitCode = 0,
                byteCount = 1,
                killReason = null,
                buildCompletion = { completion },
            )
        } catch (t: Throwable) {
            thrown = t
        }
        assertNotNull("transaction failure propagates", thrown)

        val row = dao.getById(taskId.toString())
        assertNotNull(row)
        assertEquals(
            "terminal remains detached because rollback restored pre-commit state",
            ShellRunStatus.DETACHED.name,
            row!!.status,
        )
        assertEquals(
            "no durable completion event after rollback",
            0,
            eventDao.listPending(conversationId.toString()).size,
        )
    }

    @Test
    fun `detached terminal attempts are idempotent at the database`(): Unit = runBlocking {
        val dao = FakeShellRunDAO()
        val eventDao = FakeAgentEventDAO()
        val st = RoomShellRunStore(
            dao = dao,
            agentEventDao = eventDao,
            transactions = FakeTransactions(dao, eventDao),
            now = { clock++ },
        )

        val conversationId = Uuid.random()
        val taskId = Uuid.random()
        st.create(taskId, conversationId, "ws", "cmd", "", "/tmp/out")
        st.detach(taskId, "pid")

        val completion = ShellCompletion.of(
            conversationId = conversationId,
            taskId = taskId,
            status = ShellRunStatus.SUCCEEDED,
            exitCode = 0,
            outputRef = "/tmp/out",
            tail = "",
            byteCount = 10,
        )

        val outcomes = awaitAll(
            async {
                st.recordTerminal(
                    taskId = taskId,
                    status = ShellRunStatus.SUCCEEDED,
                    exitCode = 0,
                    byteCount = 10,
                    killReason = null,
                    buildCompletion = { completion },
                )
            },
            async {
                st.recordTerminal(
                    taskId = taskId,
                    status = ShellRunStatus.SUCCEEDED,
                    exitCode = 0,
                    byteCount = 10,
                    killReason = null,
                    buildCompletion = { completion },
                )
            },
        )

        assertEquals(1, outcomes.count { it.outcome == TerminalOutcome.Won })
        assertEquals(1, outcomes.count { it.outcome == TerminalOutcome.Lost })
        assertEquals(
            "exactly one completion event after concurrent terminal attempts",
            1,
            eventDao.listPending(conversationId.toString()).size,
        )
        assertTrue(
            "terminal row is visible as terminal",
            ShellRunStatus.fromPersistedOrNull(dao.getById(taskId.toString())!!.status)!!.isTerminal,
        )
    }

    @Test
    fun `foreground completion remains in-process-only and does not enqueue event`(): Unit = runBlocking {
        val dao = FakeShellRunDAO()
        val eventDao = FakeAgentEventDAO()
        val st = RoomShellRunStore(
            dao = dao,
            agentEventDao = eventDao,
            transactions = FakeTransactions(dao, eventDao),
            now = { clock++ },
        )

        val conversationId = Uuid.random()
        val taskId = Uuid.random()
        st.create(taskId, conversationId, "ws", "cmd", "", "/tmp/out")

        // The inline path (no detach) must NOT build the completion at all: building it reads the run's
        // output tail off disk and can throw, and an inline run must terminalise regardless. A supplier
        // that throws if invoked locks the regression — the test passes only because it is never called.
        val terminal = st.recordTerminal(
            taskId = taskId,
            status = ShellRunStatus.SUCCEEDED,
            exitCode = 0,
            byteCount = 5,
            killReason = null,
            buildCompletion = { error("inline path must not build completion (would read the output tail)") },
        )

        assertEquals(TerminalOutcome.Won, terminal.outcome)
        assertEquals(false, terminal.wasDetached)
        assertEquals("inline terminal delivers no completion", null, terminal.completion)
        assertEquals(
            "inline terminal keeps no event row",
            0,
            eventDao.listPending(conversationId.toString()).size,
        )
        assertEquals(
            ShellRunStatus.SUCCEEDED.name,
            dao.getById(taskId.toString())!!.status,
        )
    }

    @Test
    fun `tool anchor attach is idempotent and rejects conflicting anchors`(): Unit = runBlocking {
        val dao = FakeShellRunDAO()
        val eventDao = FakeAgentEventDAO()
        val st = RoomShellRunStore(
            dao = dao,
            agentEventDao = eventDao,
            transactions = FakeTransactions(dao, eventDao),
            now = { clock++ },
        )
        val conversationId = Uuid.random()
        val taskId = Uuid.random()
        val anchor = ShellRunToolAnchor(
            toolCallId = "call-shell",
            toolNodeId = Uuid.random(),
            toolMessageId = Uuid.random(),
        )
        st.create(taskId, conversationId, "ws", "cmd", "", "/tmp/out")
        st.detach(taskId, "pid")

        assertTrue(st.attachToolAnchor(taskId, anchor))
        assertTrue(st.attachToolAnchor(taskId, anchor))
        assertEquals(anchor, st.getToolAnchor(taskId))

        val conflicting = anchor.copy(toolCallId = "other-call")
        assertEquals(false, st.attachToolAnchor(taskId, conflicting))
        assertEquals("original anchor remains the single anchor", anchor, st.getToolAnchor(taskId))
    }
}
