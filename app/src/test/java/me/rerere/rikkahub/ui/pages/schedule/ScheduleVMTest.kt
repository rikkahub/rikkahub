package me.rerere.rikkahub.ui.pages.schedule

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.TaskScheduleRepository
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeTaskScheduleDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * UI-path unit tests for [ScheduleVM] (SPEC.md M5 / task T10). The schedule screen WRITES through the
 * SAME [TaskScheduleRepository] the schedule tools use, so a UI create is judged by the identical
 * legality gates (no UI-only validation path). The distinguishing UI invariant proven here: a create
 * with NO existing conversation creates a conversation bound to the target assistant FIRST and binds
 * the new schedule to its id — never [me.rerere.rikkahub.data.ai.task.TaskCoordinator]'s `Uuid.random()`
 * parent default (spec assumption 5).
 */
class ScheduleVMTest {

    private class MutableClock {
        private var t = 1_000L
        fun current(): Long = ++t
    }

    /** Records the conversation-ensure seam so the test can assert it ran (or did not). */
    private class RecordingEnsureConversation(private val produced: Uuid) {
        var calledWithAssistant: Uuid? = null
        val ensure: suspend (Uuid) -> Uuid = { assistantId ->
            calledWithAssistant = assistantId
            produced
        }
    }

    /** Records the rollback seam so the test can assert which conversation id (if any) it deleted. */
    private class RecordingRollbackConversation {
        var rolledBack: Uuid? = null
        val rollback: suspend (Uuid) -> Unit = { id -> rolledBack = id }
    }

    private class Fixture(boundConversationId: Uuid?) {
        val dao = FakeTaskScheduleDAO()
        val target = Assistant(id = Uuid.random(), name = "agent", spawnable = true)
        val createdConversationId = Uuid.random()
        val ensure = RecordingEnsureConversation(createdConversationId)
        val rollback = RecordingRollbackConversation()
        val repository = TaskScheduleRepository(
            dao = dao,
            transactions = FakeBoardTransactions(),
            resolveAssistant = { id -> if (id == target.id) target else null },
            now = MutableClock()::current,
        )
        val vm = ScheduleVM(
            targetAssistantId = target.id,
            initialConversationId = boundConversationId,
            repository = repository,
            ensureConversation = ensure.ensure,
            rollbackConversation = rollback.rollback,
        )

        // The dialog never supplies a target — Uuid.NIL stands in, and the VM must stamp the
        // screen's bound assistant before the draft reaches the repository.
        fun oneShotDraft() = ScheduleDraft(
            targetAssistantId = Uuid.NIL,
            prompt = "remind me",
            kind = ScheduleKind.ONE_SHOT,
            firstFireAt = 10_000L,
            timeZoneId = "UTC",
        )

        // An over-length prompt trips the cleanest conversation-independent gate
        // (prompt.length > MAX_PROMPT_CHARS), so the repository rejects the create.
        fun rejectedDraft() = oneShotDraft().copy(
            prompt = "x".repeat(TaskScheduleRepository.MAX_PROMPT_CHARS + 1),
        )
    }

    @Test
    fun create_with_no_conversation_creates_one_first_and_binds_it() = runBlocking {
        val f = Fixture(boundConversationId = null)

        val result = f.vm.createSchedule(f.oneShotDraft())

        // The ensure-conversation seam ran with the target assistant, up front.
        assertEquals(f.target.id, f.ensure.calledWithAssistant)

        assertTrue("expected Accepted, got $result", result is ScheduleMutationResult.Accepted)
        // The schedule landed scoped to the NEWLY created conversation — not a random parent.
        val rows = f.dao.listByConversation(f.createdConversationId.toString())
        assertEquals(1, rows.size)
        assertEquals(ScheduleOwner.USER.name, rows.single().owner)
        // No row ever leaked onto any other (random) conversation id.
        assertEquals(f.createdConversationId.toString(), rows.single().conversationId)
        // The VM stamped the screen's bound assistant onto the draft (the dialog passed Uuid.NIL).
        assertEquals(f.target.id.toString(), rows.single().targetAssistantId)
    }

    @Test
    fun create_with_existing_conversation_does_not_create_another() = runBlocking {
        val existing = Uuid.random()
        val f = Fixture(boundConversationId = existing)

        val result = f.vm.createSchedule(f.oneShotDraft())

        assertNull("must not create a conversation when one is already bound", f.ensure.calledWithAssistant)
        assertTrue(result is ScheduleMutationResult.Accepted)
        val rows = f.dao.listByConversation(existing.toString())
        assertEquals(1, rows.size)
        assertEquals(existing.toString(), rows.single().conversationId)
    }

