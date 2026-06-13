package me.rerere.ai.runtime.schedule

import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.contract.ScheduleSnapshot
import me.rerere.ai.runtime.contract.TaskSchedulePort
import me.rerere.ai.runtime.contract.MisfirePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Pins the neutral schedule contract (SPEC.md M1 / task T1): the port and its draft/snapshot/
 * result types carry NO platform Context and NO conversation id (the composition root binds
 * scope + owner, mirroring [me.rerere.ai.runtime.contract.TaskBoardPort]). A rejected mutation is
 * an EXPECTED domain outcome surfaced to the caller, never an exception that aborts a chat turn.
 */
class SchedulePortsContractTest {

    private val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val scheduleId = Uuid.parse("00000000-0000-0000-0000-0000000000aa")

    private fun snapshot(id: Uuid = scheduleId) = ScheduleSnapshot(
        id = id,
        targetAssistantId = assistantId,
        prompt = "morning briefing",
        owner = ScheduleOwner.AGENT,
        kind = ScheduleKind.RECURRING,
        firstFireAt = 1_000L,
        nextFireAt = 1_000L,
        timeZoneId = "UTC",
        recurrenceSpec = """{"every":1,"unit":"DAYS"}""",
        misfirePolicy = MisfirePolicy.FIRE_ONCE_AND_COALESCE,
        enabled = true,
        lastFiredAt = null,
        lastTaskRunId = null,
        runningTaskRunId = null,
    )

    /** A fake port proving the contract is implementable with no conversation id leaking in. */
    private class FakePort(private val onCreate: ScheduleMutationResult) : TaskSchedulePort {
        val created = mutableListOf<ScheduleDraft>()
        val deleted = mutableListOf<Uuid>()
        var listResult: List<ScheduleSnapshot> = emptyList()

        override suspend fun create(draft: ScheduleDraft): ScheduleMutationResult {
            created += draft
            return onCreate
        }

        override suspend fun list(): List<ScheduleSnapshot> = listResult

        override suspend fun delete(id: Uuid): ScheduleMutationResult {
            deleted += id
            return ScheduleMutationResult.Rejected("schedule not found")
        }
    }

    @Test
    fun draft_defaults_misfire_policy_to_coalesce_and_recurrence_spec_to_null() {
        val draft = ScheduleDraft(
            targetAssistantId = assistantId,
            prompt = "remind me",
            kind = ScheduleKind.ONE_SHOT,
            firstFireAt = 42L,
            timeZoneId = "UTC",
        )

        assertNull(draft.recurrenceSpec)
        assertEquals(MisfirePolicy.FIRE_ONCE_AND_COALESCE, draft.misfirePolicy)
    }

    @Test
    fun schedule_kind_and_owner_expose_exactly_the_v1_variants() {
        assertEquals(listOf("ONE_SHOT", "RECURRING"), ScheduleKind.entries.map { it.name })
        assertEquals(listOf("USER", "AGENT"), ScheduleOwner.entries.map { it.name })
    }

    @Test
    fun accepted_carries_the_resulting_snapshot() {
        val accepted = ScheduleMutationResult.Accepted(snapshot())
        assertEquals(scheduleId, accepted.snapshot.id)
    }

    @Test
    fun rejected_carries_a_caller_surfaceable_reason_and_is_not_a_throwable() {
        val rejected: ScheduleMutationResult = ScheduleMutationResult.Rejected("over per-conversation cap")
        assertTrue(rejected is ScheduleMutationResult.Rejected)
        assertEquals("over per-conversation cap", (rejected as ScheduleMutationResult.Rejected).reason)
    }

    @Test
    fun port_round_trips_create_list_delete_without_any_conversation_id() = runBlocking {
        val port = FakePort(onCreate = ScheduleMutationResult.Accepted(snapshot()))
        port.listResult = listOf(snapshot())

        val draft = ScheduleDraft(
            targetAssistantId = assistantId,
            prompt = "nightly digest",
            kind = ScheduleKind.RECURRING,
            firstFireAt = 100L,
            timeZoneId = "UTC",
            recurrenceSpec = """{"every":1,"unit":"DAYS"}""",
        )

        val createResult = port.create(draft)
        val listResult = port.list()
        val deleteResult = port.delete(scheduleId)

        assertTrue(createResult is ScheduleMutationResult.Accepted)
        assertEquals(listOf(draft), port.created)
        assertEquals(1, listResult.size)
        assertEquals(scheduleId, listResult.single().id)
        assertEquals(listOf(scheduleId), port.deleted)
        assertTrue(deleteResult is ScheduleMutationResult.Rejected)
    }
}
