package me.rerere.ai.ui

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROPERTY #7 — INVARIANT suite over [ToolApprovalState] and the derived [UIMessagePart.Tool] flags.
 *
 * DOWNGRADE JUSTIFICATION: there is NO state-transition API. ToolApprovalState (Message.kt:335-356)
 * is a closed sealed hierarchy of value objects with no mutate/transition methods. The only
 * behavioral contract is the pure predicate [canResumeToolExecution] (Message.kt:358-367) and the
 * derived Tool flags isExecuted/isPending/canResumeExecution (Message.kt:484-490). Modeling a legal
 * transition machine would be fabricating an API the code does not have, so per the task's downgrade
 * clause this is an INVARIANT suite over the predicate + flags, NOT a transition property.
 */
class ToolApprovalStateInvariantPropertyTest {

    private val arbApprovalState: Arb<ToolApprovalState> = Arb.choice(
        Arb.of(listOf<ToolApprovalState>(ToolApprovalState.Auto)),
        Arb.of(listOf<ToolApprovalState>(ToolApprovalState.Pending)),
        Arb.of(listOf<ToolApprovalState>(ToolApprovalState.Approved)),
        Arb.string(0..8).map { ToolApprovalState.Denied(it) },
        Arb.string(0..8).map { ToolApprovalState.Answered(it) },
    )

    private val arbTool: Arb<UIMessagePart.Tool> = arbitrary {
        UIMessagePart.Tool(
            toolCallId = Arb.string(0..8).bind(),
            toolName = Arb.string(0..8).bind(),
            input = Arb.string(0..8).bind(),
            // Vary output: empty vs non-empty Text list, to exercise isExecuted both ways.
            output = Arb.list(Arb.string(0..4).map { UIMessagePart.Text(it) }, 0..2).bind(),
            approvalState = arbApprovalState.bind(),
        )
    }

    @Test
    fun `canResumeToolExecution partitions the sealed states exactly`() {
        runBlocking {
            checkAll(200, arbApprovalState) { state ->
                val expected = when (state) {
                    ToolApprovalState.Approved,
                    is ToolApprovalState.Denied,
                    is ToolApprovalState.Answered,
                        -> true

                    ToolApprovalState.Auto,
                    ToolApprovalState.Pending,
                        -> false
                }
                assertEquals(expected, state.canResumeToolExecution())
                // Pure / idempotent: a terminal state's verdict is stable across repeated calls.
                assertEquals(state.canResumeToolExecution(), state.canResumeToolExecution())
            }
        }
    }

    @Test
    fun `tool flags are exactly derived from output and approvalState`() {
        runBlocking {
            checkAll(200, arbTool) { tool ->
                assertEquals(tool.output.isNotEmpty(), tool.isExecuted)
                assertEquals(tool.approvalState is ToolApprovalState.Pending, tool.isPending)
                assertEquals(
                    !tool.isExecuted && tool.approvalState.canResumeToolExecution(),
                    tool.canResumeExecution,
                )
                // An executed tool can never be resumed, regardless of approval state.
                if (tool.isExecuted) assertFalse(tool.canResumeExecution)
                // A Pending tool can never resume (Pending is not a resumable state).
                if (tool.isPending) assertFalse(tool.canResumeExecution)
            }
        }
    }

    @Test
    fun `Denied and Answered are stable resumable value objects`() {
        runBlocking {
            checkAll(200, Arb.string(0..8)) { s ->
                val denied = ToolApprovalState.Denied(s)
                val answered = ToolApprovalState.Answered(s)
                assertTrue(denied.canResumeToolExecution())
                assertTrue(answered.canResumeToolExecution())
                // Value-object identity: re-constructing from the same payload compares equal,
                // and the verdict is unchanged (no hidden mutable transition state).
                assertEquals(denied, ToolApprovalState.Denied(s))
                assertEquals(answered, ToolApprovalState.Answered(s))
                assertEquals(denied.canResumeToolExecution(), ToolApprovalState.Denied(s).canResumeToolExecution())
            }
        }
    }
}
