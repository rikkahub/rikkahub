package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationUpsertMessageTest {
    private val user = UIMessage.user("question")
    private val answer = UIMessage.assistant("first answer")
    private val followUp = UIMessage.user("follow up")

    @Test
    fun `reply to a missing node is appended as a new node`() {
        val nodeId = Uuid.random()
        val conversation = conversationOf(user.toMessageNode())

        val updated = conversation
            .upsertMessage(nodeId, UIMessage.assistant("partial"))
            .let { it.upsertMessage(nodeId, it.messageNodes.last().messages.single().copy(parts = UIMessage.assistant("full").parts)) }

        assertEquals(nodeId, updated.messageNodes.last().id)
        assertEquals(listOf("question", "full"), updated.currentMessages.map { it.toText() })
    }

    @Test
    fun `regenerated reply becomes a selected branch of its node`() {
        val answerNode = answer.toMessageNode()
        val conversation = conversationOf(user.toMessageNode(), answerNode, followUp.toMessageNode())

        val updated = conversation.upsertMessage(answerNode.id, UIMessage.assistant("second answer"))

        val node = updated.messageNodes[1]
        assertEquals(listOf("first answer", "second answer"), node.messages.map { it.toText() })
        assertEquals(1, node.selectIndex)
        assertEquals(followUp.id, updated.messageNodes[2].currentMessage.id)
    }

    @Test
    fun `nodes removed before the reply node do not shift where the reply is written`() {
        val answerNode = answer.toMessageNode()
        val conversation = conversationOf(user.toMessageNode(), answerNode)
            .let { it.copy(messageNodes = it.messageNodes.drop(1)) }

        val updated = conversation.upsertMessage(answerNode.id, answer.copy(parts = UIMessage.assistant("streamed").parts))

        assertEquals(1, updated.messageNodes.size)
        assertEquals(listOf("streamed"), updated.messageNodes.single().messages.map { it.toText() })
    }

    @Test
    fun `updating a reply keeps the branch the user switched to`() {
        val regenerated = UIMessage.assistant("second answer")
        val answerNode = MessageNode(messages = listOf(answer, regenerated), selectIndex = 0)
        val conversation = conversationOf(user.toMessageNode(), answerNode)

        val updated = conversation.upsertMessage(answerNode.id, regenerated.copy(parts = UIMessage.assistant("longer").parts))

        assertEquals(0, updated.messageNodes[1].selectIndex)
        assertEquals("longer", updated.messageNodes[1].messages[1].toText())
    }

    private fun conversationOf(vararg nodes: MessageNode) =
        Conversation.ofId(Uuid.random(), messages = nodes.toList())
}
