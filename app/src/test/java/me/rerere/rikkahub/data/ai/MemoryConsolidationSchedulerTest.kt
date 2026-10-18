package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class MemoryConsolidationSchedulerTest {
    private val memoryId = "memory"
    private val chat = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
    private val otherChat = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())

    private class Call(val conversationIds: Set<Uuid>, val fullReview: Boolean)

    /** 扮演数据库和模型：[pending] 是还没整理的轮数，整理成功后清掉 */
    private inner class FakeWorker : MemoryConsolidationWorker {
        val pending = mutableMapOf<Uuid, Int>()

        // 不在这里的对话都属于 memoryId
        val memoryIdOf = mutableMapOf<Uuid, String>()

        // 助手关了自动整理的对话
        val manual = mutableSetOf<Uuid>()
        val calls = mutableListOf<Call>()
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null

        // 查完数据库、结果还没返回时停在这里
        var scanGate: CompletableDeferred<Unit>? = null

        // 一次最多整理这么多个对话，模拟长度预算
        var perRun = Int.MAX_VALUE

        // 每个对话一次最多整理这么多轮，模拟每个对话的轮数上限
        var turnsPerRun = Int.MAX_VALUE

        // 进度写不进去：整理完对话还是待整理
        var stuck = false

        private fun item(id: Uuid, turns: Int) =
            PendingMemoryConversation(id, memoryIdOf[id] ?: memoryId, turns, automatic = id !in manual)

        override suspend fun pendingOf(conversation: Conversation) =
            pending[conversation.id]?.let { item(conversation.id, it) }

        override suspend fun memoryIdsWithUpdates() =
            pending.map { (id, turns) -> item(id, turns).memoryId }.distinct()

        override suspend fun findPending(memoryId: String): List<PendingMemoryConversation> {
            val found = pending.map { (id, turns) -> item(id, turns) }.filter { it.memoryId == memoryId }
            scanGate?.await()
            return found
        }

        override suspend fun markConsolidated(conversation: Conversation) {
            pending.remove(conversation.id)
        }

        override suspend fun consolidate(
            memoryId: String,
            conversationIds: Set<Uuid>,
            fullReview: Boolean,
        ): MemoryConsolidationRun? {
            calls += Call(conversationIds, fullReview)
            gate?.await()
            failure?.let { throw it }
            val batch = conversationIds.take(perRun)
            if (batch.isEmpty() && !fullReview) return null
            if (!stuck) {
                batch.forEach { id ->
                    val left = pending.getValue(id) - turnsPerRun
                    if (left > 0) pending[id] = left else pending.remove(id)
                }
            }
            return MemoryConsolidationRun(
                memoryId = memoryId,
                finishedAt = 0,
                fullReview = fullReview,
                changedPaths = listOf("/profile.md"),
                advancedConversations = if (stuck) emptySet() else batch.toSet(),
            )
        }
    }

    private val worker = FakeWorker()
    private var appVisible = true
    private var activeHolds = 0
    private var totalHolds = 0

    private fun TestScope.scheduler() = MemoryConsolidationScheduler(
        scope = backgroundScope,
        worker = worker,
        keepAlive = {
            activeHolds++
            totalHolds++
            AutoCloseable { activeHolds-- }
        },
        isAppVisible = { appVisible },
        idleDelay = 2.minutes,
        maxPendingTurns = 3,
    )

    private fun MemoryConsolidationScheduler.finishTurn(conversation: Conversation, pendingTurns: Int) {
        worker.pending[conversation.id] = pendingTurns
        onTurnFinished(conversation)
    }

    @Test
    fun `turns in quick succession are consolidated once, after the chat goes idle`() = runTest {
        val scheduler = scheduler()

        scheduler.finishTurn(chat, pendingTurns = 1)
        advanceTimeBy(1.minutes)
        scheduler.finishTurn(chat, pendingTurns = 2)
        advanceTimeBy(2.minutes - 1.seconds)
        assertTrue(worker.calls.isEmpty())
        assertEquals(2, scheduler.statusOf(memoryId).first().pendingTurns)

        advanceTimeBy(2.seconds)

        assertEquals(listOf(setOf(chat.id)), worker.calls.map { it.conversationIds })
        assertFalse(worker.calls.single().fullReview)
        assertEquals(0, scheduler.statusOf(memoryId).first().pendingTurns)
    }

    @Test
    fun `reaching the turn limit consolidates without waiting`() = runTest {
        val scheduler = scheduler()

        scheduler.finishTurn(chat, pendingTurns = 3)
        runCurrent()

        assertEquals(1, worker.calls.size)
    }

    @Test
    fun `leaving a conversation consolidates without waiting`() = runTest {
        val scheduler = scheduler()

        scheduler.onConversationLeft(otherChat.id)
        scheduler.finishTurn(chat, pendingTurns = 1)
        runCurrent()
        assertTrue(worker.calls.isEmpty())

        scheduler.onConversationLeft(chat.id)
        runCurrent()

        assertEquals(1, worker.calls.size)
        // 已经整理过，原来的定时器不该再触发一次
        advanceTimeBy(5.minutes)
        assertEquals(1, worker.calls.size)
    }

    @Test
    fun `the process is kept alive for as long as a run lasts`() = runTest {
        val scheduler = scheduler()
        worker.gate = CompletableDeferred()

        scheduler.finishTurn(chat, pendingTurns = 3)
        runCurrent()
        assertEquals(1, activeHolds)

        worker.gate!!.complete(Unit)
        runCurrent()
        assertEquals(0, activeHolds)
    }

    @Test
    fun `hiding the app consolidates pending turns without waiting`() = runTest {
        val scheduler = scheduler()

        scheduler.onAppHidden()
        scheduler.finishTurn(chat, pendingTurns = 1)
        runCurrent()
        assertTrue(worker.calls.isEmpty())

        scheduler.onAppHidden()
        runCurrent()

        assertEquals(1, worker.calls.size)
        assertEquals(0, activeHolds)
    }

    @Test
    fun `a turn that finishes while the app is hidden keeps the process alive until it is consolidated`() = runTest {
        val scheduler = scheduler()
        appVisible = false
        worker.gate = CompletableDeferred()

        scheduler.finishTurn(chat, pendingTurns = 1)
        // 还没查出有没有待整理的内容就先占住保活
        assertEquals(1, activeHolds)
        advanceTimeBy(2.minutes - 1.seconds)
        // 和在前台时一样等空闲，等的时候一直占着
        assertTrue(worker.calls.isEmpty())
        assertEquals(1, activeHolds)

        advanceTimeBy(2.seconds)
        assertEquals(1, worker.calls.size)
        assertTrue(activeHolds > 0)

        worker.gate!!.complete(Unit)
        runCurrent()
        assertEquals(0, activeHolds)
    }

    @Test
    fun `turns that finish in quick succession while the app is hidden are consolidated once`() = runTest {
        val scheduler = scheduler()
        appVisible = false

        scheduler.finishTurn(chat, pendingTurns = 1)
        advanceTimeBy(1.minutes)
        scheduler.finishTurn(chat, pendingTurns = 2)
        runCurrent()
        // 前一次的保活随被顶掉的定时器放开，中间没有断过
        assertEquals(1, activeHolds)
        advanceTimeBy(2.minutes + 1.seconds)

        assertEquals(1, worker.calls.size)
        assertEquals(0, activeHolds)
    }

    @Test
    fun `reaching the turn limit while the app is hidden consolidates without waiting`() = runTest {
        val scheduler = scheduler()
        appVisible = false

        scheduler.finishTurn(chat, pendingTurns = 3)
        runCurrent()

        assertEquals(1, worker.calls.size)
        assertEquals(0, activeHolds)
    }

    @Test
    fun `nothing is held once a hidden turn turns out to have nothing new`() = runTest {
        val scheduler = scheduler()
        appVisible = false

        scheduler.onTurnFinished(chat)
        runCurrent()

        assertTrue(worker.calls.isEmpty())
        assertEquals(1, totalHolds)
        assertEquals(0, activeHolds)
    }

    @Test
    fun `one run covers every pending conversation of the store`() = runTest {
        val scheduler = scheduler()

        scheduler.finishTurn(chat, pendingTurns = 1)
        scheduler.finishTurn(otherChat, pendingTurns = 1)
        advanceTimeBy(2.minutes + 1.seconds)

        assertEquals(listOf(setOf(chat.id, otherChat.id)), worker.calls.map { it.conversationIds })
    }

    @Test
    fun `a failed run is reported, keeps the turns pending and is not retried on its own`() = runTest {
        val scheduler = scheduler()
        worker.failure = IllegalStateException("model unavailable")

        scheduler.finishTurn(chat, pendingTurns = 3)
        advanceTimeBy(10.minutes)

        assertEquals(1, worker.calls.size)
        val status = scheduler.statusOf(memoryId).first()
        assertEquals("model unavailable", status.lastRun?.error)
        assertEquals(3, status.pendingTurns)
        assertFalse(status.running)

        // 切走应用、离开对话也不会再试
        scheduler.onAppHidden()
        scheduler.onConversationLeft(chat.id)
        runCurrent()
        assertEquals(1, worker.calls.size)

        // 下一轮对话结束时连同之前的一起重试
        worker.failure = null
        scheduler.finishTurn(chat, pendingTurns = 4)
        runCurrent()
        assertEquals(2, worker.calls.size)
        assertNull(scheduler.statusOf(memoryId).first().lastRun?.error)
    }

    @Test
    fun `turns that finish during a run are consolidated in a later run`() = runTest {
        val scheduler = scheduler()
        worker.gate = CompletableDeferred()

        scheduler.finishTurn(chat, pendingTurns = 3)
        runCurrent()
        assertTrue(scheduler.statusOf(memoryId).first().running)

        worker.pending[otherChat.id] = 1
        worker.gate!!.complete(Unit)
        runCurrent()
        assertFalse(scheduler.statusOf(memoryId).first().running)
        assertEquals(1, scheduler.statusOf(memoryId).first().pendingTurns)

        advanceTimeBy(2.minutes + 1.seconds)

        assertEquals(listOf(setOf(chat.id), setOf(otherChat.id)), worker.calls.map { it.conversationIds })
    }

    @Test
    fun `a turn that finishes while the store is being rescanned is not lost`() = runTest {
        val scheduler = scheduler()
        worker.scanGate = CompletableDeferred()

        scheduler.finishTurn(chat, pendingTurns = 3)
        runCurrent()
        // 整理完了，正在重新查数据库；这时另一个对话的一轮结束了，查询结果里没有它
        assertEquals(1, worker.calls.size)
        scheduler.finishTurn(otherChat, pendingTurns = 1)
        runCurrent()

        worker.scanGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, scheduler.statusOf(memoryId).first().pendingTurns)
        advanceTimeBy(2.minutes + 1.seconds)

        assertEquals(listOf(setOf(chat.id), setOf(otherChat.id)), worker.calls.map { it.conversationIds })
    }

    @Test
    fun `tapping organize again while it is starting does not queue a second review`() = runTest {
        val scheduler = scheduler()
        worker.scanGate = CompletableDeferred()

        scheduler.organize(memoryId)
        runCurrent()
        assertTrue(scheduler.statusOf(memoryId).first().running)
        scheduler.organize(memoryId)

        worker.scanGate!!.complete(Unit)
        runCurrent()

        assertEquals(1, worker.calls.size)
        assertFalse(scheduler.statusOf(memoryId).first().running)
    }

    @Test
    fun `organizing the store reviews it even when no conversation is pending`() = runTest {
        val scheduler = scheduler()

        scheduler.organize(memoryId)
        runCurrent()

        assertEquals(1, worker.calls.size)
        assertTrue(worker.calls.single().fullReview)
        assertTrue(worker.calls.single().conversationIds.isEmpty())
    }

    @Test
    fun `pending conversations found at startup are consolidated after the idle delay`() = runTest {
        val scheduler = scheduler()
        worker.pending[chat.id] = 2

        scheduler.resumePending()
        assertEquals(2, scheduler.statusOf(memoryId).first().pendingTurns)
        advanceTimeBy(2.minutes + 1.seconds)

        assertEquals(listOf(setOf(chat.id)), worker.calls.map { it.conversationIds })
    }

    @Test
    fun `startup resumes every memory store that has pending conversations`() = runTest {
        val scheduler = scheduler()
        worker.pending[chat.id] = 2
        worker.pending[otherChat.id] = 1
        worker.memoryIdOf[otherChat.id] = "other memory"

        scheduler.resumePending()
        assertEquals(2, scheduler.statusOf(memoryId).first().pendingTurns)
        assertEquals(1, scheduler.statusOf("other memory").first().pendingTurns)
        advanceTimeBy(2.minutes + 1.seconds)

        assertEquals(setOf(setOf(chat.id), setOf(otherChat.id)), worker.calls.map { it.conversationIds }.toSet())
    }

    @Test
    fun `turns of an assistant without auto consolidation wait for a manual run`() = runTest {
        val scheduler = scheduler()
        worker.manual += chat.id

        scheduler.finishTurn(chat, pendingTurns = 3)
        runCurrent()
        scheduler.onConversationLeft(chat.id)
        scheduler.onAppHidden()
        advanceTimeBy(10.minutes)
        assertTrue(worker.calls.isEmpty())
        assertEquals(3, scheduler.statusOf(memoryId).first().pendingTurns)

        scheduler.organize(memoryId)
        runCurrent()

        assertEquals(listOf(setOf(chat.id)), worker.calls.map { it.conversationIds })
        assertTrue(worker.calls.single().fullReview)
    }

    @Test
    fun `an automatic run of a shared store leaves out conversations without auto consolidation`() = runTest {
        val scheduler = scheduler()
        worker.manual += otherChat.id

        scheduler.finishTurn(otherChat, pendingTurns = 1)
        scheduler.finishTurn(chat, pendingTurns = 1)
        advanceTimeBy(2.minutes + 1.seconds)

        assertEquals(listOf(setOf(chat.id)), worker.calls.map { it.conversationIds })
        assertEquals(1, scheduler.statusOf(memoryId).first().pendingTurns)
        // 整理完重新查数据库时找到的也只是它，不会再排一次自动整理
        advanceTimeBy(10.minutes)
        assertEquals(1, worker.calls.size)
    }

    @Test
    fun `startup does not schedule stores whose pending conversations are all manual`() = runTest {
        val scheduler = scheduler()
        worker.pending[chat.id] = 2
        worker.manual += chat.id

        scheduler.resumePending()
        advanceTimeBy(10.minutes)

        assertEquals(2, scheduler.statusOf(memoryId).first().pendingTurns)
        assertTrue(worker.calls.isEmpty())
    }

    @Test
    fun `organizing keeps going until every pending conversation is consolidated`() = runTest {
        val scheduler = scheduler()
        val third = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        listOf(chat, otherChat, third).forEach {
            worker.pending[it.id] = 1
            worker.manual += it.id
        }
        worker.perRun = 1

        scheduler.organize(memoryId)
        runCurrent()

        assertEquals(3, worker.calls.size)
        // 只有第一批顺带清理整个记忆库
        assertEquals(listOf(true, false, false), worker.calls.map { it.fullReview })
        val status = scheduler.statusOf(memoryId).first()
        assertEquals(0, status.pendingTurns)
        assertFalse(status.running)
        // 之后也不会再自动整理
        advanceTimeBy(10.minutes)
        assertEquals(3, worker.calls.size)
    }

    @Test
    fun `organizing a long backlog keeps going for as long as it makes progress`() = runTest {
        val scheduler = scheduler()
        worker.pending[chat.id] = 241
        worker.manual += chat.id
        worker.turnsPerRun = 12

        scheduler.organize(memoryId)
        runCurrent()

        assertEquals(21, worker.calls.size)
        assertEquals(0, scheduler.statusOf(memoryId).first().pendingTurns)
    }

    @Test
    fun `organizing stops once a batch makes no progress`() = runTest {
        val scheduler = scheduler()
        worker.pending[chat.id] = 1
        worker.pending[otherChat.id] = 1
        worker.manual += chat.id
        worker.stuck = true

        scheduler.organize(memoryId)
        runCurrent()

        assertEquals(1, worker.calls.size)
        assertFalse(scheduler.statusOf(memoryId).first().running)
    }

    @Test
    fun `a failed batch stops organizing`() = runTest {
        val scheduler = scheduler()
        worker.pending[chat.id] = 1
        worker.pending[otherChat.id] = 1
        worker.perRun = 1
        worker.failure = IllegalStateException("model unavailable")

        scheduler.organize(memoryId)
        runCurrent()

        assertEquals(1, worker.calls.size)
        assertEquals("model unavailable", scheduler.statusOf(memoryId).first().lastRun?.error)
    }
}
