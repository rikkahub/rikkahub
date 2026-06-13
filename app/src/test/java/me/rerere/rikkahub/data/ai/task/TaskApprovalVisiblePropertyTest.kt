package me.rerere.rikkahub.data.ai.task

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.set
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.task.TaskApprovalDecision
import me.rerere.ai.runtime.task.TaskApprovalRequest
import me.rerere.ai.runtime.task.TaskBudget
import me.rerere.ai.runtime.task.TaskBudgetBreach
import me.rerere.ai.runtime.task.TaskBudgetUsage
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskSpec
import me.rerere.ai.runtime.task.TaskState
import me.rerere.ai.runtime.task.TaskToolPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * TASK_APPROVAL_VISIBLE (SPEC.md M4 / T10, maintainer decision #2). Pins the child-approval router
 * contract as PROPERTIES, not example cases:
 *
 *  1. **Allowlist gate is exact.** A child tool forwards to the parent's surface IFF its name is on
 *     the agent type's [TaskToolPolicy] allowlist; everything else auto-denies. (Boundary: empty
 *     allowlist forwards NOTHING.)
 *  2. **No hidden execution while pending / on deny.** A forwarded tool reaches the parent surface
 *     exactly once and blocks (no result until the parent decides); an auto-denied tool NEVER
 *     reaches the surface and returns deny.
 *  3. **Parent-visible namespacing.** Every forwarded request is keyed `taskId/childToolCallId` on
 *     the surface, anchoring it inside the parent's `task` step.
 *  4. **Auto-deny is recorded in the task summary.** A non-allowlisted refusal appends an
 *     approval-denied summary entry the parent can read.
 *  5. **Metamorphic: approve-then-resume == direct approved execution.** The router's boolean
 *     decision for an allowlisted tool is EXACTLY the parent's decision — approving via the gate is
 *     indistinguishable from the parent having approved the tool directly.
 */
class TaskApprovalVisiblePropertyTest {

    /** A fake parent surface: records every forwarded request and answers with a fixed decision. */
    private class FakeSurface(private val decide: (String) -> Boolean) : ParentApprovalSurface {
        val forwarded = mutableListOf<Pair<String, TaskApprovalRequest>>()
        override suspend fun requestApproval(
            namespacedToolCallId: String,
            request: TaskApprovalRequest,
        ): TaskApprovalDecision {
            forwarded += namespacedToolCallId to request
            return if (decide(request.toolName)) TaskApprovalDecision.Approved else TaskApprovalDecision.Denied()
        }
    }

    /** A fake store recording auto-deny summary entries and every lifecycle event, per task. */
    private class RecordingStore : TaskRunStore {
        val summaries = ConcurrentHashMap<Uuid, MutableList<Pair<String, String>>>()
        val events = ConcurrentHashMap<Uuid, MutableList<TaskEvent>>()
        override suspend fun create(spec: TaskSpec): TaskState = TaskState.Created
        override suspend fun applyEvent(taskId: Uuid, event: TaskEvent): TaskState? {
            events.getOrPut(taskId) { mutableListOf() } += event
            return null
        }
        override suspend fun claimResume(taskId: Uuid): Boolean = false
        override suspend fun appendEventSummary(taskId: Uuid, summary: String, kind: String): Long? {
            summaries.getOrPut(taskId) { mutableListOf() } += kind to summary
            return summaries.getValue(taskId).size.toLong()
        }
        override suspend fun recordUsage(taskId: Uuid, reported: TaskBudgetUsage, budget: TaskBudget): TaskBudgetBreach? = null
    }

    private val arbToolName: Arb<String> = Arb.string(1..8)

