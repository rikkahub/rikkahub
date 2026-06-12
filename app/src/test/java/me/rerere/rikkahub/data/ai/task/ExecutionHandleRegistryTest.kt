package me.rerere.rikkahub.data.ai.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Tests for [ExecutionHandleRegistry] (SPEC.md M4): in-memory live subagent handles whose child
 * [Job]s are structural children of the parent generation job, so `stopGeneration`'s
 * `parentJob.cancel()` cascades to every registered handle (ChatService.kt:1822) and the
 * handle state machine `Created -> Running -> Completed | Failed | Stopped` only takes legal
 * edges. Handles are never persisted — this registry is the only home for the live coroutine.
 */
class ExecutionHandleRegistryTest {

    private fun registry() = ExecutionHandleRegistry()

    private fun spawn(
        registry: ExecutionHandleRegistry,
        parentJob: Job,
        conversationId: Uuid = Uuid.random(),
        assistantId: Uuid = Uuid.random(),
    ): ExecutionHandle = registry.register(
        conversationId = conversationId,
        assistantId = assistantId,
        parentJob = parentJob,
    )

    @Test
    fun register_creates_a_handle_in_created_state() = runBlocking {
        val registry = registry()
        val parentJob = Job()
        val conversationId = Uuid.random()
        val assistantId = Uuid.random()

        val handle = spawn(registry, parentJob, conversationId, assistantId)

        assertEquals(ExecutionHandleKind.Subagent, handle.kind)
        assertEquals(ExecutionHandleStatus.Created, handle.status)
        assertEquals(conversationId, handle.conversationId)
        assertEquals(assistantId, handle.assistantId)
        assertTrue(handle.workItemIds.isEmpty())
        assertEquals("", handle.output)
        assertTrue(handle.updatedAt >= handle.createdAt)
        parentJob.cancel()
    }

    @Test
    fun lookup_returns_the_registered_handle_and_null_for_unknown() = runBlocking {
        val registry = registry()
        val parentJob = Job()

        val handle = spawn(registry, parentJob)

        assertNotNull(registry.get(handle.id))
        assertEquals(handle.id, registry.get(handle.id)?.id)
        assertNull(registry.get(Uuid.random().toString()))
        parentJob.cancel()
    }

    @Test
    fun list_by_conversation_returns_only_that_conversations_handles() = runBlocking {
        val registry = registry()
        val parentJob = Job()
        val convA = Uuid.random()
        val convB = Uuid.random()

        val a1 = spawn(registry, parentJob, convA)
        val a2 = spawn(registry, parentJob, convA)
        spawn(registry, parentJob, convB)

        val forA = registry.listByConversation(convA).map { it.id }.toSet()
        assertEquals(setOf(a1.id, a2.id), forA)
        parentJob.cancel()
    }

    @Test
    fun handle_runs_through_created_running_completed() = runBlocking {
        val registry = registry()
        val parentJob = Job()
        val handle = spawn(registry, parentJob)

        registry.markRunning(handle.id)
        assertEquals(ExecutionHandleStatus.Running, registry.get(handle.id)?.status)

        registry.appendOutput(handle.id, "partial ")
        registry.appendOutput(handle.id, "result")
        registry.markCompleted(handle.id, "final answer")

        val done = registry.get(handle.id)!!
        assertEquals(ExecutionHandleStatus.Completed, done.status)
        assertEquals("partial result", done.output)
        assertEquals("final answer", done.result)
        parentJob.cancel()
    }

    @Test
    fun running_can_fail() = runBlocking {
        val registry = registry()
        val parentJob = Job()
        val handle = spawn(registry, parentJob)

        registry.markRunning(handle.id)
        registry.markFailed(handle.id, "provider error")

        val failed = registry.get(handle.id)!!
        assertEquals(ExecutionHandleStatus.Failed, failed.status)
        assertEquals("provider error", failed.error)
        parentJob.cancel()
    }

