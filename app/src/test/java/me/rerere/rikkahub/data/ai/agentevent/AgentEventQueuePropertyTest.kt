package me.rerere.rikkahub.data.ai.agentevent

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.entity.AgentEventStatus
import me.rerere.rikkahub.data.repository.BoardTransactionRunner
import me.rerere.rikkahub.data.repository.fakes.FakeAgentEventDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Property suite for the durable agent-event queue (issue #290). Each property maps 1:1 to an
 * invariant in the maintainer design proposal and FAILS on a naive implementation for the exact
 * reason named in its KDoc. The queue is modelled the way the proposal asks — the PURE
 * [AgentEventQueueReducer] plus a [FakeAgentEventDAO]-backed [RoomAgentEventStore] — so CI's JVM
 * gate pins the SAME logic the `ChatService` drain uses, with no device/Room/network runtime.
 */
class AgentEventQueuePropertyTest {

    /** A serializing transaction runner — the same one-writer-at-a-time guarantee Room gives. */
    private class FakeTransactions : BoardTransactionRunner {
        private val mutex = Mutex()
        override suspend fun <T> inTransaction(block: suspend () -> T): T = mutex.withLock { block() }
    }

    /**
     * A model conversation with a turn-gate state the drain honours, an event store, and a delivery
     * log. The drain mirrors `ChatService.drainAgentEventsAtTurnEnd`: gate on
     * [AgentEventQueueReducer.canDrain], then claim+append+consume in one transaction, recording the
     * delivered event id in [deliveryLog]. The append writes into [appendedMessages] (the stand-in
     * for `saveConversation`).
     */
    private class QueueModel {
        val dao = FakeAgentEventDAO()
        val store = RoomAgentEventStore(dao = dao, transactions = FakeTransactions(), now = { clock++ })
        val conversationId = Uuid.random()

        var turnState: TurnGateState = TurnGateState.IDLE
        private var clock = 1L

        /** Ordered ids of events actually delivered (the at-most-once / FIFO oracle). */
        val deliveryLog = mutableListOf<String>()

        /** The synthetic messages appended, by event id — survives a simulated process death. */
        val appendedMessages = mutableMapOf<String, String>()

        suspend fun enqueue(dedupeKey: String, kind: String = "test", payload: String = "{}"): Boolean =
            store.enqueue(conversationId, kind, payload, dedupeKey)

        /**
         * One drain attempt, gated exactly like the service. Returns true when an event was
         * delivered. A drain claims at most one event (productDecision #5: one per continuation).
         */
        suspend fun drainOnce(): Boolean {
            if (!AgentEventQueueReducer.canDrain(turnState)) return false
            val outcome = store.claimAndAppendAndConsume(conversationId) { event ->
                appendedMessages[event.id] = event.payloadJson
                SyntheticAppendResult(
                    syntheticNodeId = "node-${event.id}",
                    syntheticMessageId = "msg-${event.id}",
                )
            }
            return if (outcome is ClaimOutcome.Delivered) {
                deliveryLog += outcome.event.id
                true
            } else {
                false
            }
        }

        /** Drain to exhaustion at the current turn state (multiple events at one idle turn-end). */
        suspend fun drainAll() {
            while (drainOnce()) { /* claim until empty */ }
        }
    }

    // --- 1. AT_MOST_ONCE (Boundary) -----------------------------------------------------------
    // Fails-before if claim+append+consume are not one transaction: a second replay would re-deliver
    // an already-appended event. Here each id appears in the delivery log at most once across any
    // sequence of enqueue / drain / duplicate-replay.
    @Test
    fun `at most once across replays`(): Unit = runBlocking {
        checkAll(60, Arb.int(1..6), Arb.int(0..4)) { eventCount, extraReplays ->
            val model = QueueModel()
            repeat(eventCount) { model.enqueue("dedupe-$it") }

            // One live drain pass, then any number of replay passes — all idle.
            model.drainAll()
            repeat(extraReplays) { model.drainAll() }

            assertEquals(
                "each event delivered at most once",
                model.deliveryLog.toSet().size,
                model.deliveryLog.size,
            )
            assertEquals("every enqueued event delivered exactly once", eventCount, model.deliveryLog.size)
        }
    }

    // --- 2. NO_DOUBLE_GENERATION / IDLE_GATING (Invariant) -------------------------------------
    // Enqueue + drain attempts while GENERATING or PAUSED_FOR_APPROVAL yield ZERO deliveries until a
    // successful turn-end transition to IDLE. Fails-before if gating ignores the approval pause (the
    // proposal's first-break point) — canDrain returns true ONLY for IDLE.
    @Test
    fun `held during generation and approval pause`(): Unit = runBlocking {
        checkAll(60, Arb.list(Arb.int(0..2), 1..6)) { stateCodes ->
            val model = QueueModel()
            model.enqueue("e1")

            // Replay drain attempts across a sequence of busy states; none may deliver.
            stateCodes.forEach { code ->
                model.turnState = when (code) {
                    0 -> TurnGateState.GENERATING
                    1 -> TurnGateState.PAUSED_FOR_APPROVAL
                    else -> TurnGateState.STOPPING
                }
                model.drainAll()
            }
            assertTrue("no delivery while busy", model.deliveryLog.isEmpty())

            // The turn ends idle; now exactly one delivery.
            model.turnState = TurnGateState.IDLE
            model.drainAll()
            assertEquals("delivered once it reaches idle", 1, model.deliveryLog.size)
        }
    }

    // --- 3. NO_DRAIN_ON_ABANDON (Invariant) ---------------------------------------------------
    // A STOPPING (user-stop / abandoned-turn) transition does NOT trigger a drain; the event stays
    // PENDING (productDecision #4: keep queued). Fails-before if the drain were wired to job
    // completion rather than a successful turn-end.
    @Test
    fun `abandoned turn does not drain`(): Unit = runBlocking {
        val model = QueueModel()
        model.enqueue("e1")
        model.turnState = TurnGateState.STOPPING
        model.drainAll()

        assertTrue("no delivery on abandon", model.deliveryLog.isEmpty())
        assertEquals("event still pending", 1, model.dao.listPending(model.conversationId.toString()).size)
    }

    // --- 4. FIFO_PER_CONVERSATION (Invariant) -------------------------------------------------
    // Multiple pending events for one conversation deliver in enqueue order. Fails-before if order
    // were inferred from insertion-map iteration or a timestamp rather than the stable enqueue_seq.
    @Test
    fun `delivers in enqueue order`(): Unit = runBlocking {
        checkAll(60, Arb.int(2..8)) { eventCount ->
            val model = QueueModel()
            val expectedOrder = (0 until eventCount).map { "k$it" }
            expectedOrder.forEach { model.enqueue(it) }

            model.drainAll()

            // The delivery log is by event id (a random UUID), so map back to enqueue_seq order via
            // the store: the n-th delivered id must be the n-th enqueued (smallest-seq-first).
            val deliveredSeqs = model.deliveryLog.map { id ->
                // Reconstruct seq from the consumed row.
                runBlocking { model.dao.getById(id)!!.enqueueSeq }
            }
            assertEquals("delivered strictly by ascending enqueue_seq", deliveredSeqs.sorted(), deliveredSeqs)
            assertEquals(eventCount, deliveredSeqs.size)
        }
    }

    // --- 5. METAMORPHIC replay-noop -----------------------------------------------------------
    // Adding extra no-op replay passes (idle) does not change the delivered set or order, for any
    // interleaving of live-drain and replay. Fails-before if replay re-appends/re-consumes.
    @Test
    fun `extra replays are no-ops`(): Unit = runBlocking {
        checkAll(50, Arb.int(2..6), Arb.int(1..5)) { eventCount, replayRounds ->
            val baseline = QueueModel()
            repeat(eventCount) { baseline.enqueue("d$it") }
            baseline.drainAll()
            val baselineLog = baseline.deliveryLog.toList()

            val withReplays = QueueModel()
            repeat(eventCount) { withReplays.enqueue("d$it") }
            // Interleave: drain one, replay several, repeat.
            repeat(eventCount + replayRounds) {
                withReplays.drainOnce()
                repeat(replayRounds) { withReplays.drainAll() }
            }

            assertEquals(
                "delivered count is identical regardless of replay passes",
                baselineLog.size,
                withReplays.deliveryLog.size,
            )
            assertEquals("each delivered exactly once", eventCount, withReplays.deliveryLog.toSet().size)

            // METAMORPHIC ORDER: the delivery ORDER (by stable enqueue_seq) is identical with or
            // without the replay interleaving — not merely the count/set. Event ids are random per
            // model, so compare the enqueue_seq sequence each delivery maps back to.
            val baselineSeqs = baselineLog.map { id -> runBlocking { baseline.dao.getById(id)!!.enqueueSeq } }
            val replaySeqs = withReplays.deliveryLog.map { id -> runBlocking { withReplays.dao.getById(id)!!.enqueueSeq } }
            assertEquals(
                "delivery order (by enqueue_seq) is unchanged by replay interleaving",
                baselineSeqs,
                replaySeqs,
            )
        }
    }

    // --- 6. SURVIVES_RESTART ------------------------------------------------------------------
    // An event enqueued, then "process death" (drop in-memory live state but keep store rows), is
    // delivered exactly once on the subsequent replay. Fails-before if pending state lived only in
    // memory rather than in the store.
    @Test
    fun `survives process death`(): Unit = runBlocking {
        val dao = FakeAgentEventDAO()
        val transactions = FakeTransactions()
        val conversationId = Uuid.random()

        // Pre-death process: enqueue, no drain (was busy / app killed before turn-end).
        val before = RoomAgentEventStore(dao = dao, transactions = transactions, now = { 1L })
        before.enqueue(conversationId, "shell", "{\"done\":true}", "restart-1")

        // Post-restart process: a fresh store over the SAME dao (the persisted rows), fresh memory.
        val after = RoomAgentEventStore(dao = dao, transactions = transactions, now = { 2L })
        val delivered = mutableListOf<String>()
        val outcome = after.claimAndAppendAndConsume(conversationId) { event ->
            SyntheticAppendResult("node-${event.id}", "msg-${event.id}")
        }
        if (outcome is ClaimOutcome.Delivered) delivered += outcome.event.id

        assertEquals("delivered once after restart", 1, delivered.size)
        // A second replay after restart is a no-op.
        val second = after.claimAndAppendAndConsume(conversationId) { event ->
            SyntheticAppendResult("node-${event.id}", "msg-${event.id}")
        }
        assertTrue("second replay is empty", second is ClaimOutcome.Empty)
    }

    // --- 7. EMPTY / DELETED boundary ----------------------------------------------------------
    // Empty-queue drain is a no-op; deleteByConversationId removes pending events without launching
    // any delivery.
    @Test
    fun `empty drain and delete cleanup`(): Unit = runBlocking {
        val model = QueueModel()
        model.drainAll()
        assertTrue("empty-queue drain delivers nothing", model.deliveryLog.isEmpty())

        model.enqueue("e1")
        model.enqueue("e2")
        val removed = model.store.deleteByConversationId(model.conversationId)
        assertEquals("both pending events removed", 2, removed)
        assertNull("no oldest pending after delete", model.dao.oldestPending(model.conversationId.toString()))

        model.drainAll()
        assertTrue("no delivery after delete", model.deliveryLog.isEmpty())
    }

    // --- enqueue idempotency (dedupe) ---------------------------------------------------------
    // A duplicate dedupe key is an insert-ignore no-op; the queue holds exactly one row.
    @Test
    fun `duplicate dedupe key is ignored`(): Unit = runBlocking {
        val model = QueueModel()
        assertTrue("first enqueue creates a row", model.enqueue("dup"))
        assertTrue("second enqueue is a no-op", !model.enqueue("dup"))

        assertEquals("only one pending row", 1, model.dao.listPending(model.conversationId.toString()).size)
        model.drainAll()
        assertEquals("delivered once", 1, model.deliveryLog.size)
    }

    // --- status marker sanity -----------------------------------------------------------------
    // A consumed row carries the CONSUMED tag and the stamped synthetic ids (the
    // SYNTHETIC_DISTINCTNESS hook the follow-on filters read).
    @Test
    fun `consumed row stamps synthetic ids`(): Unit = runBlocking {
        val model = QueueModel()
        model.enqueue("e1")
        model.drainAll()

        val id = model.deliveryLog.single()
        val row = model.dao.getById(id)!!
        assertEquals(AgentEventStatus.CONSUMED.name, row.status)
        assertEquals("node-$id", row.syntheticNodeId)
        assertEquals("msg-$id", row.syntheticMessageId)
    }

    // --- 8. RECOVERY RUNNER actively replays on restart (not observe-only) ---------------------
    // The cold-start [AgentEventRecoveryRunner] must DRIVE the drain for every conversation holding
    // pending events through the same claim path (driving delivery, not just scanning), and a second
    // startup pass must deliver nothing new — AT_MOST_ONCE across restarts. Fails-before when the
    // runner was observe-only (it returned a count but delivered nothing).
    @Test
    fun `recovery runner replays pending conversations exactly once`(): Unit = runBlocking {
        val dao = FakeAgentEventDAO()
        val transactions = FakeTransactions()
        var clock = 1L
        val store = RoomAgentEventStore(dao = dao, transactions = transactions, now = { clock++ })

        // Two conversations each hold one pending event, persisted before a simulated process death.
        val convA = Uuid.random()
        val convB = Uuid.random()
        store.enqueue(convA, "shell", "{}", "a-1")
        store.enqueue(convB, "shell", "{}", "b-1")

        val delivered = mutableListOf<String>()
        // The drain callback mirrors ChatService.maybeDrainAgentEventsWhenIdle (idle here, claim one).
        val runner = AgentEventRecoveryRunner(
            store = store,
            drainIfIdle = { id ->
                runBlocking {
                    val outcome = store.claimAndAppendAndConsume(id) { e ->
                        SyntheticAppendResult("node-${e.id}", "msg-${e.id}")
                    }
                    if (outcome is ClaimOutcome.Delivered) delivered += outcome.event.id
                }
            },
        )

        val firstPass = runner.runStartupReplay()
        assertEquals("both pending conversations replayed", 2, firstPass)
        assertEquals("each delivered exactly once on the first replay", 2, delivered.size)
        assertEquals("no duplicate deliveries", delivered.size, delivered.toSet().size)

        // A second cold-start pass finds the rows already CONSUMED -> drains nothing new.
        val secondPass = runner.runStartupReplay()
        assertEquals("no conversations still pending after replay", 0, secondPass)
        assertEquals("second replay delivers nothing new", 2, delivered.size)
    }
}
