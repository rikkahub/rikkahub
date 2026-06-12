package me.rerere.ai.runtime.board

import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TerminalStatusMonotonicity (SPEC.md M1): the work-item status machine only ever takes legal
 * transitions — `Pending -> InProgress -> Completed`; any non-deleted status `-> Deleted`;
 * `InProgress -> Pending` ONLY via explicit release; `Completed -> Pending` ONLY via explicit
 * reopen — so `Completed` never regresses without an explicit reopen, and `Deleted` absorbs
 * everything.
 */
class WorkItemTransitionPropertyTest {

    private val arbStatus: Arb<WorkItemStatus> = Arb.enum<WorkItemStatus>()

    private val arbAction: Arb<WorkItemAction> = Arb.choice(
        Arb.constant(WorkItemAction.Claim),
        Arb.constant(WorkItemAction.Complete),
        Arb.constant(WorkItemAction.Release),
        Arb.constant(WorkItemAction.Reopen),
        Arb.constant(WorkItemAction.Delete),
    )

    /**
     * The legal (from, action) -> to relation, transcribed independently from the SPEC.md
     * work-item state machine. `null` = illegal, must be rejected.
     */
    private fun legalTarget(from: WorkItemStatus, action: WorkItemAction): WorkItemStatus? = when (from) {
        WorkItemStatus.Pending -> when (action) {
            WorkItemAction.Claim -> WorkItemStatus.InProgress
            WorkItemAction.Delete -> WorkItemStatus.Deleted
            else -> null
        }

        WorkItemStatus.InProgress -> when (action) {
            WorkItemAction.Complete -> WorkItemStatus.Completed
            WorkItemAction.Release -> WorkItemStatus.Pending
            WorkItemAction.Delete -> WorkItemStatus.Deleted
            else -> null
        }

        WorkItemStatus.Completed -> when (action) {
            WorkItemAction.Reopen -> WorkItemStatus.Pending
            WorkItemAction.Delete -> WorkItemStatus.Deleted
            else -> null
        }

        WorkItemStatus.Deleted -> null
    }

    /** Repository semantics for a rejected transition: the status does not move. */
    private fun advance(status: WorkItemStatus, action: WorkItemAction): WorkItemStatus =
        when (val result = WorkItemTransitionValidator.transition(status, action)) {
            is WorkItemTransitionResult.Allowed -> result.to
            is WorkItemTransitionResult.Rejected -> status
        }

    @Test
    fun `validator matches the transcribed legal relation exactly`() {
        runBlocking {
            checkAll(1_000, arbStatus, arbAction) { status, action ->
                val expected = legalTarget(status, action)
                when (val result = WorkItemTransitionValidator.transition(status, action)) {
                    is WorkItemTransitionResult.Allowed -> assertEquals(
                        "wrong target for $status + $action",
                        expected,
                        result.to,
                    )

                    is WorkItemTransitionResult.Rejected -> {
                        assertEquals("$status + $action must be allowed", null, expected)
                        assertEquals(status, result.from)
                        assertEquals(action, result.action)
                    }
                }
            }
        }
    }

    @Test
    fun `Completed never regresses without an explicit reopen`() {
        runBlocking {
            checkAll(1_000, arbStatus, Arb.list(arbAction, 0..16)) { start, actions ->
                var status = start
                for (action in actions) {
                    val next = advance(status, action)
                    if (status == WorkItemStatus.Completed && next != WorkItemStatus.Completed) {
                        assertTrue(
                            "Completed left via $action -> $next without explicit reopen/delete",
                            action == WorkItemAction.Reopen || action == WorkItemAction.Delete,
                        )
                        val expected = if (action == WorkItemAction.Reopen) {
                            WorkItemStatus.Pending
                        } else {
                            WorkItemStatus.Deleted
                        }
                        assertEquals(expected, next)
                    }
                    status = next
                }
            }
        }
    }

    @Test
    fun `InProgress moves back to Pending only via explicit release`() {
        runBlocking {
            checkAll(1_000, arbStatus, Arb.list(arbAction, 0..16)) { start, actions ->
                var status = start
                for (action in actions) {
                    val next = advance(status, action)
                    if (status == WorkItemStatus.InProgress && next == WorkItemStatus.Pending) {
                        assertEquals(
                            "InProgress -> Pending must be an explicit release",
                            WorkItemAction.Release,
                            action,
                        )
                    }
                    status = next
                }
            }
        }
    }

    @Test
    fun `Deleted is absorbing - every action on a deleted item is rejected`() {
        runBlocking {
            checkAll(500, Arb.list(arbAction, 0..16)) { actions ->
                for (action in actions) {
                    val result = WorkItemTransitionValidator.transition(WorkItemStatus.Deleted, action)
                    assertTrue(
                        "Deleted accepted $action",
                        result is WorkItemTransitionResult.Rejected,
                    )
                }
                val replayed = actions.fold(WorkItemStatus.Deleted) { status, action -> advance(status, action) }
                assertEquals(WorkItemStatus.Deleted, replayed)
            }
        }
    }

    @Test
    fun `rejection carries the offending status and action for caller-agnostic error surfacing`() {
        runBlocking {
            checkAll(1_000, arbStatus, arbAction) { status, action ->
                val result = WorkItemTransitionValidator.transition(status, action)
                if (result is WorkItemTransitionResult.Rejected) {
                    assertEquals(status, result.from)
                    assertEquals(action, result.action)
                    assertTrue(result.reason.isNotBlank())
                }
            }
        }
    }
}
