package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.task.TaskApprovalRequest
import me.rerere.ai.runtime.task.TaskBudgetCap
import me.rerere.ai.runtime.task.TaskBudgetUsage
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskSpec
import me.rerere.ai.runtime.task.TaskState
import me.rerere.rikkahub.data.db.entity.TaskRunEntity
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeTaskRunDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * Tests for [TaskRunRepository] (SPEC.md M2): task-run persistence with reducer-gated state
 * updates. Every state change is folded through the pure `:ai-runtime` [TaskStateReducer], so the
 * persisted `latest_state` column can NEVER hold a state TASK_STATE_LEGAL forbids:
 *
 * - create persists a `Created` row;
 * - a legal event advances the persisted state and mirrors its payload columns;
 * - an illegal event is a no-op (the row is untouched), terminals are absorbing, and replaying
 *   a terminal-producing event stream is idempotent;
 * - event summaries append with a strictly monotone sequence;
 * - budget counters only ever increase (component-wise max) and a cap breach is surfaced.
 */
class TaskRunRepositoryTest {

    private class Fixture(now: () -> Long = MutableClock()::current) {
        val clock = now
        val dao = FakeTaskRunDAO()
        val repository = TaskRunRepository(
            dao = dao,
            transactions = FakeBoardTransactions(),
            now = now,
        )
    }

    /** A monotone test clock so updated_at strictly advances per write. */
    private class MutableClock {
        private var t = 1_000L
        fun current(): Long = ++t
    }

    private fun spec(
        taskId: Uuid = Uuid.random(),
        conversationId: Uuid = Uuid.random(),
    ): TaskSpec = TaskSpec(
        taskId = taskId,
        parentConversationId = conversationId,
        parentToolCallId = "call_1",
        agentTypeId = "agent_1",
        prompt = "do the thing",
    )

    @Test
    fun create_persists_a_created_row() = runBlocking {
        val f = Fixture()
        val spec = spec()

        f.repository.create(spec)

        val row = f.dao.getById(spec.taskId.toString())
        assertNotNull(row)
        assertEquals(TaskRunStateTag.CREATED.name, row!!.latestState)
        assertEquals(spec.parentConversationId.toString(), row.conversationId)
        assertEquals(spec.parentToolCallId, row.parentToolCallId)
        assertEquals(spec.prompt, row.prompt)
        assertEquals(0L, row.eventSequence)
        assertEquals(TaskState.Created, f.repository.get(spec.taskId))
    }

    @Test
    fun legal_event_advances_persisted_state() = runBlocking {
        val f = Fixture()
        val spec = spec()
        f.repository.create(spec)

        val after = f.repository.applyEvent(spec.taskId, TaskEvent.Enqueued)

        assertEquals(TaskState.Queued, after)
        assertEquals(TaskRunStateTag.QUEUED.name, f.dao.getById(spec.taskId.toString())!!.latestState)
    }

    @Test
    fun illegal_event_is_a_no_op() = runBlocking {
        val f = Fixture()
        val spec = spec()
        f.repository.create(spec)
        val before = f.dao.getById(spec.taskId.toString())!!

        // Created only accepts Enqueued; SlotClaimed is illegal here.
        val after = f.repository.applyEvent(spec.taskId, TaskEvent.SlotClaimed)

        assertEquals(TaskState.Created, after)
        // The row is untouched — same state AND same updated_at (no spurious write).
        assertEquals(before, f.dao.getById(spec.taskId.toString()))
    }

    @Test
    fun terminal_is_absorbing_and_replay_is_idempotent() = runBlocking {
        val f = Fixture()
        val spec = spec()
        f.repository.create(spec)
        f.repository.applyEvent(spec.taskId, TaskEvent.Enqueued)
        f.repository.applyEvent(spec.taskId, TaskEvent.SlotClaimed)
        f.repository.applyEvent(spec.taskId, TaskEvent.ChildProgressed)
        f.repository.applyEvent(spec.taskId, TaskEvent.FinalResult("done"))

        assertEquals(TaskState.Succeeded("done"), f.repository.get(spec.taskId))
        val terminalRow = f.dao.getById(spec.taskId.toString())!!
        assertEquals(TaskRunStateTag.SUCCEEDED.name, terminalRow.latestState)
        assertEquals("done", terminalRow.finalResult)

        // Replay arbitrary events on the terminal: state and row never change (absorbing).
        f.repository.applyEvent(spec.taskId, TaskEvent.ExecutionFailed("late error"))
        f.repository.applyEvent(spec.taskId, TaskEvent.CancelRequested)
        f.repository.applyEvent(spec.taskId, TaskEvent.FinalResult("other"))

        assertEquals(TaskState.Succeeded("done"), f.repository.get(spec.taskId))
        assertEquals(terminalRow, f.dao.getById(spec.taskId.toString()))
    }

    @Test
    fun approval_payload_is_mirrored_into_the_pending_column() = runBlocking {
        val f = Fixture()
        val spec = spec()
        f.repository.create(spec)
        f.repository.applyEvent(spec.taskId, TaskEvent.Enqueued)
        f.repository.applyEvent(spec.taskId, TaskEvent.SlotClaimed)
        f.repository.applyEvent(spec.taskId, TaskEvent.ChildProgressed)

        val request = TaskApprovalRequest(childToolCallId = "child_call_7", toolName = "ask_user")
        val after = f.repository.applyEvent(spec.taskId, TaskEvent.ApprovalRequested(request))

        assertEquals(TaskState.WaitingApproval(request), after)
        val row = f.dao.getById(spec.taskId.toString())!!
        assertEquals(TaskRunStateTag.WAITING_APPROVAL.name, row.latestState)
        assertEquals(request, f.repository.get(spec.taskId).let { it as TaskState.WaitingApproval }.request)

        // Resolving the approval clears the pending column (the child resumes with the result).
        val resumed = f.repository.applyEvent(spec.taskId, TaskEvent.ApprovalResolved(approved = true))
        assertEquals(TaskState.Resuming, resumed)
        assertNull(f.dao.getById(spec.taskId.toString())!!.pendingApproval)
    }

