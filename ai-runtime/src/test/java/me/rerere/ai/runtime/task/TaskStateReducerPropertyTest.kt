package me.rerere.ai.runtime.task

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds

/**
 * TASK_STATE_LEGAL (SPEC.md M1): the pure task-lifecycle reducer only ever takes legal
 * transitions, terminal states are absorbing (replaying any event stream on a terminal is
 * idempotent), and `Interrupted -> Resuming` and `Interrupted -> Cancelled` are the only
 * state exits from `Interrupted`
 * (maintainer decisions #1/#3 — resume is explicit, never automatic).
 */
class TaskStateReducerPropertyTest {

    private val arbText: Arb<String> = Arb.string(0..16, Codepoint.alphanumeric())

    private val arbRequest: Arb<TaskApprovalRequest> = Arb.bind(
        Arb.string(1..12, Codepoint.alphanumeric()),
        Arb.string(1..12, Codepoint.alphanumeric()),
    ) { callId, toolName -> TaskApprovalRequest(childToolCallId = callId, toolName = toolName) }

    private val arbUsage: Arb<TaskBudgetUsage> = Arb.bind(
        Arb.int(0..1_000),
        Arb.long(0L..1_000_000L),
        Arb.long(0L..3_600_000L).map { it.milliseconds },
    ) { steps, tokens, elapsed -> TaskBudgetUsage(steps = steps, tokens = tokens, elapsed = elapsed) }

    private val arbBreach: Arb<TaskBudgetBreach> = Arb.bind(
        Arb.enum<TaskBudgetCap>(),
        arbUsage,
    ) { cap, usage -> TaskBudgetBreach(cap = cap, usage = usage) }

    private val arbEvent: Arb<TaskEvent> = Arb.choice(
        Arb.constant(TaskEvent.Enqueued),
        Arb.constant(TaskEvent.SlotClaimed),
        Arb.constant(TaskEvent.ChildProgressed),
        arbRequest.map { TaskEvent.ApprovalRequested(it) },
        Arb.boolean().map { TaskEvent.ApprovalResolved(approved = it) },
        arbText.map { TaskEvent.FinalResult(summary = it) },
        arbText.map { TaskEvent.ExecutionFailed(error = it) },
        Arb.constant(TaskEvent.CancelRequested),
        arbBreach.map { TaskEvent.BudgetExceeded(it) },
        arbText.map { TaskEvent.ProcessInterrupted(progressSummary = it) },
        Arb.constant(TaskEvent.ResumeRequested),
    )

    private val arbActiveState: Arb<TaskState> = Arb.choice(
        Arb.constant(TaskState.Created),
        Arb.constant(TaskState.Queued),
        Arb.constant(TaskState.Starting),
        Arb.constant(TaskState.Running),
        arbRequest.map { TaskState.WaitingApproval(it) },
        Arb.constant(TaskState.Resuming),
    )

    private val arbTerminalState: Arb<TaskState> = Arb.choice(
        arbText.map { TaskState.Succeeded(summary = it) },
        arbText.map { TaskState.Failed(error = it) },
        Arb.constant(TaskState.Cancelled),
        arbBreach.map { TaskState.BudgetExhausted(it) },
    )

    private val arbState: Arb<TaskState> = Arb.choice(
        arbActiveState,
        arbTerminalState,
        arbText.map { TaskState.Interrupted(progressSummary = it) },
    )

    /**
     * The legal (from -> to) edge relation, transcribed independently from the SPEC.md state
     * machine. Self-loops (ignored events) are always legal; any other reachable pair must be
     * in this relation.
     */
    private val failureTargets: Set<KClass<out TaskState>> = setOf(
        TaskState.Failed::class,
        TaskState.Cancelled::class,
        TaskState.BudgetExhausted::class,
        TaskState.Interrupted::class,
    )

    private fun legalTargets(state: TaskState): Set<KClass<out TaskState>> = when (state) {
        TaskState.Created -> failureTargets + TaskState.Queued::class
        TaskState.Queued -> failureTargets + TaskState.Starting::class
        TaskState.Starting -> failureTargets + TaskState.Running::class + TaskState.Succeeded::class
        TaskState.Running -> failureTargets + TaskState.WaitingApproval::class + TaskState.Succeeded::class
        is TaskState.WaitingApproval -> failureTargets + TaskState.Resuming::class
        TaskState.Resuming -> failureTargets + TaskState.Running::class + TaskState.Succeeded::class
        is TaskState.Interrupted -> setOf(TaskState.Resuming::class, TaskState.Cancelled::class)
        is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled, is TaskState.BudgetExhausted -> emptySet()
    }

    @Test
    fun `every transition the reducer takes is in the legal edge relation`() {
        runBlocking {
            checkAll(2_000, arbState, arbEvent) { state, event ->
                val next = TaskStateReducer.reduce(state, event)
                if (next != state) {
                    assertTrue(
                        "illegal edge ${state::class.simpleName} -> ${next::class.simpleName} on $event",
                        next::class in legalTargets(state),
                    )
                }
            }
        }
    }

    @Test
    fun `terminal states absorb every event and replay is idempotent`() {
        runBlocking {
            checkAll(1_000, arbTerminalState, Arb.list(arbEvent, 0..8)) { terminal, events ->
                val replayed = events.fold(terminal) { state, event -> TaskStateReducer.reduce(state, event) }
                assertEquals(terminal, replayed)
            }
        }
    }

