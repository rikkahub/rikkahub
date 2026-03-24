package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageBehaviorTest {
    @Test
    fun `primary actions should show for non-loading message with content`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("hello"))
        )

        assertTrue(message.shouldShowPrimaryActions(loading = false))
    }

    @Test
    fun `primary actions should hide while message is loading`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("hello"))
        )

        assertFalse(message.shouldShowPrimaryActions(loading = true))
    }

    @Test
    fun `primary actions should hide for empty message`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList()
        )

        assertFalse(message.shouldShowPrimaryActions(loading = false))
    }
}
