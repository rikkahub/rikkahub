package me.rerere.ai.agent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticesTest {
    private val tool = UIMessagePart.Tool(
        toolCallId = "call-1",
        toolName = "shell",
        input = "{}",
        output = listOf(UIMessagePart.Text("ok")),
    )
    private val notice = UIMessagePart.Notice(title = "done", text = "<notice>done</notice>")

    @Test
    fun `notice after tools splits the assistant message at that point`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("checking"), tool, notice, UIMessagePart.Text("answer")),
        )

        val result = listOf(UIMessage.user("hi"), assistant).expandNotices()

        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
            result.map { it.role },
        )
        assertEquals(listOf(UIMessagePart.Text("checking"), tool), result[1].parts)
        assertEquals("<notice>done</notice>", result[2].toText())
        assertEquals(listOf(UIMessagePart.Text("answer")), result[3].parts)
        assertTrue(result.none { message -> message.parts.any { it is UIMessagePart.Notice } })
    }

    @Test
    fun `notice at the start of a turn becomes a user message before the reply`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(notice, UIMessagePart.Text("answer")),
        )

        val result = listOf(UIMessage.user("hi"), assistant).expandNotices()

        assertEquals(listOf(MessageRole.USER, MessageRole.USER, MessageRole.ASSISTANT), result.map { it.role })
        assertEquals(listOf(UIMessagePart.Text("answer")), result[2].parts)
    }

    @Test
    fun `a notice only user message becomes plain user messages`() {
        val other = UIMessagePart.Notice(title = "other", text = "<notice>other</notice>")
        val message = UIMessage(role = MessageRole.USER, parts = listOf(notice, other))

        val result = listOf(message).expandNotices()

        assertEquals(listOf("<notice>done</notice>", "<notice>other</notice>"), result.map { it.toText() })
        assertTrue(result.all { it.role == MessageRole.USER && it.isSynthetic })
    }

    @Test
    fun `messages without notices are returned as is`() {
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool, UIMessagePart.Text("answer")))

        val result = listOf(UIMessage.user("hi"), assistant).expandNotices()

        assertEquals(2, result.size)
        assertSame(assistant, result[1])
    }
}
