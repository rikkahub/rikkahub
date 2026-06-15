package me.rerere.rikkahub.web.a2a

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class A2aHitlInputRequiredTest {

    @Test
    fun `pending tool approval classifies as input required`() {
        val state = classifyA2aTransition(
            jobPresent = false,
            prevJobPresent = true,
            doneForContext = false,
            newError = false,
            cancelRequested = false,
            pendingApproval = true,
        )

        assertEquals(A2aTaskState.INPUT_REQUIRED, state)
    }

    @Test
    fun `pending approvals are exposed and unknown approval is rejected`() {
        val conversation = conversationWithTool("tool-1", ToolApprovalState.Pending)

        val input = conversation.pendingA2aInputRequests()
        assertEquals(1, input.size)
        assertEquals("tool-1", input.single().toolCallId)

        assertThrows(IllegalArgumentException::class.java) {
            validatePendingApproval(conversation, A2aToolApproval(toolCallId = "missing"))
        }
    }

    @Test
    fun `approved tool no longer blocks terminal completion`() {
        val conversation = conversationWithTool("tool-1", ToolApprovalState.Approved)

        assertEquals(emptyList<A2aInputRequest>(), conversation.pendingA2aInputRequests())
        assertEquals(
            A2aTaskState.COMPLETED,
            classifyA2aTransition(
                jobPresent = false,
                prevJobPresent = true,
                doneForContext = true,
                newError = false,
                cancelRequested = false,
                pendingApproval = conversationHasPendingA2aApproval(conversation),
            )
        )
    }

    private fun conversationWithTool(toolCallId: String, approvalState: ToolApprovalState) = Conversation(
        id = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        assistantId = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        messageNodes = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = toolCallId,
                        toolName = "ask_user",
                        input = "continue?",
                        approvalState = approvalState,
                    )
                ),
            ).toMessageNode()
        ),
    )
}