    @Test
    fun apply_event_on_missing_run_returns_null() = runBlocking {
        val f = Fixture()
        assertNull(f.repository.applyEvent(Uuid.random(), TaskEvent.Enqueued))
    }

    @Test
    fun event_summaries_append_with_monotone_sequence() = runBlocking {
        val f = Fixture()
        val spec = spec()
        f.repository.create(spec)

        f.repository.appendEventSummary(spec.taskId, "started")
        f.repository.appendEventSummary(spec.taskId, "step one")
        f.repository.appendEventSummary(spec.taskId, "step two")

        val row = f.dao.getById(spec.taskId.toString())!!
        val summaries = row.decodeEventSummaries()!!
        assertEquals(listOf("started", "step one", "step two"), summaries.map { it.summary })
        assertEquals(listOf(1L, 2L, 3L), summaries.map { it.sequence })
        // The row's monotone cursor matches the last appended sequence.
        assertEquals(3L, row.eventSequence)
        // Sequences are strictly increasing.
        assertTrue(summaries.zipWithNext().all { (a, b) -> b.sequence > a.sequence })
    }

    @Test
    fun budget_counters_are_monotone_and_breach_is_surfaced() = runBlocking {
        val f = Fixture()
        val spec = spec()
        f.repository.create(spec)

        val first = f.repository.recordUsage(spec.taskId, TaskBudgetUsage(steps = 10, tokens = 100, elapsed = 5.milliseconds))
        assertNull(first)
        // A stale, smaller report cannot lower the persisted counters (component-wise max).
        val stale = f.repository.recordUsage(spec.taskId, TaskBudgetUsage(steps = 3, tokens = 50, elapsed = 1.milliseconds))
        assertNull(stale)
        val row = f.dao.getById(spec.taskId.toString())!!
        assertEquals(10, row.usageSteps)
        assertEquals(100L, row.usageTokens)
        assertEquals(5L, row.usageElapsedMs)

        // Exceeding the default step cap (64) surfaces a Steps breach.
        val breach = f.repository.recordUsage(spec.taskId, TaskBudgetUsage(steps = 65, tokens = 100, elapsed = 5.milliseconds))
        assertNotNull(breach)
        assertEquals(TaskBudgetCap.Steps, breach!!.cap)
        assertEquals(65, f.dao.getById(spec.taskId.toString())!!.usageSteps)
    }

    @Test
    fun corrupt_summary_column_recovers_with_a_non_empty_progress_marker() = runBlocking {
        // A RUNNING row whose event_summaries blob is corrupt JSON. decodeEventSummaries() returns
        // null for it by design (corrupt != "no events yet"). Recovery must NOT collapse that null
        // to "" — an Interrupted run seeded from a falsely-empty summary resumes as if no progress
        // existed, risking duplicated side effects (review finding #4). The recovered progress
        // summary must be NON-BLANK so resume injects the recovery-context block.
        val f = Fixture()
        val taskId = Uuid.random()
        f.dao.upsert(
            TaskRunEntity(
                id = taskId.toString(),
                conversationId = Uuid.random().toString(),
                parentToolCallId = "call_1",
                agentTypeId = "agent_1",
                prompt = "do the thing",
                latestState = TaskRunStateTag.RUNNING.name,
                eventSummaries = "{not valid json",
                createdAt = 1L,
                updatedAt = 1L,
            )
        )

        val recovered = f.repository.recoverInterruptedRuns()

        assertEquals(listOf(taskId), recovered)
        val state = f.repository.get(taskId)
        assertTrue("a corrupt-summary row must still recover to Interrupted", state is TaskState.Interrupted)
        assertTrue(
            "corruption must not be seeded as empty progress",
            (state as TaskState.Interrupted).progressSummary.isNotBlank(),
        )
    }

    @Test
    fun interrupted_run_round_trips_through_the_entity() = runBlocking {
        val f = Fixture()
        val spec = spec()
        f.repository.create(spec)
        f.repository.applyEvent(spec.taskId, TaskEvent.Enqueued)
        f.repository.applyEvent(spec.taskId, TaskEvent.SlotClaimed)
        f.repository.applyEvent(spec.taskId, TaskEvent.ChildProgressed)

        val after = f.repository.applyEvent(spec.taskId, TaskEvent.ProcessInterrupted("paused mid-step"))
        assertEquals(TaskState.Interrupted("paused mid-step"), after)
        assertEquals(TaskRunStateTag.INTERRUPTED.name, f.dao.getById(spec.taskId.toString())!!.latestState)
        // Reconstructing the domain state from the persisted row preserves the summary.
        assertEquals(TaskState.Interrupted("paused mid-step"), f.repository.get(spec.taskId))

        // Resume is the only edge out — folds back to Resuming.
        val resumed = f.repository.applyEvent(spec.taskId, TaskEvent.ResumeRequested)
        assertEquals(TaskState.Resuming, resumed)
    }
}
