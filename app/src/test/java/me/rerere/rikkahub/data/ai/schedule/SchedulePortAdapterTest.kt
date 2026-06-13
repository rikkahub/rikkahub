package me.rerere.rikkahub.data.ai.schedule

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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Tests for [SchedulePortAdapter] (SPEC.md M4 / task T7): the adapter is the ONLY place that knows
 * the conversation scope and the owner class — the neutral [me.rerere.ai.runtime.contract.TaskSchedulePort]
 * a tool is built on never sees either. Mirrors [me.rerere.rikkahub.data.ai.task.BoardPortAdapter]:
 * scope + owner are closed over here, so a schedule tool physically cannot reach another
 * conversation's schedules or author under the wrong owner.
 *
 * Backed by the REAL [TaskScheduleRepository] + a fake DAO (TDD: prefer the real collaborator over a
 * mock), so a passing test proves the adapter routes through the single legality path, not that it
 * called some method.
 */
class SchedulePortAdapterTest {

    private class MutableClock {
        private var t = 1_000L
        fun current(): Long = ++t
    }

    private class Fixture {
        val dao = FakeTaskScheduleDAO()
        val spawnable = Assistant(id = Uuid.random(), name = "agent", spawnable = true)
        val repository = TaskScheduleRepository(
            dao = dao,
            transactions = FakeBoardTransactions(),
            resolveAssistant = { id -> if (id == spawnable.id) spawnable else null },
            now = MutableClock()::current,
        )

        fun adapter(conversationId: Uuid) = SchedulePortAdapter(
            repository = repository,
            conversationId = conversationId,
            owner = ScheduleOwner.AGENT,
        )

        fun oneShotDraft() = ScheduleDraft(
            targetAssistantId = spawnable.id,
            prompt = "remind me",
            kind = ScheduleKind.ONE_SHOT,
            firstFireAt = 10_000L,
            timeZoneId = "UTC",
        )
    }

    @Test
    fun create_routes_to_the_repo_bound_to_the_supplied_conversation_id() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        val port = f.adapter(conversationId)

        val result = port.create(f.oneShotDraft())

        assertTrue("expected Accepted, got $result", result is ScheduleMutationResult.Accepted)
        // The schedule landed under the bound conversation: listing THAT conversation surfaces it.
        val listed = f.repository.list(conversationId)
        assertEquals(1, listed.size)
        assertEquals(
            (result as ScheduleMutationResult.Accepted).snapshot.id,
            listed.single().id,
        )
        // ...and no OTHER conversation can see it.
        assertTrue(f.repository.list(Uuid.random()).isEmpty())
    }

    @Test
    fun create_authors_under_the_bound_owner_class() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        val port = f.adapter(conversationId)

        val result = port.create(f.oneShotDraft())

        val snapshot = (result as ScheduleMutationResult.Accepted).snapshot
        assertEquals(ScheduleOwner.AGENT, snapshot.owner)
    }

    @Test
    fun delete_cannot_touch_another_conversations_schedule() = runBlocking {
        val f = Fixture()
        val conversationA = Uuid.random()
        val conversationB = Uuid.random()

        // A schedule created under conversation A...
        val created = f.adapter(conversationA).create(f.oneShotDraft())
        val scheduleId = (created as ScheduleMutationResult.Accepted).snapshot.id

        // ...cannot be deleted through a port bound to conversation B.
        val portB = f.adapter(conversationB)
        val rejected = portB.delete(scheduleId)

        assertTrue("cross-conversation delete must reject, got $rejected",
            rejected is ScheduleMutationResult.Rejected)
        // The schedule still exists under conversation A — it was NOT silently deleted.
        assertEquals(1, f.repository.list(conversationA).size)
    }

    @Test
    fun list_is_scoped_to_the_bound_conversation() = runBlocking {
        val f = Fixture()
        val conversationA = Uuid.random()
        val conversationB = Uuid.random()

        f.adapter(conversationA).create(f.oneShotDraft())

        assertEquals(1, f.adapter(conversationA).list().size)
        assertTrue(f.adapter(conversationB).list().isEmpty())
    }
}