    @Test
    fun terminal_status_is_absorbing_no_regression() = runBlocking {
        val registry = registry()
        val parentJob = Job()
        val handle = spawn(registry, parentJob)

        registry.markRunning(handle.id)
        registry.markCompleted(handle.id, "done")
        // Illegal edges off a terminal are no-ops; the registry never regresses Completed.
        registry.markRunning(handle.id)
        registry.markFailed(handle.id, "late error")
        registry.appendOutput(handle.id, "late output")

        val stuck = registry.get(handle.id)!!
        assertEquals(ExecutionHandleStatus.Completed, stuck.status)
        assertEquals("done", stuck.result)
        assertEquals("", stuck.output)
        parentJob.cancel()
    }

    @Test
    fun running_cannot_skip_back_to_created_or_jump_from_created_to_completed() = runBlocking {
        val registry = registry()
        val parentJob = Job()
        val handle = spawn(registry, parentJob)

        // Created -> Completed is illegal: must pass through Running first.
        registry.markCompleted(handle.id, "skipped")
        assertEquals(ExecutionHandleStatus.Created, registry.get(handle.id)?.status)
        assertNull(registry.get(handle.id)?.result)
        parentJob.cancel()
    }

    @Test
    fun child_job_is_a_child_of_the_parent_generation_job() = runBlocking {
        val registry = registry()
        val parentJob = Job()
        val handle = spawn(registry, parentJob)

        // Structural parenting: the handle's job is in the parent's children set, so the parent
        // job's cancellation cascades to it without the registry tracking it explicitly.
        assertTrue(parentJob.children.contains(handle.job))
        parentJob.cancel()
    }

    @Test
    fun stop_cancels_the_handle_job_and_marks_stopped() = runBlocking {
        val registry = registry()
        val parentJob = Job()
        val handle = spawn(registry, parentJob)
        registry.markRunning(handle.id)

        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        // Launch on a scope rooted at the handle's job, so the work is a structural descendant of
        // the handle job (and therefore of the parent generation job).
        CoroutineScope(handle.job).launch {
            started.complete(Unit)
            never.await() // would hang forever unless cancelled
        }
        started.await()

        registry.stop(handle.id)

        assertEquals(ExecutionHandleStatus.Stopped, registry.get(handle.id)?.status)
        assertFalse(handle.job.isActive)
        parentJob.cancel()
    }

    @Test
    fun cancelling_the_parent_job_stops_all_registered_handles() = runBlocking {
        val registry = registry()
        val parentJob = Job()
        val conversationId = Uuid.random()
        val h1 = spawn(registry, parentJob, conversationId)
        val h2 = spawn(registry, parentJob, conversationId)
        registry.markRunning(h1.id)
        registry.markRunning(h2.id)

        // Each handle runs a coroutine that would hang; cancelling the parent must tear them down.
        val running = listOf(h1, h2).map { handle ->
            val started = CompletableDeferred<Unit>()
            CoroutineScope(handle.job).launch {
                started.complete(Unit)
                delay(Long.MAX_VALUE)
            }
            started.await()
            handle
        }

        parentJob.cancel()
        parentJob.children.forEach { runCatching { it.join() } }

        running.forEach { assertFalse("handle ${it.id} job still active", it.job.isActive) }
    }

    @Test
    fun attaching_a_work_item_id_records_it_on_the_handle() = runBlocking {
        val registry = registry()
        val parentJob = Job()
        val handle = spawn(registry, parentJob)
        val item1 = Uuid.random()
        val item2 = Uuid.random()

        registry.attachWorkItem(handle.id, item1)
        registry.attachWorkItem(handle.id, item2)
        registry.attachWorkItem(handle.id, item1) // idempotent

        assertEquals(setOf(item1, item2), registry.get(handle.id)?.workItemIds)
        parentJob.cancel()
    }

    @Test
    fun unregister_removes_the_handle_from_lookup() = runBlocking {
        val registry = registry()
        val parentJob = Job()
        val handle = spawn(registry, parentJob)

        registry.unregister(handle.id)

        assertNull(registry.get(handle.id))
        assertTrue(registry.listByConversation(handle.conversationId).isEmpty())
        parentJob.cancel()
    }
}
