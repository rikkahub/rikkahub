package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.runtime.contract.DeliveryMode
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.schedule.RecurrenceSpec
import me.rerere.ai.runtime.schedule.RecurrenceUnit
import me.rerere.rikkahub.data.db.entity.TaskScheduleEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeTaskScheduleDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Tests for [TaskScheduleRepository] (SPEC.md M3 / task T4): the SINGLE legality path UI and tools
 * share. Each create-gate has one test; list and delete are proven scoped to the bound conversation
 * (a foreign id rejects, never silently deletes cross-conversation). Domain rejections come back as
 * [ScheduleMutationResult.Rejected] — never an exception — so a rejected schedule edit cannot abort
 * the chat turn that attempted it.
 */
class TaskScheduleRepositoryTest {

    /** A monotone test clock so updated_at strictly advances per write. */
    private class MutableClock {
        private var t = 1_000L
        fun current(): Long = ++t
    }

    private class Fixture {
        val dao = FakeTaskScheduleDAO()
        val spawnable = Assistant(id = Uuid.random(), name = "agent", spawnable = true)
        val notSpawnable = Assistant(id = Uuid.random(), name = "config", spawnable = false)
        private val registry = mapOf(
            spawnable.id to spawnable,
            notSpawnable.id to notSpawnable,
        )

        /** Records each firing-seam call so a test can assert a fire was (or was NOT) armed/cancelled. */
        val enqueued = mutableListOf<Pair<Uuid, Long>>()
        val cancelled = mutableListOf<Uuid>()

        val repository = TaskScheduleRepository(
            dao = dao,
            transactions = FakeBoardTransactions(),
            resolveAssistant = { id -> registry[id] },
            now = MutableClock()::current,
            onScheduleCreated = { id, fireAt -> enqueued += id to fireAt },
            onScheduleDeleted = { id -> cancelled += id },
        )
    }

    private val zone = "UTC"

    private fun oneShotDraft(
        target: Uuid,
        prompt: String = "remind me",
        firstFireAt: Long = 10_000L,
    ): ScheduleDraft = ScheduleDraft(
        targetAssistantId = target,
        prompt = prompt,
        kind = ScheduleKind.ONE_SHOT,
        firstFireAt = firstFireAt,
        timeZoneId = zone,
    )

    private fun recurringDraft(
        target: Uuid,
        every: Int,
        unit: RecurrenceUnit,
        prompt: String = "morning briefing",
    ): ScheduleDraft = ScheduleDraft(
        targetAssistantId = target,
        prompt = prompt,
        kind = ScheduleKind.RECURRING,
        firstFireAt = 10_000L,
        timeZoneId = zone,
        recurrenceSpec = Json.encodeToString(RecurrenceSpec(every = every, unit = unit)),
    )

    // --- create gates ---------------------------------------------------------------------------

    @Test
    fun create_accepts_a_spawnable_one_shot() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()

