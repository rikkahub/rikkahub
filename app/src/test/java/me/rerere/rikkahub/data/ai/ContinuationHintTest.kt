package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ContinuationHintTest {
    private val checkpoint = UIMessage.user("summary").copy(isContextCheckpoint = true)
    private val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("working")))

    private fun UIMessage.texts() = parts.filterIsInstance<UIMessagePart.Text>().map { it.text }

    @Test
    fun `a trailing checkpoint tells the model to continue`() {
        val result = listOf(checkpoint).withContinuationHint()

        assertEquals(2, result.single().texts().size)
        assertEquals("summary", result.single().texts().first())
    }

    @Test
    fun `the hint stays once the continued reply follows the checkpoint`() {
        val result = listOf(checkpoint, reply, UIMessage.user("next")).withContinuationHint()

        assertEquals(2, result.first().texts().size)
        assertEquals(listOf(reply, result.last()), result.drop(1))
    }

    @Test
    fun `a checkpoint followed by a user message is left alone`() {
        val messages = listOf(checkpoint, UIMessage.user("next"))

        assertSame(messages, messages.withContinuationHint())
    }

    @Test
    fun `messages without a checkpoint are left alone`() {
        val messages = listOf(UIMessage.user("hi"), reply)

        assertSame(messages, messages.withContinuationHint())
    }
}
