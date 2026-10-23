package me.rerere.rikkahub.ui.floating

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GenerationPhase
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.MessageQueueState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

class FloatingChatUiStateTest {
    private fun state(conversation: Conversation, replyMessageId: Uuid?, generating: Boolean = false) =
        buildFloatingChatUiState(
            conversation = conversation,
            replyMessageId = replyMessageId,
            generating = generating,
            processingStatus = null,
            queue = MessageQueueState(),
            error = null,
        )

    private fun pendingToolReply() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "1",
                toolName = "shell",
                input = "{}",
                approvalState = ToolApprovalState.Pending
            )
        ),
    )

    // 重新生成较早的助手消息：回复写回那个节点的新分支，后面的节点保留
    private fun regenerated(newBranch: UIMessage, selectIndex: Int = 1): Conversation {
        val node =
            MessageNode(messages = listOf(UIMessage.assistant("old answer"), newBranch), selectIndex = selectIndex)
        return Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(
                UIMessage.user("first").toMessageNode(),
                node,
                UIMessage.user("second").toMessageNode(),
                UIMessage.assistant("tail answer").toMessageNode(),
            )
        )
    }

    @Test
    fun `regenerating an earlier reply previews that reply instead of the tail`() {
        val reply = UIMessage.assistant("new answer")
        val state = state(regenerated(reply), reply.id, generating = true)
        assertEquals("new answer", state.preview)
        assertEquals(GenerationPhase.Writing("new answer"), state.phase)
    }

    @Test
    fun `switching the node back to the old branch keeps previewing the generating reply`() {
        val reply = UIMessage.assistant("new answer")
        val state = state(regenerated(reply, selectIndex = 0), reply.id, generating = true)
        assertEquals("new answer", state.preview)
    }

    @Test
    fun `pending approval in an earlier node is reported`() {
        val reply = pendingToolReply()
        val conversation = regenerated(reply)
        assertEquals(listOf("1"), state(conversation, reply.id).pendingTools.map { it.toolCallId })
        // 即使不知道本轮写的是哪条消息，审批也不能漏
        assertEquals(1, state(conversation, replyMessageId = null).pendingTools.size)
    }

    @Test
    fun `new turn without output yet does not show the previous reply`() {
        val state = state(regenerated(UIMessage.assistant("new answer")), replyMessageId = null, generating = true)
        assertFalse(state.hasReply)
        assertEquals("", state.preview)
    }
}