        val result = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))

        assertTrue("expected Accepted, got $result", result is ScheduleMutationResult.Accepted)
        val snapshot = (result as ScheduleMutationResult.Accepted).snapshot
        assertEquals(f.spawnable.id, snapshot.targetAssistantId)
        assertEquals(ScheduleKind.ONE_SHOT, snapshot.kind)
        assertEquals(ScheduleOwner.USER, snapshot.owner)
        assertTrue(snapshot.enabled)
        // The row landed scoped to the conversation.
        assertNotNull(f.dao.getById(snapshot.id.toString()))
    }

    @Test
    fun create_rejects_an_unknown_target() = runBlocking {
        val f = Fixture()
        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, oneShotDraft(Uuid.random()))
        assertTrue(result is ScheduleMutationResult.Rejected)
    }

    @Test
    fun create_rejects_a_non_spawnable_target() = runBlocking {
        val f = Fixture()
        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, oneShotDraft(f.notSpawnable.id))
        assertTrue(result is ScheduleMutationResult.Rejected)
    }

    @Test
    fun create_conversation_event_skips_the_spawnable_gate_and_round_trips_delivery_mode() = runBlocking {
        // A /loop schedule (#364 slice 2) injects into its bound conversation and spawns nothing, so the
        // spawnable gate must NOT apply — a non-spawnable target (the conversation's own chat assistant,
        // typically not spawnable) is accepted, and the persisted delivery mode round-trips.
        val f = Fixture()
        val draft = recurringDraft(f.notSpawnable.id, every = 15, unit = RecurrenceUnit.MINUTES)
            .copy(deliveryMode = DeliveryMode.CONVERSATION_EVENT)

        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, draft)

        assertTrue("a CONVERSATION_EVENT schedule bypasses the spawnable gate", result is ScheduleMutationResult.Accepted)
        assertEquals(
            DeliveryMode.CONVERSATION_EVENT,
            (result as ScheduleMutationResult.Accepted).snapshot.deliveryMode,
        )
    }

    @Test
    fun create_detached_task_still_requires_a_spawnable_target() = runBlocking {
        // The default DETACHED_TASK delivery keeps the spawnable gate (it spawns the target as a subagent).
        val f = Fixture()
        val draft = recurringDraft(f.notSpawnable.id, every = 15, unit = RecurrenceUnit.MINUTES)
            .copy(deliveryMode = DeliveryMode.DETACHED_TASK)

        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, draft)

        assertTrue(result is ScheduleMutationResult.Rejected)
    }

    @Test
    fun create_rejects_over_the_per_conversation_cap() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        repeat(TaskScheduleRepository.MAX_ACTIVE_PER_CONVERSATION) {
            val r = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
            assertTrue("seed $it should be accepted", r is ScheduleMutationResult.Accepted)
        }
        val overflow = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
        assertTrue(overflow is ScheduleMutationResult.Rejected)
    }

    @Test
    fun create_rejects_over_the_per_user_cap() = runBlocking {
        val f = Fixture()
        // Spread enabled USER schedules across many conversations so the per-conversation cap never
        // trips first; only the per-user cap can reject here.
        var created = 0
        var conversationCount = 0
        while (created < TaskScheduleRepository.MAX_ACTIVE_PER_USER) {
            val conversationId = Uuid.random()
            conversationCount++
            repeat(TaskScheduleRepository.MAX_ACTIVE_PER_CONVERSATION) {
                if (created < TaskScheduleRepository.MAX_ACTIVE_PER_USER) {
                    val r = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
                    assertTrue(r is ScheduleMutationResult.Accepted)
                    created++
                }
            }
        }
        val overflow = f.repository.create(Uuid.random(), ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
        assertTrue(overflow is ScheduleMutationResult.Rejected)
    }

    @Test
    fun per_owner_cap_isolates_agent_from_user() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        // Fill the per-conversation cap with AGENT schedules.
        repeat(TaskScheduleRepository.MAX_ACTIVE_PER_CONVERSATION) {
            f.repository.create(conversationId, ScheduleOwner.AGENT, oneShotDraft(f.spawnable.id))
        }
        // The per-conversation cap counts all enabled schedules regardless of owner, so a USER add
        // on the SAME conversation is still over the conversation cap.
        val sameConv = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
        assertTrue(sameConv is ScheduleMutationResult.Rejected)
        // But a USER add on a DIFFERENT conversation is fine: the AGENT fill did not consume the
        // USER per-user quota.
        val otherConv = f.repository.create(Uuid.random(), ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
        assertTrue(otherConv is ScheduleMutationResult.Accepted)
    }

    // NOTE: there is no longer a "sub-minimum recurring interval" rejection test. With the floor at
    // 1 minute and MINUTES the smallest unit, the smallest CONSTRUCTIBLE interval (every = 1, MINUTES
    // = 1 min) equals the floor, and RecurrenceSpec.init already rejects `every < 1`. So no decodable
    // spec produces an interval below the floor — the repo's `interval < MIN` guard is unreachable
    // defense-in-depth, not a triggerable path. The malformed-spec rejection is covered separately.

    @Test
    fun create_accepts_a_recurring_interval_at_the_minimum() = runBlocking {
        val f = Fixture()
        // Exactly the 1-minute floor is allowed (boundary).
        val result = f.repository.create(
            Uuid.random(),
            ScheduleOwner.USER,
            recurringDraft(f.spawnable.id, every = 1, unit = RecurrenceUnit.MINUTES),
        )
        assertTrue("expected Accepted, got $result", result is ScheduleMutationResult.Accepted)
    }

    @Test
    fun create_rejects_a_recurring_draft_with_no_spec() = runBlocking {
        val f = Fixture()
        val draft = ScheduleDraft(
            targetAssistantId = f.spawnable.id,
            prompt = "x",
            kind = ScheduleKind.RECURRING,
            firstFireAt = 10_000L,
            timeZoneId = zone,
            recurrenceSpec = null,
        )
        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, draft)
        assertTrue(result is ScheduleMutationResult.Rejected)
    }

    @Test
    fun create_rejects_a_bad_time_zone_id() = runBlocking {
        val f = Fixture()
        // claimDue calls ZoneId.of(row.timeZoneId) at fire time; an unparseable zone must be a
        // Rejected create, not a DateTimeException thrown on every future fire.
        val draft = oneShotDraft(f.spawnable.id).copy(timeZoneId = "Not/AZone")
        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, draft)
        assertTrue("expected Rejected, got $result", result is ScheduleMutationResult.Rejected)
    }

    @Test
    fun create_rejects_a_daily_recurrence_with_a_bad_time_of_day() = runBlocking {
        val f = Fixture()
        val badRecurrenceSpec = """{"every":1,"unit":"DAYS","timeOfDay":"25:99"}"""
        val draft = recurringDraft(f.spawnable.id, every = 1, unit = RecurrenceUnit.DAYS).copy(
            recurrenceSpec = badRecurrenceSpec,
        )
        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, draft)
        assertTrue("expected Rejected, got $result", result is ScheduleMutationResult.Rejected)

        val conversationId = Uuid.random()
        val badRowId = Uuid.random()
        f.dao.insert(
            TaskScheduleEntity(
                id = badRowId.toString(),
                conversationId = conversationId.toString(),
                targetAssistantId = f.spawnable.id.toString(),
                prompt = "morning briefing",
                owner = ScheduleOwner.USER.name,
                kind = ScheduleKind.RECURRING.name,
                recurrenceSpec = badRecurrenceSpec,
                timeZoneId = zone,
                firstFireAt = 10_000L,
                nextFireAt = 10_000L,
                enabled = true,
                createdAt = 10_000L,
                updatedAt = 10_000L,
            )
        )
        val claim = f.repository.claimDue(badRowId, now = 20_000L)
        assertNull(claim)
        val claimedRow = f.dao.getById(badRowId.toString())!!
        assertTrue(!claimedRow.enabled)
    }

    @Test
    fun create_accepts_a_daily_recurrence_with_a_valid_time_of_day() = runBlocking {
        val f = Fixture()
        val draft = recurringDraft(f.spawnable.id, every = 1, unit = RecurrenceUnit.DAYS).copy(
            recurrenceSpec = Json.encodeToString(
                RecurrenceSpec(every = 1, unit = RecurrenceUnit.DAYS, timeOfDay = "09:00"),
            ),
        )
        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, draft)
        assertTrue("expected Accepted, got $result", result is ScheduleMutationResult.Accepted)
    }

    @Test
    fun create_rejects_an_over_length_prompt() = runBlocking {
        val f = Fixture()
        val tooLong = "a".repeat(TaskScheduleRepository.MAX_PROMPT_CHARS + 1)
        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, oneShotDraft(f.spawnable.id, prompt = tooLong))
        assertTrue(result is ScheduleMutationResult.Rejected)
    }

    @Test
    fun create_accepts_a_prompt_at_the_max_length() = runBlocking {
        val f = Fixture()
        val atMax = "a".repeat(TaskScheduleRepository.MAX_PROMPT_CHARS)
        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, oneShotDraft(f.spawnable.id, prompt = atMax))
        assertTrue("expected Accepted, got $result", result is ScheduleMutationResult.Accepted)
    }

    // --- list / delete scoping ------------------------------------------------------------------

    @Test
    fun list_is_scoped_to_the_bound_conversation() = runBlocking {
        val f = Fixture()
        val conversationA = Uuid.random()
        val conversationB = Uuid.random()
        f.repository.create(conversationA, ScheduleOwner.USER, oneShotDraft(f.spawnable.id, firstFireAt = 30_000L))
        f.repository.create(conversationA, ScheduleOwner.USER, oneShotDraft(f.spawnable.id, firstFireAt = 20_000L))
        f.repository.create(conversationB, ScheduleOwner.USER, oneShotDraft(f.spawnable.id, firstFireAt = 99_000L))

        val listA = f.repository.list(conversationA)

        assertEquals(2, listA.size)
        assertTrue("list must only contain conversationA's schedules", listA.all { snapshot ->
            f.dao.getById(snapshot.id.toString())!!.conversationId == conversationA.toString()
        })
        // Presentation order is by next_fire_at ascending.
        assertEquals(listOf(20_000L, 30_000L), listA.map { it.nextFireAt })
    }

    @Test
    fun delete_removes_a_schedule_in_the_bound_conversation() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        val created = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
            as ScheduleMutationResult.Accepted

        val result = f.repository.delete(conversationId, created.snapshot.id)

        assertTrue(result is ScheduleMutationResult.Accepted)
        assertNull(f.dao.getById(created.snapshot.id.toString()))
        assertTrue(f.repository.list(conversationId).isEmpty())
    }

    @Test
    fun delete_of_a_foreign_conversation_id_rejects_and_does_not_delete() = runBlocking {
        val f = Fixture()
        val owningConversation = Uuid.random()
        val foreignConversation = Uuid.random()
        val created = f.repository.create(owningConversation, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
            as ScheduleMutationResult.Accepted

        // Deleting through a DIFFERENT conversation must reject and leave the row intact.
        val result = f.repository.delete(foreignConversation, created.snapshot.id)

        assertTrue(result is ScheduleMutationResult.Rejected)
        assertNotNull("foreign delete must not remove the row", f.dao.getById(created.snapshot.id.toString()))
    }

    @Test
    fun delete_of_an_unknown_id_rejects() = runBlocking {
        val f = Fixture()
        val result = f.repository.delete(Uuid.random(), Uuid.random())
        assertTrue(result is ScheduleMutationResult.Rejected)
    }

    // --- setEnabled pause/resume (SPEC.md M5 / task T10) -----------------------------------------

    @Test
    fun pause_disables_the_row_and_cancels_its_pending_fire() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        val created = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
            as ScheduleMutationResult.Accepted
        // Discard the create-path enqueue bookkeeping; we assert only the seams the pause itself fires.
        f.enqueued.clear()
        f.cancelled.clear()

        val result = f.repository.setEnabled(conversationId, created.snapshot.id, enabled = false)

        assertTrue("expected Accepted, got $result", result is ScheduleMutationResult.Accepted)
        assertTrue("pause must disable the row", !f.dao.getById(created.snapshot.id.toString())!!.enabled)
        // pause cancels the WorkManager fire via onScheduleDeleted (the same cancel seam delete uses).
        assertEquals(listOf(created.snapshot.id), f.cancelled)
        assertTrue("pause must not enqueue a fire", f.enqueued.isEmpty())
    }

    @Test
    fun resume_within_cap_re_enables_and_re_arms_the_fire() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        val created = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
            as ScheduleMutationResult.Accepted
        f.repository.setEnabled(conversationId, created.snapshot.id, enabled = false)
        f.enqueued.clear()
        f.cancelled.clear()

        val result = f.repository.setEnabled(conversationId, created.snapshot.id, enabled = true)

        assertTrue("expected Accepted, got $result", result is ScheduleMutationResult.Accepted)
        val row = f.dao.getById(created.snapshot.id.toString())!!
        assertTrue("resume must re-enable the row", row.enabled)
        // resume re-arms the next fire via onScheduleCreated (same enqueue seam create uses), at the
        // schedule's nextFireAt.
        assertEquals(listOf(created.snapshot.id to row.nextFireAt), f.enqueued)
        assertTrue("resume must not cancel", f.cancelled.isEmpty())
    }

    @Test
    fun resume_that_would_breach_the_per_conversation_cap_rejects_and_stays_disabled() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        // Create one schedule, then pause it (freeing a quota slot).
        val paused = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
            as ScheduleMutationResult.Accepted
        f.repository.setEnabled(conversationId, paused.snapshot.id, enabled = false)
        // Fill the per-conversation cap with OTHER enabled schedules; the freed slot is now taken.
        repeat(TaskScheduleRepository.MAX_ACTIVE_PER_CONVERSATION) {
            val r = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
            assertTrue("cap fill $it should be accepted", r is ScheduleMutationResult.Accepted)
        }
        f.enqueued.clear()

        // Resuming the paused row would push enabled count to cap+1 — must reject.
        val result = f.repository.setEnabled(conversationId, paused.snapshot.id, enabled = true)

        assertTrue("expected Rejected, got $result", result is ScheduleMutationResult.Rejected)
        assertTrue(
            "a rejected resume must leave the row disabled",
            !f.dao.getById(paused.snapshot.id.toString())!!.enabled,
        )
        assertTrue("a rejected resume must not enqueue a fire", f.enqueued.isEmpty())
    }

    @Test
    fun resume_that_would_breach_the_per_owner_cap_rejects_and_stays_disabled() = runBlocking {
        val f = Fixture()
        val pausedConversation = Uuid.random()
        val paused = f.repository.create(pausedConversation, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
            as ScheduleMutationResult.Accepted
        f.repository.setEnabled(pausedConversation, paused.snapshot.id, enabled = false)
        // Fill the per-owner cap with enabled USER schedules spread across conversations so the
        // per-conversation cap never trips first.
        var created = 0
        while (created < TaskScheduleRepository.MAX_ACTIVE_PER_USER) {
            val c = Uuid.random()
            repeat(TaskScheduleRepository.MAX_ACTIVE_PER_CONVERSATION) {
                if (created < TaskScheduleRepository.MAX_ACTIVE_PER_USER) {
                    val r = f.repository.create(c, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
                    assertTrue(r is ScheduleMutationResult.Accepted)
                    created++
                }
            }
        }
        f.enqueued.clear()

        val result = f.repository.setEnabled(pausedConversation, paused.snapshot.id, enabled = true)

        assertTrue("expected Rejected, got $result", result is ScheduleMutationResult.Rejected)
        assertTrue(!f.dao.getById(paused.snapshot.id.toString())!!.enabled)
        assertTrue(f.enqueued.isEmpty())
    }

    @Test
    fun setEnabled_through_a_foreign_conversation_id_rejects() = runBlocking {
        val f = Fixture()
        val owningConversation = Uuid.random()
        val foreignConversation = Uuid.random()
        val created = f.repository.create(owningConversation, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
            as ScheduleMutationResult.Accepted
        f.cancelled.clear()
        f.enqueued.clear()

        // Toggling through a DIFFERENT conversation must reject and touch nothing — the scope check
        // is what stops a UI/agent bound to one conversation from pausing another's schedule.
        val result = f.repository.setEnabled(foreignConversation, created.snapshot.id, enabled = false)

        assertTrue("expected Rejected, got $result", result is ScheduleMutationResult.Rejected)
        assertTrue("foreign toggle must leave the row enabled", f.dao.getById(created.snapshot.id.toString())!!.enabled)
        assertTrue("foreign toggle must not fire either seam", f.cancelled.isEmpty() && f.enqueued.isEmpty())
    }

    @Test
    fun setEnabled_of_an_unknown_id_rejects() = runBlocking {
        val f = Fixture()
        val result = f.repository.setEnabled(Uuid.random(), Uuid.random(), enabled = false)
        assertTrue(result is ScheduleMutationResult.Rejected)
    }

    @Test
    fun resume_of_a_fired_one_shot_rejects_and_does_not_re_arm() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        val created = f.repository.create(
            conversationId,
            ScheduleOwner.USER,
            oneShotDraft(f.spawnable.id, firstFireAt = 10_000L),
        ) as ScheduleMutationResult.Accepted
        // Fire the one-shot: claimDue stamps lastFiredAt and flips enabled=false (terminal). Its
        // nextFireAt stays at the original due time, so a naive resume would let claimDue
        // (next_fire_at <= now) re-fire the same one-shot, duplicating the user's action.
        val claim = f.repository.claimDue(created.snapshot.id, now = 20_000L)
        assertNotNull("the one-shot must fire", claim)
        f.repository.finishRun(created.snapshot.id, claim!!.runId, Uuid.random())
        f.enqueued.clear()
        f.cancelled.clear()

        // Turning a completed one-shot back on must be rejected — it is terminally done.
        val result = f.repository.setEnabled(conversationId, created.snapshot.id, enabled = true)

        assertTrue("expected Rejected, got $result", result is ScheduleMutationResult.Rejected)
        assertTrue(
            "a fired one-shot must stay disabled",
            !f.dao.getById(created.snapshot.id.toString())!!.enabled,
        )
        assertTrue("a rejected resume must not re-arm a fire", f.enqueued.isEmpty())
    }

    @Test
    fun resume_of_an_unfired_paused_one_shot_is_accepted() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        val created = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
            as ScheduleMutationResult.Accepted
        // Pause BEFORE it ever fires (lastFiredAt stays null): this is a legitimately resumable row,
        // distinct from a terminal fired one-shot.
        f.repository.setEnabled(conversationId, created.snapshot.id, enabled = false)
        f.enqueued.clear()
        f.cancelled.clear()

        val result = f.repository.setEnabled(conversationId, created.snapshot.id, enabled = true)

        assertTrue("expected Accepted, got $result", result is ScheduleMutationResult.Accepted)
        val row = f.dao.getById(created.snapshot.id.toString())!!
        assertTrue("an unfired paused one-shot resumes", row.enabled)
        assertEquals(listOf(created.snapshot.id to row.nextFireAt), f.enqueued)
    }

    @Test
    fun setEnabled_to_the_same_state_is_idempotent_and_does_not_double_fire_a_seam() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        val created = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
            as ScheduleMutationResult.Accepted
        f.enqueued.clear()
        f.cancelled.clear()

        // The row is already enabled; enabling again must not re-arm a duplicate fire.
        val result = f.repository.setEnabled(conversationId, created.snapshot.id, enabled = true)

        assertTrue("expected Accepted, got $result", result is ScheduleMutationResult.Accepted)
        assertTrue("enabling an already-enabled row must not re-enqueue", f.enqueued.isEmpty())
        assertTrue(f.cancelled.isEmpty())
        assertTrue(f.dao.getById(created.snapshot.id.toString())!!.enabled)
    }

    // --- claimDue / finishRun (atomic, SPEC.md M3 / task T5) -------------------------------------

    /** Create one schedule and return its persisted entity for direct claim-path manipulation. */
    private suspend fun Fixture.seedOneShot(firstFireAt: Long): Uuid {
        val conversationId = Uuid.random()
        val created = repository.create(
            conversationId,
            ScheduleOwner.USER,
            oneShotDraft(spawnable.id, firstFireAt = firstFireAt),
        ) as ScheduleMutationResult.Accepted
        return created.snapshot.id
    }

    private suspend fun Fixture.seedRecurring(
        firstFireAt: Long,
        every: Int,
        unit: RecurrenceUnit,
    ): Uuid {
        val conversationId = Uuid.random()
        val draft = ScheduleDraft(
            targetAssistantId = spawnable.id,
            prompt = "morning briefing",
            kind = ScheduleKind.RECURRING,
            firstFireAt = firstFireAt,
            timeZoneId = zone,
            recurrenceSpec = Json.encodeToString(RecurrenceSpec(every = every, unit = unit)),
        )
        val created = repository.create(conversationId, ScheduleOwner.USER, draft)
            as ScheduleMutationResult.Accepted
        return created.snapshot.id
    }

    @Test
    fun two_concurrent_claims_yield_exactly_one_winner() = runBlocking {
        val f = Fixture()
        val id = f.seedOneShot(firstFireAt = 10_000L)
        val now = 20_000L

        // Two workers race for the same due window. The win is reported DIRECTLY by claimDue, so
        // exactly one returns a ScheduleClaim and the other null — never two claims for one window.
        val first = f.repository.claimDue(id, now)
        val second = f.repository.claimDue(id, now)

        val winners = listOfNotNull(first, second)
        assertEquals("exactly one claim must win the window", 1, winners.size)
        assertNotNull(winners.single().runId)
        assertEquals(id, winners.single().snapshot.id)
    }

    @Test
    fun claimDue_returns_null_when_not_yet_due() = runBlocking {
        val f = Fixture()
        val id = f.seedOneShot(firstFireAt = 50_000L)
        // now < nextFireAt ⇒ not due ⇒ no claim, row untouched.
        assertNull(f.repository.claimDue(id, now = 49_999L))
        val row = f.dao.getById(id.toString())!!
        assertNull(row.runningTaskRunId)
        assertNull(row.lastFiredAt)
        assertTrue(row.enabled)
    }

    @Test
    fun claimDue_returns_null_for_an_unknown_schedule() = runBlocking {
        val f = Fixture()
        assertNull(f.repository.claimDue(Uuid.random(), now = 99_999L))
    }

    @Test
    fun claiming_a_one_shot_disables_it_and_stamps_the_fire() = runBlocking {
        val f = Fixture()
        val id = f.seedOneShot(firstFireAt = 10_000L)
        val now = 12_345L

        val claim = f.repository.claimDue(id, now)

        assertNotNull("a due one-shot must be claimable", claim)
        val row = f.dao.getById(id.toString())!!
        assertTrue("one-shot is disabled after it fires", !row.enabled)
        assertEquals("lastFiredAt is stamped with now", now, row.lastFiredAt)
        assertEquals(
            "the claim's runId is pinned as runningTaskRunId",
            claim!!.runId.toString(),
            row.runningTaskRunId,
        )
    }

    @Test
    fun claiming_a_recurring_advances_next_fire_to_a_future_occurrence() = runBlocking {
        val f = Fixture()
        // Hourly schedule anchored at 0; firing late at now=10h must advance to 11h (strictly future),
        // not replay each missed hour.
        val firstFireAt = 0L
        val hour = 60L * 60 * 1000
        val id = f.seedRecurring(firstFireAt = firstFireAt, every = 1, unit = RecurrenceUnit.HOURS)
        val now = 10 * hour

        val claim = f.repository.claimDue(id, now)

        assertNotNull(claim)
        val row = f.dao.getById(id.toString())!!
        assertTrue("a recurring schedule stays enabled after a fire", row.enabled)
        assertTrue("nextFireAt advances strictly past now", row.nextFireAt > now)
        // Coalesced: exactly ONE forward step to the first future hour boundary (11h), not N catch-ups.
        assertEquals(11 * hour, row.nextFireAt)
        assertEquals(now, row.lastFiredAt)
        assertEquals(claim!!.runId.toString(), row.runningTaskRunId)
    }

    @Test
    fun a_claimed_schedule_with_running_run_cannot_be_reclaimed() = runBlocking {
        val f = Fixture()
        // A recurring schedule keeps a future nextFireAt and enabled=true after the first claim, so the
        // only thing stopping an immediate second claim is the runningTaskRunId guard.
        val hour = 60L * 60 * 1000
        val id = f.seedRecurring(firstFireAt = 0L, every = 1, unit = RecurrenceUnit.HOURS)
        val firstClaim = f.repository.claimDue(id, now = 10 * hour)
        assertNotNull(firstClaim)
        val advancedNextFire = f.dao.getById(id.toString())!!.nextFireAt

        // Even at a now past the advanced nextFireAt, the in-flight run blocks a re-claim.
        val second = f.repository.claimDue(id, now = advancedNextFire + 1)
        assertNull("a schedule with a running run must not be re-claimed", second)
    }

    @Test
    fun finishRun_clears_running_id_and_records_last_task_run_id() = runBlocking {
        val f = Fixture()
        val id = f.seedOneShot(firstFireAt = 10_000L)
        val claim = f.repository.claimDue(id, now = 12_000L)!!
        val terminalRunId = Uuid.random()

        f.repository.finishRun(id, claim.runId, terminalRunId)

        val row = f.dao.getById(id.toString())!!
        assertNull("finishRun clears running_task_run_id", row.runningTaskRunId)
        assertEquals("finishRun records last_task_run_id", terminalRunId.toString(), row.lastTaskRunId)
    }

    @Test
    fun finishRun_for_an_unknown_schedule_is_a_no_op() = runBlocking {
        val f = Fixture()
        // No row, no crash — finishRun is abort-safe (a worker may finish after the row was deleted).
        f.repository.finishRun(Uuid.random(), Uuid.random(), Uuid.random())
    }

    // --- nextFireIfStillArmed (worker re-enqueue ground-truth, SPEC.md M5 / task T9) -------------

    @Test
    fun nextFireIfStillArmed_returns_the_fire_time_for_an_enabled_row() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        val created = f.repository.create(
            conversationId,
            ScheduleOwner.USER,
            oneShotDraft(f.spawnable.id, firstFireAt = 42_000L),
        ) as ScheduleMutationResult.Accepted

        assertEquals(42_000L, f.repository.nextFireIfStillArmed(created.snapshot.id))
    }

    @Test
    fun nextFireIfStillArmed_returns_null_for_a_paused_row() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        val created = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
            as ScheduleMutationResult.Accepted
        // A pause concurrent with a fire turns the row disabled; the worker must read null here so it
        // does NOT re-arm and silently undo the pause.
        f.repository.setEnabled(conversationId, created.snapshot.id, enabled = false)

        assertNull(f.repository.nextFireIfStillArmed(created.snapshot.id))
    }

    @Test
    fun nextFireIfStillArmed_returns_null_for_an_unknown_schedule() = runBlocking {
        val f = Fixture()
        assertNull(f.repository.nextFireIfStillArmed(Uuid.random()))
    }
}
