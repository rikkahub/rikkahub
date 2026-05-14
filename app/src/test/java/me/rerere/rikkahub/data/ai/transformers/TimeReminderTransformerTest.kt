package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [applyTimeReminder].
 *
 * Contract (since the f5336e69 behavior change): a `<time_reminder>` is injected
 * before the FIRST user message unconditionally (no "since last message" text),
 * and before any later user message whose gap from the previous message exceeds
 * 1 hour (with the gap spelled out). Non-user messages never get a reminder.
 */
class TimeReminderTransformerTest {

    private fun userMessage(text: String, createdAt: LocalDateTime) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = createdAt,
    )

    private fun getMessageText(msg: UIMessage): String =
        msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    @Test
    fun `single user message gets a reminder injected before it`() {
        val messages = listOf(userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)))
        val result = applyTimeReminder(messages)
        // reminder + original message
        assertEquals(2, result.size)
        val injected = getMessageText(result[0])
        assertTrue(injected.contains("<time_reminder>"))
        // first-message reminder carries no gap text
        assertFalse(injected.contains("since last message"))
        assertEquals("Hello", getMessageText(result[1]))
    }

    @Test
    fun `gap less than 1 hour injects only the first-message reminder`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 10, 30, 0)), // 30 min gap
        )
        val result = applyTimeReminder(messages)
        // first-message reminder + Hello + World (no second reminder, gap < 1h)
        assertEquals(3, result.size)
        assertTrue(getMessageText(result[0]).contains("<time_reminder>"))
        assertEquals("Hello", getMessageText(result[1]))
        assertEquals("World", getMessageText(result[2]))
    }

    @Test
    fun `gap exactly 1 hour injects only the first-message reminder`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 11, 0, 0)), // exactly 1 hour
        )
        val result = applyTimeReminder(messages)
        // threshold is strict (> 3600s), so exactly 1h does not trigger
        assertEquals(3, result.size)
        assertTrue(getMessageText(result[0]).contains("<time_reminder>"))
        assertEquals("Hello", getMessageText(result[1]))
        assertEquals("World", getMessageText(result[2]))
    }

    @Test
    fun `gap more than 1 hour injects a reminder before the second message`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 12, 0, 0)), // 2 hours
        )
        val result = applyTimeReminder(messages)
        // first-message reminder + Hello + gap reminder + World
        assertEquals(4, result.size)
        assertTrue(getMessageText(result[0]).contains("<time_reminder>"))
        assertEquals("Hello", getMessageText(result[1]))
        val injected = getMessageText(result[2])
        assertTrue(injected.contains("<time_reminder>"))
        assertTrue(injected.contains("since last message"))
        assertEquals("World", getMessageText(result[3]))
    }

    @Test
    fun `injected gap reminder contains day of week and gap in hours`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 12, 0, 0)), // 2 hours
        )
        val result = applyTimeReminder(messages)
        val injected = getMessageText(result[2])
        // day-of-week and time are comma-separated
        assertTrue(injected.contains(","))
        assertTrue(injected.contains("2 h since last message"))
    }

    @Test
    fun `gap in days formats correctly`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 20, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 10, 0, 0)), // 2 days
        )
        val result = applyTimeReminder(messages)
        val injected = getMessageText(result[2])
        assertTrue(injected.contains("2 d since last message"))
    }

    @Test
    fun `multiple large gaps inject multiple reminders`() {
        val messages = listOf(
            userMessage("Msg 1", LocalDateTime(2026, 2, 20, 10, 0, 0)),
            userMessage("Msg 2", LocalDateTime(2026, 2, 21, 10, 0, 0)), // 1 day
            userMessage("Msg 3", LocalDateTime(2026, 2, 22, 10, 0, 0)), // 1 day
        )
        val result = applyTimeReminder(messages)
        // first-message reminder + Msg 1 + gap reminder + Msg 2 + gap reminder + Msg 3
        assertEquals(6, result.size)
        assertTrue(getMessageText(result[0]).contains("<time_reminder>"))
        assertEquals("Msg 1", getMessageText(result[1]))
        assertTrue(getMessageText(result[2]).contains("<time_reminder>"))
        assertEquals("Msg 2", getMessageText(result[3]))
        assertTrue(getMessageText(result[4]).contains("<time_reminder>"))
        assertEquals("Msg 3", getMessageText(result[5]))
    }

    @Test
    fun `empty messages return empty`() {
        val result = applyTimeReminder(emptyList())
        assertEquals(0, result.size)
    }
}
