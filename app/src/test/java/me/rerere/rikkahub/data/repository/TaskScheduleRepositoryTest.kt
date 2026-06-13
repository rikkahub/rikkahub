package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.schedule.RecurrenceSpec
import me.rerere.ai.runtime.schedule.RecurrenceUnit
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
        val repository = TaskScheduleRepository(
            dao = dao,
            transactions = FakeBoardTransactions(),
            resolveAssistant = { id -> registry[id] },
            now = MutableClock()::current,
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

    @Test
    fun create_rejects_a_sub_minimum_recurring_interval() = runBlocking {
        val f = Fixture()
        // 1 minute < 15-minute floor.
        val result = f.repository.create(
            Uuid.random(),
            ScheduleOwner.USER,
            recurringDraft(f.spawnable.id, every = 1, unit = RecurrenceUnit.MINUTES),
        )
        assertTrue(result is ScheduleMutationResult.Rejected)
    }

    @Test
    fun create_accepts_a_recurring_interval_at_the_minimum() = runBlocking {
        val f = Fixture()
        // Exactly the 15-minute floor is allowed (boundary).
        val result = f.repository.create(
            Uuid.random(),
            ScheduleOwner.USER,
            recurringDraft(f.spawnable.id, every = 15, unit = RecurrenceUnit.MINUTES),
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
        // Recurrence.nextDailyOccurrence calls LocalTime.parse(spec.timeOfDay); a bad "HH:mm" must
        // be rejected upfront, not crash the worker every fire.
        val draft = recurringDraft(f.spawnable.id, every = 1, unit = RecurrenceUnit.DAYS).copy(
            recurrenceSpec = Json.encodeToString(
                RecurrenceSpec(every = 1, unit = RecurrenceUnit.DAYS, timeOfDay = "25:99"),
            ),
        )
        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, draft)
        assertTrue("expected Rejected, got $result", result is ScheduleMutationResult.Rejected)
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
}