    @Test
    fun `Interrupted leaves only on resume or cancel requests`() {
        runBlocking {
            checkAll(1_000, arbText, arbEvent) { summary, event ->
                val interrupted = TaskState.Interrupted(progressSummary = summary)
                val next = TaskStateReducer.reduce(interrupted, event)
                if (event == TaskEvent.ResumeRequested) {
                    assertEquals(TaskState.Resuming, next)
                } else if (event == TaskEvent.CancelRequested) {
                    assertEquals(TaskState.Cancelled, next)
                } else {
                    assertEquals(interrupted, next)
                }
            }
        }
    }

    @Test
    fun `ResumeRequested is ignored in every non-Interrupted state`() {
        runBlocking {
            checkAll(500, Arb.choice(arbActiveState, arbTerminalState)) { state ->
                assertEquals(state, TaskStateReducer.reduce(state, TaskEvent.ResumeRequested))
            }
        }
    }

    @Test
    fun `failure events map every active state to the matching failure state with its payload`() {
        runBlocking {
            checkAll(1_000, arbActiveState, arbText, arbBreach) { state, text, breach ->
                assertEquals(
                    TaskState.Failed(error = text),
                    TaskStateReducer.reduce(state, TaskEvent.ExecutionFailed(error = text)),
                )
                assertEquals(
                    TaskState.Cancelled,
                    TaskStateReducer.reduce(state, TaskEvent.CancelRequested),
                )
                assertEquals(
                    TaskState.BudgetExhausted(breach),
                    TaskStateReducer.reduce(state, TaskEvent.BudgetExceeded(breach)),
                )
                assertEquals(
                    TaskState.Interrupted(progressSummary = text),
                    TaskStateReducer.reduce(state, TaskEvent.ProcessInterrupted(progressSummary = text)),
                )
            }
        }
    }

    @Test
    fun `happy path Created to Succeeded carries the final summary`() {
        runBlocking {
            checkAll(500, arbText) { summary ->
                val terminal = listOf(
                    TaskEvent.Enqueued,
                    TaskEvent.SlotClaimed,
                    TaskEvent.ChildProgressed,
                    TaskEvent.FinalResult(summary = summary),
                ).fold<TaskEvent, TaskState>(TaskState.Created) { state, event ->
                    TaskStateReducer.reduce(state, event)
                }
                assertEquals(TaskState.Succeeded(summary = summary), terminal)
            }
        }
    }

    @Test
    fun `approval round-trip goes Running to WaitingApproval to Resuming and back to Running`() {
        runBlocking {
            checkAll(500, arbRequest, Arb.boolean()) { request, approved ->
                val waiting = TaskStateReducer.reduce(TaskState.Running, TaskEvent.ApprovalRequested(request))
                assertEquals(TaskState.WaitingApproval(request), waiting)

                // Approve AND deny both resume the child (a denial is delivered to the child as
                // the tool result); neither short-circuits to a terminal state.
                val resuming = TaskStateReducer.reduce(waiting, TaskEvent.ApprovalResolved(approved = approved))
                assertEquals(TaskState.Resuming, resuming)

                val running = TaskStateReducer.reduce(resuming, TaskEvent.ChildProgressed)
                assertEquals(TaskState.Running, running)
            }
        }
    }

    @Test
    fun `FinalResult terminates execution-active states into Succeeded`() {
        runBlocking {
            checkAll(1_000, arbText) { summary ->
                assertEquals(
                    TaskState.Succeeded(summary = summary),
                    TaskStateReducer.reduce(TaskState.Starting, TaskEvent.FinalResult(summary = summary)),
                )
                assertEquals(
                    TaskState.Succeeded(summary = summary),
                    TaskStateReducer.reduce(TaskState.Running, TaskEvent.FinalResult(summary = summary)),
                )
                assertEquals(
                    TaskState.Succeeded(summary = summary),
                    TaskStateReducer.reduce(TaskState.Resuming, TaskEvent.FinalResult(summary = summary)),
                )
            }
        }
    }

    @Test
    fun `FinalResult terminal event is consistent across execution-active states`() {
        runBlocking {
            checkAll(1_000, arbText, arbBreach) { summary, breach ->
                val events = listOf(
                    TaskEvent.FinalResult(summary = summary),
                    TaskEvent.ExecutionFailed(error = summary),
                    TaskEvent.CancelRequested,
                    TaskEvent.BudgetExceeded(breach),
                )

                events.forEach { event ->
                    val fromStarting = TaskStateReducer.reduce(TaskState.Starting, event)
                    val fromRunning = TaskStateReducer.reduce(TaskState.Running, event)
                    val fromResuming = TaskStateReducer.reduce(TaskState.Resuming, event)

                    assertEquals(fromStarting, fromRunning)
                    assertEquals(fromStarting, fromResuming)
                }
            }
        }
    }

    @Test
    fun `CancelRequested moves active states to Cancelled`() {
        runBlocking {
            checkAll(1_000, arbText) { summary ->
                assertEquals(
                    TaskState.Cancelled,
                    TaskStateReducer.reduce(TaskState.Starting, TaskEvent.CancelRequested),
                )
                assertEquals(
                    TaskState.Cancelled,
                    TaskStateReducer.reduce(TaskState.Running, TaskEvent.CancelRequested),
                )
                assertEquals(
                    TaskState.Cancelled,
                    TaskStateReducer.reduce(TaskState.Resuming, TaskEvent.CancelRequested),
                )
                assertEquals(
                    TaskState.Cancelled,
                    TaskStateReducer.reduce(
                        TaskState.Interrupted(progressSummary = summary),
                        TaskEvent.CancelRequested,
                    ),
                )
            }
        }
    }
}
