package me.rerere.rikkahub.web.routes

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

class ConversationRoutesToolApprovalCheckTest {

    private fun conversationWith(
        toolCallId: String,
        state: ToolApprovalState,
    ): Conversation = Conversation.ofId(
        id = Uuid.random(),
        messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = toolCallId,
                        toolName = "ask_user",
                        input = "{}",
                        approvalState = state,
                    ),
                    UIMessagePart.Text("ack"),
                ),
            ).toMessageNode(),
        ),
    )

    @Test
    fun `tool-approval check accepts a pending target`() {
        val pendingToolCallId = "call-pending"
        val conversation = conversationWith(
            toolCallId = pendingToolCallId,
            state = ToolApprovalState.Pending,
        )

        assertTrue(isPendingToolApproval(conversation, pendingToolCallId))
        assertFalse(isPendingToolApproval(conversation, "unknown-call-id"))
    }

    @Test
    fun `tool-approval check rejects a non-pending target`() {
        val conversation = conversationWith(
            toolCallId = "call-not-pending",
            state = ToolApprovalState.Approved,
        )

        assertFalse(isPendingToolApproval(conversation, "call-not-pending"))
    }
}
