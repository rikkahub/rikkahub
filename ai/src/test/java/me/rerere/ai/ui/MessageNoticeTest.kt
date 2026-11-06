package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNoticeTest {
    private val notice = UIMessagePart.Notice(title = "done", text = "<notice>done</notice>")

    @Test
    fun `only messages made of notices count as notice only`() {
        assertTrue(UIMessage(role = MessageRole.USER, parts = listOf(notice)).isNoticeOnly)
        assertFalse(UIMessage(role = MessageRole.USER, parts = emptyList()).isNoticeOnly)
        assertFalse(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(notice, UIMessagePart.Text("answer"))).isNoticeOnly
        )
    }

    @Test
    fun `sorting legacy parts keeps a notice where it is`() {
        val parts = listOf(notice, UIMessagePart.Reasoning("thinking"), UIMessagePart.Text("answer"))

        assertEquals(parts, parts.toSortedMessageParts())
    }
}
