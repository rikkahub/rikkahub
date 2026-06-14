package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AutomationGrant
import me.rerere.rikkahub.data.model.AutomationVerb
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * T8 invariant + finding 3: the per-run automation grant (`pendingAutomationGrant`) is transient lease
 * state, but its lifecycle is the whole TURN, not a single lease entry. The per-generation guard
 * (`activeAutomationGuard`) is always dropped at teardown; the grant is dropped TOO when the turn
 * truly ended (no pending approval), so a stale per-run grant cannot leak onto a LATER, unrelated run
 * (T11). When an ASK-guardrail approval breaks the turn (a Pending tool waits for the user), the lease
 * tears down but the grant is PRESERVED so the approval-resume re-mints the guard from it. Both fields
 * live on the session because the kill-switch thread must reach them.
 */
class ConversationSessionAutomationLeaseStateTest {

    private fun session(): ConversationSession = ConversationSession(
        id = Uuid.random(),
        initial = Conversation.ofId(id = Uuid.random()),
        scope = CoroutineScope(Dispatchers.Unconfined),
        onIdle = {},
    )

    private fun session(initial: Conversation): ConversationSession = ConversationSession(
        id = initial.id,
        initial = initial,
        scope = CoroutineScope(Dispatchers.Unconfined),
        onIdle = {},
    )

    private fun pendingTool(): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = "call_1",
        toolName = "ui_set_text",
        input = """{"selector":{"tid":0},"text":"hi"}""",
        approvalState = ToolApprovalState.Pending,
    )

    private fun cancelToolForTest(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
        output = listOf(UIMessagePart.Text("""{"status":"cancelled"}""")),
        approvalState = ToolApprovalState.Denied("new send abandoned pending tool"),
    )

    @Test
    fun `clearing the automation lease state nulls the pending grant alongside the guard`() {
        val s = session()
        s.pendingAutomationGrant = AutomationGrant(
            enabled = true,
            allowedPackages = setOf("com.example.target"),
            verbs = setOf(AutomationVerb.OBSERVE),
            ttlMinutes = 5,
            maxSteps = 50,
        )

        s.clearAutomationLeaseState()

        assertNull(
            "the per-run grant must be cleared by the same lifecycle that nulls activeAutomationGuard",
            s.pendingAutomationGrant,
        )
        assertNull(s.activeAutomationGuard)
    }

    @Test
    fun `session cleanup clears the pending grant`() {
        val s = session()
        s.pendingAutomationGrant = AutomationGrant(enabled = true, allowedPackages = setOf("com.app"))

        s.cleanup()

        assertNull(s.pendingAutomationGrant)
    }

    /**
     * Finding 3: a per-run grant authorizes a whole TURN, not a single lease entry. An ASK-guardrail
     * approval breaks the turn (a Pending tool waits for the user) and the lease tears down BUT the
     * turn has not ended — the approval-resume re-enters the lease and must re-mint the SAME guard from
     * the SAME grant. So the lease teardown must be able to drop the per-generation guard while
     * PRESERVING the per-run grant for the resume. Clearing both (the old single-step teardown) is the
     * bug: on resume the grant is gone, no guard is minted, and the approved `ui_*` call errors
     * "Tool not found". The terminal teardown (no pending approval) still clears both.
     */
    @Test
    fun `preserving the grant on teardown nulls the guard but keeps the grant for the resume`() {
        val s = session()
        val grant = AutomationGrant(
            enabled = true,
            allowedPackages = setOf("com.example.target"),
            verbs = setOf(AutomationVerb.OBSERVE),
            ttlMinutes = 5,
            maxSteps = 50,
        )
        s.pendingAutomationGrant = grant

        s.clearAutomationLeaseState(preserveGrant = true)

        assertNull("the per-generation guard is always dropped at teardown", s.activeAutomationGuard)
        assertSame(
            "the per-run grant must survive an approval-break teardown so the resume re-mints it",
            grant,
            s.pendingAutomationGrant,
        )
    }

    @Test
    fun `the terminal teardown still clears both the guard and the grant`() {
        val s = session()
        s.pendingAutomationGrant = AutomationGrant(
            enabled = true,
            allowedPackages = setOf("com.example.target"),
            verbs = setOf(AutomationVerb.OBSERVE),
            ttlMinutes = 5,
            maxSteps = 50,
        )

        s.clearAutomationLeaseState(preserveGrant = false)

        assertNull(
            "a turn that truly ended (no pending approval) must not leak its grant to a later run",
            s.pendingAutomationGrant,
        )
        assertNull(s.activeAutomationGuard)
    }

    @Test
    fun `abandoning a pending tool for a new send clears the grant before the next derivation`() {
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(
                UIMessage.user("drive the app").toMessageNode(),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(pendingTool()),
                ).toMessageNode(),
            ),
        )
        val s = session(conversation)
        s.pendingAutomationGrant = AutomationGrant(
            enabled = true,
            allowedPackages = setOf("com.example.target"),
            verbs = setOf(AutomationVerb.OBSERVE),
            ttlMinutes = 5,
            maxSteps = 50,
        )
        assertNotNull(
            "the pending grant is usable before the abandon path runs",
            effectiveAutomationCapability(
                pendingGrant = s.pendingAutomationGrant,
                assistantGrant = AutomationGrant(),
                masterSwitchEnabled = true,
                sessionId = conversation.id.toString(),
                now = 1_000L,
            ),
        )

        val updatedConversation = finishInterruptedPendingToolsForNewSend(
            session = s,
            cancelTool = ::cancelToolForTest,
        )

        assertNotNull("the pending tool should be finalized for persistence", updatedConversation)
        assertNull(
            "a new-send abandon finalizes the pending tool, so its one-operation grant must be reset",
            s.pendingAutomationGrant,
        )
        assertNull(
            "the next derivation must not inherit the abandoned run's pending grant",
            effectiveAutomationCapability(
                pendingGrant = s.pendingAutomationGrant,
                assistantGrant = AutomationGrant(),
                masterSwitchEnabled = true,
                sessionId = conversation.id.toString(),
                now = 1_000L,
            ),
        )
        val finalizedTool = updatedConversation!!.currentMessages.last().parts.single() as UIMessagePart.Tool
        assertTrue(finalizedTool.approvalState is ToolApprovalState.Denied)
    }
}
