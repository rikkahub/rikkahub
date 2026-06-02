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
 * NOTE: since commit f5336e69 ("时间提醒为首条用户消息注入时间") applyTimeReminder ALWAYS injects a
 * current-time reminder (no "since last message") before the FIRST user message, then injects a
 * gap reminder before any later user message whose gap exceeds the threshold. These expectations
 * account for that leading first-message reminder at index 0.
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
    fun `single message injects only the leading current-time reminder`() {
        val messages = listOf(userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)))
        val result = applyTimeReminder(messages)
        assertEquals(2, result.size)
        val leading = getMessageText(result[0])
        assertTrue(leading.contains("<time_reminder>"))
        assertFalse(leading.contains("since last message"))
        assertEquals("Hello", getMessageText(result[1]))
    }

    @Test
    fun `gap less than 1 hour injects no gap reminder`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 10, 30, 0)), // 30 分钟
        )
        val result = applyTimeReminder(messages)
        // leading reminder + 2 messages, no gap reminder
        assertEquals(3, result.size)
        assertEquals("Hello", getMessageText(result[1]))
        assertEquals("World", getMessageText(result[2]))
    }

    @Test
    fun `gap exactly 1 hour injects no gap reminder`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 11, 0, 0)), // 恰好 1 小时
        )
        val result = applyTimeReminder(messages)
        assertEquals(3, result.size)
        assertEquals("World", getMessageText(result[2]))
    }

    @Test
    fun `gap more than 1 hour injects gap reminder before second message`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 12, 0, 0)), // 2 小时
        )
        val result = applyTimeReminder(messages)
        // leading reminder, Hello, gap reminder, World
        assertEquals(4, result.size)
        val injected = getMessageText(result[2])
        assertTrue(injected.contains("<time_reminder>"))
        assertTrue(injected.contains("since last message"))
        assertEquals("World", getMessageText(result[3]))
    }

    @Test
    fun `injected gap reminder contains day of week and gap in hours`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 12, 0, 0)), // 2 小时
        )
        val result = applyTimeReminder(messages)
        val injected = getMessageText(result[2])
        // 星期几和时间之间有逗号分隔
        assertTrue(injected.contains(","))
        assertTrue(injected.contains("2 h since last message"))
    }

    @Test
    fun `gap in days should format correctly`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 20, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 10, 0, 0)), // 2 天
        )
        val result = applyTimeReminder(messages)
        val injected = getMessageText(result[2])
        assertTrue(injected.contains("2 d since last message"))
    }

    @Test
    fun `multiple large gaps should inject multiple reminders`() {
        val messages = listOf(
            userMessage("Msg 1", LocalDateTime(2026, 2, 20, 10, 0, 0)),
            userMessage("Msg 2", LocalDateTime(2026, 2, 21, 10, 0, 0)), // 1 天
            userMessage("Msg 3", LocalDateTime(2026, 2, 22, 10, 0, 0)), // 1 天
        )
        val result = applyTimeReminder(messages)
        // leading reminder + 3 messages + 2 gap reminders
        assertEquals(6, result.size)
        assertTrue(getMessageText(result[0]).contains("<time_reminder>"))
        assertEquals("Msg 1", getMessageText(result[1]))
        assertTrue(getMessageText(result[2]).contains("<time_reminder>"))
        assertEquals("Msg 2", getMessageText(result[3]))
        assertTrue(getMessageText(result[4]).contains("<time_reminder>"))
        assertEquals("Msg 3", getMessageText(result[5]))
    }

    @Test
    fun `empty messages should return empty`() {
        val result = applyTimeReminder(emptyList())
        assertEquals(0, result.size)
    }
}
