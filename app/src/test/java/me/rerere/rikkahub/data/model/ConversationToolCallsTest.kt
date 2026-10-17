package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationToolCallsTest {
    @Test
    fun `messages whose unexecuted tools cannot resume are dropped before generating`() {
        val user = UIMessage.user("run it")
        val answered = toolReply(ToolApprovalState.Pending)
        val stale = toolReply(ToolApprovalState.Pending)
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(
                user.toMessageNode(),
                MessageNode(messages = listOf(answered, stale), selectIndex = 1),
            )
        )

        val cleaned = conversation.withoutUnresumableToolCalls()

        assertEquals(listOf(user.id, answered.id), cleaned.currentMessages.map { it.id })
        assertEquals(listOf(answered.id), cleaned.messageNodes[1].messages.map { it.id })
    }

    @Test
    fun `regenerating a reply that waits for approval keeps it as a branch`() {
        val user = UIMessage.user("run it")
        val earlierStale = toolReply(ToolApprovalState.Pending)
        val pending = toolReply(ToolApprovalState.Pending)
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(earlierStale.toMessageNode(), user.toMessageNode(), pending.toMessageNode())
        )

        // 只清理要发给模型的节点：之前的节点照常去掉，被重新生成的节点保留
        val cleaned = conversation.withoutUnresumableToolCalls(untilNode = 2)

        assertEquals(listOf(user.id, pending.id), cleaned.currentMessages.map { it.id })
    }

    @Test
    fun `approved tools and finished replies are kept`() {
        val approved = toolReply(ToolApprovalState.Approved)
        val executed = UIMessage.assistant("done")
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(executed.toMessageNode(), approved.toMessageNode())
        )

        assertTrue(conversation.withoutUnresumableToolCalls() === conversation)
    }

    @Test
    fun `node left without messages is removed`() {
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(UIMessage.user("hi").toMessageNode(), toolReply(ToolApprovalState.Auto).toMessageNode())
        )

        assertEquals(1, conversation.withoutUnresumableToolCalls().messageNodes.size)
    }

    @Test
    fun `awaiting approval only looks at the selected branch`() {
        val pending = toolReply(ToolApprovalState.Pending)
        val other = UIMessage.assistant("other branch")
        val selectingPending = Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(MessageNode(messages = listOf(other, pending), selectIndex = 1))
        )

        assertTrue(selectingPending.isAwaitingToolApproval)
        assertFalse(
            selectingPending.copy(messageNodes = listOf(selectingPending.messageNodes.single().copy(selectIndex = 0)))
                .isAwaitingToolApproval
        )
    }

    private fun toolReply(approvalState: ToolApprovalState) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = Uuid.random().toString(),
                toolName = "workspace_shell",
                input = "{}",
                approvalState = approvalState,
            )
        ),
    )
}