    @Test
    fun rejected_first_create_rolls_back_the_freshly_created_conversation() = runBlocking {
        val f = Fixture(boundConversationId = null)

        val result = f.vm.createSchedule(f.rejectedDraft())

        assertTrue("expected Rejected, got $result", result is ScheduleMutationResult.Rejected)
        // The conversation WAS materialized up front (the gate runs only after binding)...
        assertEquals(f.target.id, f.ensure.calledWithAssistant)
        // ...and the rejected create rolled that exact conversation back and unbound it.
        assertEquals(f.createdConversationId, f.rollback.rolledBack)
        assertNull(f.vm.conversationId.value)
        assertTrue(f.dao.listByConversation(f.createdConversationId.toString()).isEmpty())
    }

    @Test
    fun rejected_create_on_prebound_conversation_does_not_roll_back() = runBlocking {
        val existing = Uuid.random()
        val f = Fixture(boundConversationId = existing)

        val result = f.vm.createSchedule(f.rejectedDraft())

        assertTrue(result is ScheduleMutationResult.Rejected)
        // wasUnbound is false, so a pre-bound parent is never deleted and stays bound.
        assertNull("must not roll back a pre-bound conversation", f.rollback.rolledBack)
        assertEquals(existing, f.vm.conversationId.value)
    }

    @Test
    fun list_is_scoped_to_the_bound_conversation() = runBlocking {
        val existing = Uuid.random()
        val f = Fixture(boundConversationId = existing)
        f.vm.createSchedule(f.oneShotDraft())

        val snapshots = f.vm.listSchedules()

        assertEquals(1, snapshots.size)
        assertEquals(f.target.id, snapshots.single().targetAssistantId)
    }

    @Test
    fun delete_removes_a_bound_schedule() = runBlocking {
        val existing = Uuid.random()
        val f = Fixture(boundConversationId = existing)
        val created = f.vm.createSchedule(f.oneShotDraft()) as ScheduleMutationResult.Accepted

        val result = f.vm.deleteSchedule(created.snapshot.id)

        assertTrue(result is ScheduleMutationResult.Accepted)
        assertNull(f.dao.getById(created.snapshot.id.toString()))
    }

    // Regression for the concurrent-create race: two fire-and-forget creates from an UNBOUND screen must
    // materialize EXACTLY ONE parent conversation. Before createSchedule was serialized, both calls
    // observed _conversationId == null across the ensureConversation suspension and each materialized a
    // conversation; a sibling rejection's rollback could then unbind a parent the other create had bound,
    // leaving the accepted schedule unreachable from the screen.
    @Test
    fun concurrent_unbound_creates_materialize_exactly_one_conversation() = runBlocking {
        val f = Fixture(boundConversationId = null)
        val ensureCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        // ensureConversation holds inside its critical section so both creates would race the unbound
        // check if createSchedule were not serialized.
        val vm = ScheduleVM(
            targetAssistantId = f.target.id,
            initialConversationId = null,
            repository = f.repository,
            ensureConversation = { _ ->
                ensureCalls.incrementAndGet()
                gate.await()
                f.createdConversationId
            },
            rollbackConversation = f.rollback.rollback,
        )

        // Unconfined → each launch runs eagerly to its first real suspension at launch time.
        val a = launch(Dispatchers.Unconfined) { vm.createSchedule(f.oneShotDraft()) }
        val b = launch(Dispatchers.Unconfined) { vm.createSchedule(f.oneShotDraft()) }

        // Without serialization both calls reach ensureConversation here (count 2 — fails); the mutex
        // lets only the lock holder through (count 1 — passes).
        assertEquals("only one create may materialize a conversation under contention", 1, ensureCalls.get())

        gate.complete(Unit)
        a.join()
        b.join()

        assertEquals(1, ensureCalls.get())
        // The second create reused the bound parent; the binding is intact (never nulled by the sibling)
        // and both schedules landed on the single conversation.
        assertEquals(f.createdConversationId, vm.conversationId.value)
        assertEquals(2, f.dao.listByConversation(f.createdConversationId.toString()).size)
        assertNull("a successful create must never be rolled back", f.rollback.rolledBack)
    }
}
