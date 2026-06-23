package me.rerere.rikkahub.data.ai.agentevent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.entity.AgentEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * JVM unit tests for [AgentEventDrainCoordinator] (#360 P4): the durable agent-event drain
 * COORDINATION extracted from ChatService. Pins the race-relevant orchestration the original
 * inline methods only documented — the snapshot-bounded drain loop, idle gating
 * ([AgentEventQueueReducer.canDrain]), the slot claim (NO_DOUBLE_GENERATION), and enqueue's
 * persist-then-kick — now testable with fake store / gate / delivery, without the full ChatService.
 *
 * The scope is [Dispatchers.Unconfined] so a `launch(LAZY)` started by the fake slot-claim runs its
 * body (and its completion re-poke) synchronously on the test thread: deterministic, no awaits.
 */
class AgentEventDrainCoordinatorTest {

    private fun event(seq: Long) = AgentEventEntity(
        id = "e$seq",
        conversationId = "c",
        dedupeKey = "d$seq",
        enqueueSeq = seq,
        kind = "k",
        payloadJson = "{}",
        status = "PENDING",
        createdAt = 0L,
    )

    /** A fake store exposing only what the coordinator drives: enqueue + listPending. */
    private inner class FakeStore : AgentEventStore {
        val pending = ArrayDeque<AgentEventEntity>()
        var enqueueReturns = true
        var enqueueThrows: Throwable? = null
        var enqueueCount = 0

        fun seed(n: Int) = repeat(n) { pending.addLast(event(it.toLong())) }

        override suspend fun enqueue(
            conversationId: Uuid,
            kind: String,
            payloadJson: String,
            dedupeKey: String,
        ): Boolean {
            enqueueCount++
            enqueueThrows?.let { throw it }
            if (enqueueReturns) pending.addLast(event(pending.size.toLong()))
            return enqueueReturns
        }

        override suspend fun listPending(conversationId: Uuid): List<AgentEventEntity> =
            pending.toList()

        override suspend fun claimAndAppendAndConsume(
            conversationId: Uuid,
            append: suspend (AgentEventEntity) -> ClaimAppendAction,
        ): ClaimOutcome = ClaimOutcome.Empty

        override suspend fun conversationsWithPending(): List<Uuid> = emptyList()

        override suspend fun deleteByConversationId(conversationId: Uuid): Int = 0
    }

    private val cid = Uuid.random()

    @Test
    fun `drainAtTurnEnd drains exactly the snapshot count even when delivery reports more remain`() =
        runBlocking {
            val store = FakeStore().apply { seed(3) }
            var drainOneCalls = 0
            val coordinator = AgentEventDrainCoordinator(
                store = store,
                scope = CoroutineScope(Dispatchers.Unconfined),
                turnGate = { TurnGateState.IDLE },
                claimIdleSlot = { _, _ -> false },
                withConversationRef = { _, _ -> },
                hydrate = {},
                drainOne = { drainOneCalls++; true }, // always "more may remain"
                signalDrainPass = {},
                reportError = { _, _ -> },
            )

            coordinator.drainAtTurnEnd(cid)

            assertEquals(
                "the snapshot count BOUNDS the loop — a continuation enqueuing mid-drain is not chased",
                3,
                drainOneCalls,
            )
        }

    @Test
    fun `drainAtTurnEnd stops early when delivery reports nothing left`() = runBlocking {
        val store = FakeStore().apply { seed(5) }
        var drainOneCalls = 0
        val results = ArrayDeque(listOf(true, true, false))
        val coordinator = AgentEventDrainCoordinator(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            turnGate = { TurnGateState.IDLE },
            claimIdleSlot = { _, _ -> false },
            withConversationRef = { _, _ -> },
            hydrate = {},
            drainOne = { drainOneCalls++; results.removeFirst() },
            signalDrainPass = {},
            reportError = { _, _ -> },
        )

        coordinator.drainAtTurnEnd(cid)

        assertEquals("a false from drainOne ends the pass even with budget remaining", 3, drainOneCalls)
    }

    @Test
    fun `maybeDrainWhenIdle does not claim a slot or drain in any non-IDLE state`() {
        for (state in listOf(
            TurnGateState.GENERATING,
            TurnGateState.PAUSED_FOR_APPROVAL,
            TurnGateState.STOPPING,
        )) {
            val store = FakeStore().apply { seed(2) }
            var claimCalls = 0
            var drainOneCalls = 0
            var hydrateCalls = 0
            val coordinator = AgentEventDrainCoordinator(
                store = store,
                scope = CoroutineScope(Dispatchers.Unconfined),
                turnGate = { state },
                claimIdleSlot = { _, factory -> claimCalls++; factory().start(); true },
                withConversationRef = { _, _ -> },
                hydrate = { hydrateCalls++ },
                drainOne = { drainOneCalls++; store.pending.removeFirstOrNull() != null },
                signalDrainPass = {},
                reportError = { _, _ -> },
            )

            coordinator.maybeDrainWhenIdle(cid)

            assertEquals("$state must not claim the generation slot", 0, claimCalls)
            assertEquals("$state must not hydrate", 0, hydrateCalls)
            assertEquals("$state must not drain", 0, drainOneCalls)
            assertEquals("$state must leave the events buffered", 2, store.pending.size)
        }
    }

    @Test
    fun `maybeDrainWhenIdle claims the slot, hydrates before draining, then signals`() = runBlocking {
        val store = FakeStore().apply { seed(2) }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val order = mutableListOf<String>()
        var claimCalls = 0
        var signalCalls = 0
        val coordinator = AgentEventDrainCoordinator(
            store = store,
            scope = scope,
            turnGate = { TurnGateState.IDLE },
            claimIdleSlot = { _, factory -> claimCalls++; factory().start(); true },
            withConversationRef = { _, block -> scope.launch { block() } },
            hydrate = { order += "hydrate" },
            drainOne = { order += "drain"; store.pending.removeFirstOrNull() != null },
            signalDrainPass = { signalCalls++ },
            reportError = { _, _ -> },
        )

        coordinator.maybeDrainWhenIdle(cid)

        assertEquals("idle drain claims the generation slot exactly once", 1, claimCalls)
        assertEquals("hydrate must run before any delivery (cold-start data-loss guard)", "hydrate", order.first())
        assertEquals("the 2-event snapshot is delivered then the loop ends", listOf("hydrate", "drain", "drain"), order)
        assertTrue("the pass is signalled after draining", signalCalls == 1)
        assertTrue("all pending events were drained", store.pending.isEmpty())
    }

    @Test
    fun `enqueue persists then kicks an idle drain`() = runBlocking {
        val store = FakeStore()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var claimCalls = 0
        val coordinator = AgentEventDrainCoordinator(
            store = store,
            scope = scope,
            turnGate = { TurnGateState.IDLE },
            claimIdleSlot = { _, factory -> claimCalls++; factory().start(); true },
            withConversationRef = { _, block -> scope.launch { block() } },
            hydrate = {},
            drainOne = { store.pending.removeFirstOrNull() != null },
            signalDrainPass = {},
            reportError = { _, _ -> },
        )

        coordinator.enqueue(cid, kind = "k", payloadJson = "{}", dedupeKey = "d")

        assertEquals("the event is persisted once", 1, store.enqueueCount)
        assertEquals("a successful enqueue kicks an idle drain", 1, claimCalls)
        assertTrue("the freshly enqueued event was drained", store.pending.isEmpty())
    }

    @Test
    fun `enqueue still kicks a drain when the call succeeds but dedupes to no new row`() = runBlocking {
        // The drain kick gates on the enqueue call NOT THROWING (runCatching{}.isSuccess), NOT on the
        // returned Boolean — preserved 1:1 from the original inline ChatService.enqueueAgentEvent. A
        // dedupe no-op (returns false, no throw) therefore still kicks an idle drain: harmless (the
        // drain is idle-gated and AT_MOST_ONCE), and it lets an already-pending event get delivered.
        val store = FakeStore().apply { enqueueReturns = false }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var claimCalls = 0
        val coordinator = AgentEventDrainCoordinator(
            store = store,
            scope = scope,
            turnGate = { TurnGateState.IDLE },
            claimIdleSlot = { _, _ -> claimCalls++; true },
            withConversationRef = { _, block -> scope.launch { block() } },
            hydrate = {},
            drainOne = { false },
            signalDrainPass = {},
            reportError = { _, _ -> },
        )

        coordinator.enqueue(cid, kind = "k", payloadJson = "{}", dedupeKey = "dup")

        assertEquals("the dedupe-no-op enqueue still touches the store", 1, store.enqueueCount)
        assertEquals("a non-throwing enqueue kicks an idle drain regardless of the dedupe result", 1, claimCalls)
    }

    @Test
    fun `enqueue surfaces a store failure via reportError and does not kick a drain`() = runBlocking {
        val store = FakeStore().apply { enqueueThrows = IllegalStateException("db down") }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var claimCalls = 0
        val errors = mutableListOf<Throwable>()
        val coordinator = AgentEventDrainCoordinator(
            store = store,
            scope = scope,
            turnGate = { TurnGateState.IDLE },
            claimIdleSlot = { _, _ -> claimCalls++; true },
            withConversationRef = { _, block -> scope.launch { block() } },
            hydrate = {},
            drainOne = { false },
            signalDrainPass = {},
            reportError = { e, _ -> errors += e },
        )

        coordinator.enqueue(cid, kind = "k", payloadJson = "{}", dedupeKey = "d")

        assertEquals("a persist failure is surfaced, never swallowed", 1, errors.size)
        assertTrue(errors.first() is IllegalStateException)
        assertEquals("a failed enqueue must not kick a drain", 0, claimCalls)
    }
}