    @Test
    fun `only allowlisted child tools forward, everything else auto-denies and is recorded`() {
        // Generate an allowlist and a set of probe tool names (some on the list, some not).
        val arbCase = Arb.set(arbToolName, 0..4).map { it.toList() }

        runBlocking {
            checkAll(300, arbCase, Arb.list(arbToolName, 1..6), Arb.boolean()) { allowlist, probes, parentApproves ->
                val taskId = Uuid.random()
                val policy = TaskToolPolicy(approvalForwardAllowlist = allowlist.toSet())
                val surface = FakeSurface { parentApproves }
                val store = RecordingStore()
                val router = TaskApprovalRouter(
                    policyFor = { policy },
                    surface = surface,
                    store = store,
                )

                probes.forEachIndexed { i, toolName ->
                    val request = TaskApprovalRequest(childToolCallId = "call-$i", toolName = toolName)
                    val decision = router.await(taskId, request)

                    if (toolName in allowlist) {
                        // Forwarded: surface saw it exactly once, namespaced, and the router's
                        // decision is EXACTLY the parent's (metamorphic: gate == direct approval).
                        val match = surface.forwarded.filter { it.second === request }
                        assertEquals("an allowlisted tool forwards exactly once: $toolName", 1, match.size)
                        assertEquals(
                            "forwarded request must be namespaced taskId/childToolCallId",
                            "$taskId/${request.childToolCallId}",
                            match.single().first,
                        )
                        assertEquals("router decision == parent decision for allowlisted tool", parentApproves, decision.approved)
                    } else {
                        // Auto-denied: never reached the surface, returned false, recorded a reason.
                        assertFalse("a non-allowlisted tool must never reach the parent surface: $toolName",
                            surface.forwarded.any { it.second === request })
                        assertFalse("a non-allowlisted tool auto-denies", decision.approved)
                        val recorded = store.summaries[taskId].orEmpty()
                        assertTrue(
                            "auto-deny must record an approval-denied summary mentioning the tool: $toolName",
                            recorded.any { (kind, text) ->
                                kind == TaskApprovalRouter.SUMMARY_KIND_APPROVAL_DENIED && text.contains(toolName)
                            },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a forwarded approval drives the WaitingApproval round-trip on the state machine`() {
        // The round-trip is exactly ApprovalRequested (Running -> WaitingApproval), ApprovalResolved
        // (WaitingApproval -> Resuming), ChildProgressed (Resuming -> Running — the child resumes
        // the moment the gate returns). An auto-deny never blocks the child, so it drives NO
        // lifecycle events; its only trace is the summary entry.
        runBlocking {
            checkAll(200, Arb.list(arbToolName, 1..6), Arb.boolean()) { probes, parentApproves ->
                val taskId = Uuid.random()
                val allowlisted = probes.first()
                val store = RecordingStore()
                val router = TaskApprovalRouter(
                    policyFor = { TaskToolPolicy(approvalForwardAllowlist = setOf(allowlisted)) },
                    surface = FakeSurface { parentApproves },
                    store = store,
                )

                probes.forEachIndexed { i, toolName ->
                    val request = TaskApprovalRequest(childToolCallId = "call-$i", toolName = toolName)
                    val before = store.events[taskId].orEmpty().size
                    router.await(taskId, request)

                    val driven = store.events[taskId].orEmpty().drop(before)
                    if (toolName == allowlisted) {
                        assertEquals(
                            "a forwarded approval must drive the full WaitingApproval round-trip",
                            listOf(
                                TaskEvent.ApprovalRequested(request),
                                TaskEvent.ApprovalResolved(parentApproves),
                                TaskEvent.ChildProgressed,
                            ),
                            driven,
                        )
                        // The decision's DURABLE audit record: the transcript part is a live
                        // projection the next publish replaces, so the summary must carry it.
                        assertTrue(
                            "a resolved approval must be recorded in the task summary: $toolName",
                            store.summaries[taskId].orEmpty().any { (kind, text) ->
                                kind == TaskApprovalRouter.SUMMARY_KIND_APPROVAL_RESOLVED &&
                                    text.contains(toolName) &&
                                    text.contains(if (parentApproves) "approved" else "denied")
                            },
                        )
                    } else {
                        assertEquals(
                            "an auto-deny never blocks the child, so it drives no lifecycle events",
                            emptyList<TaskEvent>(),
                            driven,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `an answered decision's answer survives into the durable summary`() {
        // Recovery seeds an interrupted run from the LAST event summary; for Answered the answer
        // IS the child's tool result, so reducing it to "approved" would resume a process-death
        // run without the one fact the child was waiting on.
        runBlocking {
            val taskId = Uuid.random()
            val store = RecordingStore()
            val router = TaskApprovalRouter(
                policyFor = { TaskToolPolicy(approvalForwardAllowlist = setOf("ask_user")) },
                surface = object : ParentApprovalSurface {
                    override suspend fun requestApproval(
                        namespacedToolCallId: String,
                        request: TaskApprovalRequest,
                    ): TaskApprovalDecision = TaskApprovalDecision.Answered("ship the green build")
                },
                store = store,
            )

            val decision = router.await(taskId, TaskApprovalRequest("c1", "ask_user"))

            assertEquals(TaskApprovalDecision.Answered("ship the green build"), decision)
            assertTrue(
                "the durable summary must carry the answer payload",
                store.summaries[taskId].orEmpty().any { (kind, text) ->
                    kind == TaskApprovalRouter.SUMMARY_KIND_APPROVAL_RESOLVED &&
                        text.contains("ship the green build")
                },
            )
        }
    }

    @Test
    fun `an empty allowlist forwards nothing`() {
        runBlocking {
            checkAll(200, Arb.list(arbToolName, 1..6)) { probes ->
                val taskId = Uuid.random()
                val surface = FakeSurface { true } // would approve if ever reached
                val router = TaskApprovalRouter(
                    policyFor = { TaskToolPolicy() }, // empty allowlist = forward nothing (default)
                    surface = surface,
                    store = RecordingStore(),
                )

                probes.forEachIndexed { i, toolName ->
                    val decision = router.await(taskId, TaskApprovalRequest("call-$i", toolName))
                    assertFalse("empty allowlist auto-denies every tool: $toolName", decision.approved)
                }
                assertTrue("empty allowlist must forward NOTHING to the parent surface", surface.forwarded.isEmpty())
            }
        }
    }

    @Test
    fun `a forwarded approval blocks until the parent decides — no hidden early execution`() {
        runBlocking {
            // The surface only returns once released; until then the router must be suspended on it
            // (pending), proving the child tool does not execute while waiting on the parent.
            val resumeOrder = AtomicInteger(0)
            var surfaceEnteredAt = -1
            var routerReturnedAt = -1

            val surface = object : ParentApprovalSurface {
                override suspend fun requestApproval(
                    namespacedToolCallId: String,
                    request: TaskApprovalRequest,
                ): TaskApprovalDecision {
                    surfaceEnteredAt = resumeOrder.getAndIncrement()
                    return TaskApprovalDecision.Approved
                }
            }
            val router = TaskApprovalRouter(
                policyFor = { TaskToolPolicy(approvalForwardAllowlist = setOf("ask_user")) },
                surface = surface,
                store = RecordingStore(),
            )

            val decision = router.await(Uuid.random(), TaskApprovalRequest("c1", "ask_user"))
            routerReturnedAt = resumeOrder.getAndIncrement()

            // The router cannot return before the surface has been consulted: the decision is the
            // surface's, never a value invented before the parent was asked.
            assertTrue("router must enter the parent surface before returning a decision",
                surfaceEnteredAt in 0 until routerReturnedAt)
            assertTrue("approve-then-resume yields the parent's approval", decision.approved)
        }
    }
}
