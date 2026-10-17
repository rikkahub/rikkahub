package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationForkTest {
    @Test
    fun `fork inherits folder and workspace context`() {
        val message = UIMessage.user("hello")
        val source = Conversation(
            assistantId = Uuid.random(),
            title = "Source conversation",
            messageNodes = listOf(message.toMessageNode()),
            config = ConversationConfig(chatModelId = Uuid.random(), reasoningLevel = ReasoningLevel.HIGH),
            workspaceCwd = "/workspace/project",
            folderId = Uuid.random(),
            isPinned = true,
        )

        val fork = source.fork(message.id, emptySet()) { it }!!

        assertNotEquals(source.id, fork.id)
        assertEquals(source.assistantId, fork.assistantId)
        assertEquals(source.config, fork.config)
        assertEquals(source.workspaceCwd, fork.workspaceCwd)
        assertEquals(source.folderId, fork.folderId)
        assertEquals("Source conversation(1)", fork.title)
        assertFalse(fork.isPinned)
    }

    @Test
    fun `fork title increments existing numeric suffix instead of stacking`() {
        assertEquals("Chat(2)", forkConversationTitle("Chat(1)", emptySet()))
        assertEquals("Chat(4)", forkConversationTitle("Chat(1)", setOf("Chat(2)", "Chat(3)")))
        assertEquals("Chat(1)", forkConversationTitle("Chat", emptySet()))
        assertEquals("Chat(2)", forkConversationTitle("Chat", setOf("Chat(1)")))
        assertEquals("Chat(abc)(1)", forkConversationTitle("Chat(abc)", emptySet()))
    }

    @Test
    fun `fork keeps nodes up to the message with new ids and the same branches`() {
        val question = UIMessage.user("question")
        val first = UIMessage.assistant("first")
        val second = UIMessage.assistant("second")
        val answerNode = MessageNode(messages = listOf(first, second), selectIndex = 1)
        val source = Conversation.ofId(
            Uuid.random(),
            messages = listOf(question.toMessageNode(), answerNode, UIMessage.user("later").toMessageNode()),
        )

        val fork = source.fork(first.id, emptySet()) { it }!!

        assertEquals(2, fork.messageNodes.size)
        assertNotEquals(answerNode.id, fork.messageNodes[1].id)
        assertEquals(listOf(first.id, second.id), fork.messageNodes[1].messages.map { it.id })
        assertEquals(1, fork.messageNodes[1].selectIndex)
    }

    @Test
    fun `fork copies local attachments including files inside tool results`() {
        val local = "file:///data/upload/a.png"
        val nested = "file:///data/upload/b.png"
        val remote = "https://example.com/c.png"
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Image(local),
                UIMessagePart.Image(remote),
                UIMessagePart.Tool(
                    toolCallId = "call",
                    toolName = "render",
                    input = "{}",
                    output = listOf(UIMessagePart.Image(nested)),
                ),
            ),
        )
        val source = Conversation.ofId(Uuid.random(), messages = listOf(message.toMessageNode()))

        val fork = source.fork(message.id, emptySet()) { "$it.copy" }!!

        val parts = fork.messageNodes.single().currentMessage.parts
        assertEquals("$local.copy", (parts[0] as UIMessagePart.Image).url)
        assertEquals(remote, (parts[1] as UIMessagePart.Image).url)
        // 两个会话各删各的附件，工具结果里的文件也不能共用
        assertEquals("$nested.copy", ((parts[2] as UIMessagePart.Tool).output.single() as UIMessagePart.Image).url)
    }

    @Test
    fun `fork at a missing message gives nothing`() {
        val source = Conversation.ofId(Uuid.random(), messages = listOf(UIMessage.user("hi").toMessageNode()))

        assertNull(source.fork(Uuid.random(), emptySet()) { error("Must not copy files") })
    }
}
