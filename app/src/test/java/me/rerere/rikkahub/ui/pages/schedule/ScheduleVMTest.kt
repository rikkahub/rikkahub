package me.rerere.rikkahub.ui.pages.schedule

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.schedule.RecurrenceUnit
import me.rerere.rikkahub.data.db.entity.TaskScheduleEntity
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

    /**
     * The snapshots the screen would render — the [ScheduleUiState.Content] list, or empty for the
     * Loading/Empty/Error cases. Lets the M1/M2/M5 tests keep asserting on "the published list" now that
     * the list lives inside the sealed [ScheduleUiState] (SPEC.md M6) instead of a bare StateFlow.
     */
    private val ScheduleVM.publishedSchedules: List<me.rerere.ai.runtime.contract.ScheduleSnapshot>
        get() = (uiState.value as? ScheduleUiState.Content)?.schedules ?: emptyList()

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

    // SC2 (SPEC.md M2): a repository Rejected on create must NOT mutate VM state. The dialog owns its own
    // field state and decides whether to dismiss; the VM is the legality conduit only, so a rejection at
    // the VM seam must (a) leave the bound-conversation binding intact for a pre-bound parent, (b) leave
    // the published _schedules list untouched (no spurious refresh that could blank or reorder cards), and
    // (c) return the Rejected carrying a non-empty reason for the dialog to render inline. There is no
    // dialog-close side effect at this seam — createSchedule returns a result, it never signals dismissal.
    // This pins the "keep dialog open, input intact, show the reason" contract at the VM level, distinct
    // from the rollback test above (which only asserts the pre-bound parent is not unbound).
    @Test
    fun rejected_create_on_prebound_conversation_leaves_vm_state_unchanged() = runBlocking {
        val existing = Uuid.random()
        val f = Fixture(boundConversationId = existing)
        // Establish a real, non-empty published list first so "unchanged" is observable: an accepted
        // create populates _schedules with one snapshot scoped to the pre-bound conversation.
        assertTrue(f.vm.createSchedule(f.oneShotDraft()) is ScheduleMutationResult.Accepted)
        val scheduleBefore = f.vm.publishedSchedules
        assertEquals(1, scheduleBefore.size)

        val result = f.vm.createSchedule(f.rejectedDraft())

        // The rejection carries a reason for the dialog to render inline — never a bare flag.
        assertTrue("expected Rejected, got $result", result is ScheduleMutationResult.Rejected)
        assertTrue(
            "Rejected must carry a non-empty reason for inline display",
            (result as ScheduleMutationResult.Rejected).reason.isNotBlank(),
        )
        // The pre-bound parent is NOT unbound on rejection: binding is identical to before.
        assertNull("must not roll back a pre-bound conversation", f.rollback.rolledBack)
        assertEquals(existing, f.vm.conversationId.value)
        // The published list is byte-for-byte the pre-rejection list — no spurious refresh/clear.
        assertEquals(scheduleBefore, f.vm.publishedSchedules)
    }

    // SC1 regression (SPEC.md M1): a schedule seeded on the conversation a VM is BOUND to must be
    // visible from that VM. The empty-list bug was a data-scope failure — the drawer navigated to
    // Screen.Schedule carrying only the assistant id, so ScheduleVM started with
    // initialConversationId == null and listSchedules()/load() short-circuited to emptyList() while
    // _conversationId was unbound, hiding schedules created by tools or a prior session. The route now
    // threads the conversation id (T1); this test pins that the bound id flows through to the listing
    // path. It fails under the pre-T1 reasoning (a null binding short-circuits to empty) and passes once
    // the bound id reaches listSchedules().
    @Test
    fun bound_conversation_lists_a_schedule_seeded_on_it() = runBlocking {
        val existing = Uuid.random()
        val f = Fixture(boundConversationId = existing)
        // Seed through the SAME repository legality path a tool/prior session would use — scoped to the
        // conversation the VM is bound to, not via the VM's own create (which would also exercise the
        // ensure-conversation seam). This isolates the listing/data-scope behavior SC1 is about.
        val seeded = f.repository.create(
            existing,
            ScheduleOwner.USER,
            f.oneShotDraft().copy(targetAssistantId = f.target.id),
        ) as? ScheduleMutationResult.Accepted
        assertNotNull("seeding the bound conversation must be Accepted", seeded)

        val snapshots = f.vm.listSchedules()

        // listSchedules() returns the seeded schedule (not the pre-T1 empty short-circuit)...
        assertEquals(1, snapshots.size)
        assertEquals(seeded!!.snapshot.id, snapshots.single().id)
        assertEquals(f.target.id, snapshots.single().targetAssistantId)
        // ...and load() publishes it to the StateFlow the screen observes (size 1, not empty).
        f.vm.load()
        assertEquals(1, f.vm.publishedSchedules.size)
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
    // SC3 invariant (SPEC.md M4 / task T9): the UI must never OFFER a draft the repository will reject.
    // The create button is enabled iff ScheduleFormState.validate(now) is empty; this test pins the dual
    // guarantee — every form state validate() deems submittable projects (via the SAME toDraft() the
    // dialog's confirm uses) into a draft the repository ACCEPTS. Were a mirrored gate weaker than its
    // repository counterpart, some submittable form would yield a Rejected here, breaking the contract.
    // The drafts span each gate's submittable side: a plain one-shot, MINUTES at the 15-min floor, HOURS
    // at every=1, DAYS with and without a timeOfDay anchor, a non-default IANA zone, and a prompt at the
    // exact 8000-char bound — the boundary inputs whose repository acceptance most depends on the mirror
    // being exact. Each runs on its own conversation so the per-conversation cap never confounds the test.
    @Test
    fun every_submittable_form_state_yields_an_accepted_repository_create() = runBlocking {
        val now = 1_700_000_000_000L
        val futureFire = now + 60 * 60 * 1000L
        fun form(
            prompt: String = "do a thing",
            kind: ScheduleKind = ScheduleKind.ONE_SHOT,
            every: Int = 1,
            unit: RecurrenceUnit = RecurrenceUnit.HOURS,
            timeOfDay: String? = null,
            timeZoneId: String = "UTC",
        ) = ScheduleFormState(
            prompt = prompt,
            kind = kind,
            every = every,
            unit = unit,
            firstFireAt = futureFire,
            timeOfDay = timeOfDay,
            timeZoneId = timeZoneId,
        )

        val submittable = listOf(
            form(),
            form(kind = ScheduleKind.RECURRING, every = 15, unit = RecurrenceUnit.MINUTES),
            form(kind = ScheduleKind.RECURRING, every = 1, unit = RecurrenceUnit.HOURS),
            form(kind = ScheduleKind.RECURRING, every = 1, unit = RecurrenceUnit.DAYS, timeOfDay = "09:00"),
            form(kind = ScheduleKind.RECURRING, every = 2, unit = RecurrenceUnit.DAYS, timeOfDay = null),
            form(timeZoneId = "Asia/Jakarta"),
            form(prompt = "a".repeat(TaskScheduleRepository.MAX_PROMPT_CHARS)),
        )

        for (state in submittable) {
            assertTrue(
                "fixture invariant: this form must validate as submittable: ${state.validate(now)}",
                state.validate(now).isEmpty(),
            )
            // Each create runs on its own bound conversation so the per-conversation active cap (20)
            // cannot reject a later draft for a reason unrelated to the form's own legality.
            val f = Fixture(boundConversationId = Uuid.random())
            val result = f.vm.createSchedule(state.toDraft())
            assertTrue(
                "a submittable form must be Accepted, got $result for $state",
                result is ScheduleMutationResult.Accepted,
            )
        }
    }

    // SC5 (SPEC.md M5 / task T11): the card's pause/resume Switch flips `enabled` ONLY through the
    // repository's setEnabled — the single legality path — and a successful toggle refreshes the
    // published list so the card re-renders the new run-state. This pins the VM seam: setEnabled routes
    // the toggle to repository.setEnabled (scoped to the bound conversation) and re-lists on Accepted, so
    // the StateFlow the screen observes carries the post-toggle enabled value (never a stale flag).
    @Test
    fun set_enabled_routes_through_repository_and_refreshes() = runBlocking {
        val existing = Uuid.random()
        val f = Fixture(boundConversationId = existing)
        val created = f.vm.createSchedule(f.oneShotDraft()) as ScheduleMutationResult.Accepted
        // The created schedule starts enabled and is the published list's single snapshot.
        assertTrue(f.vm.publishedSchedules.single().enabled)

        val paused = f.vm.setScheduleEnabled(created.snapshot.id, enabled = false)

        // The toggle was judged by the repository (Accepted), and the DAO row — the single source of
        // truth — now carries the new flag (proving the write went through the repository, not a shortcut).
        assertTrue("expected Accepted, got $paused", paused is ScheduleMutationResult.Accepted)
        assertEquals(false, f.dao.getById(created.snapshot.id.toString())?.enabled)
        // The published list refreshed, so the screen observes the disabled snapshot.
        assertEquals(false, f.vm.publishedSchedules.single().enabled)

        // Resuming routes the same way and the refresh re-publishes the enabled snapshot.
        val resumed = f.vm.setScheduleEnabled(created.snapshot.id, enabled = true)
        assertTrue(resumed is ScheduleMutationResult.Accepted)
        assertEquals(true, f.dao.getById(created.snapshot.id.toString())?.enabled)
        assertEquals(true, f.vm.publishedSchedules.single().enabled)
    }

    // An unbound screen has no conversation to scope a toggle to, so setEnabled must REJECT rather than
    // reach into a random/foreign conversation — mirroring deleteSchedule's "no conversation bound" guard.
    @Test
    fun set_enabled_on_unbound_screen_rejects() = runBlocking {
        val f = Fixture(boundConversationId = null)

        val result = f.vm.setScheduleEnabled(Uuid.random(), enabled = false)

        assertTrue("expected Rejected, got $result", result is ScheduleMutationResult.Rejected)
    }

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

    // SC6 (SPEC.md M6 / task T12): the screen must distinguish loading / empty / error / populated,
    // so "loading" is no longer indistinguishable from "empty". The VM exposes a sealed [ScheduleUiState];
    // load() publishes Loading first, then resolves to Empty (no rows), Content(list) (rows), or — only on
    // an UNEXPECTED exception (domain rejections come back as Rejected, not throws) — Error. These tests pin
    // each transition at the VM seam, the layer CI actually runs (pure JVM unit, no Compose).

    @Test
    fun ui_state_starts_loading_before_load() {
        val f = Fixture(boundConversationId = Uuid.random())
        // The init state is Loading, not a bare empty list — the screen renders a spinner, not the
        // empty-state CTA, until load() resolves.
        assertTrue(
            "expected Loading init, got ${f.vm.uiState.value}",
            f.vm.uiState.value is ScheduleUiState.Loading,
        )
    }

    @Test
    fun load_with_no_schedules_resolves_to_empty() = runBlocking {
        val f = Fixture(boundConversationId = Uuid.random())

        f.vm.listSchedules()

        assertTrue(
            "an empty conversation must resolve to Empty, got ${f.vm.uiState.value}",
            f.vm.uiState.value is ScheduleUiState.Empty,
        )
    }

    @Test
    fun load_with_schedules_resolves_to_content() = runBlocking {
        val existing = Uuid.random()
        val f = Fixture(boundConversationId = existing)
        f.repository.create(existing, ScheduleOwner.USER, f.oneShotDraft().copy(targetAssistantId = f.target.id))

        f.vm.listSchedules()

        val state = f.vm.uiState.value
        assertTrue("a populated conversation must resolve to Content, got $state", state is ScheduleUiState.Content)
        assertEquals(1, (state as ScheduleUiState.Content).schedules.size)
    }

    // A repository THROW (not a Rejected — domain errors come back as Rejected) is the only path to Error:
    // an unexpected exception while listing must surface as Error(message), never crash the screen or be
    // mistaken for Empty. A DAO whose listByConversation throws stands in for an unexpected persistence fault.
    @Test
    fun load_with_repository_throw_resolves_to_error() = runBlocking {
        val existing = Uuid.random()
        val throwingDao = object : FakeTaskScheduleDAO() {
            override suspend fun listByConversation(conversationId: String): List<TaskScheduleEntity> =
                throw IllegalStateException("db unavailable")
        }
        val target = Assistant(id = Uuid.random(), name = "agent", spawnable = true)
        val repository = TaskScheduleRepository(
            dao = throwingDao,
            transactions = FakeBoardTransactions(),
            resolveAssistant = { id -> if (id == target.id) target else null },
            now = MutableClock()::current,
        )
        val vm = ScheduleVM(
            targetAssistantId = target.id,
            initialConversationId = existing,
            repository = repository,
            ensureConversation = { existing },
            rollbackConversation = {},
        )

        vm.listSchedules()

        val state = vm.uiState.value
        assertTrue("an unexpected throw must resolve to Error, got $state", state is ScheduleUiState.Error)
        assertTrue("Error must carry a message", (state as ScheduleUiState.Error).message.isNotBlank())
    }
}
