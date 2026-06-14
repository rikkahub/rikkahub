package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Finding 3 turn-boundary signal. A per-run automation grant authorizes a whole TURN, but a single
 * `withAutomationLease` entry is NOT the whole turn: an ASK-guardrail approval breaks the turn (a
 * Pending tool waits for the user) and the lease tears down, then the approval-resume re-enters the
 * lease. So the lease teardown must clear the grant only when the turn truly ENDED — and the signal
 * for "still mid-turn" is an outstanding Pending tool approval in the conversation. This pure
 * predicate is that signal, JVM-testable without the service / coroutines.
 */
class AutomationTurnBoundaryTest {

    private fun conversation(parts: List<UIMessagePart>): Conversation = Conversation.ofId(
        id = Uuid.random(),
        messages = listOf(
            UIMessage.user("do it").toMessageNode(),
            UIMessage(role = MessageRole.ASSISTANT, parts = parts).toMessageNode(),
        ),
    )

    private fun tool(approvalState: ToolApprovalState) = UIMessagePart.Tool(
        toolCallId = "call_1",
        toolName = "ui_set_text",
        input = """{"selector":{"tid":0},"text":"hi"}""",
        approvalState = approvalState,
    )

    @Test
    fun `a Pending tool approval means the turn has NOT ended`() {
        val conversation = conversation(listOf(tool(ToolApprovalState.Pending)))

        assertTrue(
            "a Pending ui_* call is the ASK-break mid-turn state — the grant must survive for the resume",
            conversationHasPendingToolApproval(conversation),
        )
    }

    @Test
    fun `an approved tool is not pending — the turn may end and clear the grant`() {
        val conversation = conversation(listOf(tool(ToolApprovalState.Approved)))

        assertFalse(conversationHasPendingToolApproval(conversation))
    }

    @Test
    fun `a fresh auto tool is not a pending approval`() {
        val conversation = conversation(listOf(tool(ToolApprovalState.Auto)))

        assertFalse(conversationHasPendingToolApproval(conversation))
    }

    @Test
    fun `a denied tool is not a pending approval`() {
        val conversation = conversation(listOf(tool(ToolApprovalState.Denied("nope"))))

        assertFalse(conversationHasPendingToolApproval(conversation))
    }

    @Test
    fun `a conversation with no tool parts has no pending approval`() {
        val conversation = conversation(listOf(UIMessagePart.Text("just text")))

        assertFalse(conversationHasPendingToolApproval(conversation))
    }

    @Test
    fun `a Pending tool anywhere in the current messages counts`() {
        // Even if the latest message is plain text, a Pending tool earlier in the turn keeps it open.
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(
                UIMessage.user("do it").toMessageNode(),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool(ToolApprovalState.Pending)))
                    .toMessageNode(),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("status")))
                    .toMessageNode(),
            ),
        )

        assertTrue(conversationHasPendingToolApproval(conversation))
    }

    // --- shouldPreservePerRunGrant truth table: preserve iff normal-completion AND paused ---

    @Test
    fun `the grant is preserved only on a normal pause for an approval`() {
        assertTrue(
            "normal completion + pending approval = the ASK-break that resumes",
            shouldPreservePerRunGrant(completedNormally = true, hasPendingApproval = true),
        )
    }

    @Test
    fun `a normal finish with no pending approval clears the grant`() {
        assertFalse(
            "the turn ended — its one-run grant must not survive to a later run",
            shouldPreservePerRunGrant(completedNormally = true, hasPendingApproval = false),
        )
    }

    @Test
    fun `a cancellation while paused on an approval still clears the grant`() {
        // Stop / supersede / regenerate: a Pending tool is outstanding at cancel time, but the turn is
        // abandoned, not resumed — preserving the grant here is the exact leak finding 3 must avoid.
        assertFalse(
            "an abandoned (cancelled) turn must not leak its grant even though an approval is pending",
            shouldPreservePerRunGrant(completedNormally = false, hasPendingApproval = true),
        )
    }

    @Test
    fun `a cancellation with no pending approval clears the grant`() {
        assertFalse(
            shouldPreservePerRunGrant(completedNormally = false, hasPendingApproval = false),
        )
    }
}
