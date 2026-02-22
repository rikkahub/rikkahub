package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeReminderTransformerTest {

    private fun userMessage(text: String, createdAt: LocalDateTime) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = createdAt,
    )

    private fun getMessageText(msg: UIMessage): String =
        msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    @Test
    fun `single message should not inject time reminder`() {
        val messages = listOf(userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)))
        val result = applyTimeReminder(messages)
        assertEquals(1, result.size)
    }

    @Test
    fun `gap less than 1 hour should not inject`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 10, 30, 0)), // 30 分钟
        )
        val result = applyTimeReminder(messages)
        assertEquals(2, result.size)
    }

    @Test
    fun `gap exactly 1 hour should not inject`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 11, 0, 0)), // 恰好 1 小时
        )
        val result = applyTimeReminder(messages)
        assertEquals(2, result.size)
    }

    @Test
    fun `gap more than 1 hour should inject time reminder before second message`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 12, 0, 0)), // 2 小时
        )
        val result = applyTimeReminder(messages)
        assertEquals(3, result.size)
        // 注入消息在原第二条之前
        val injected = getMessageText(result[1])
        assertTrue(injected.contains("<time_reminder>"))
        assertTrue(injected.contains("since last message"))
        assertEquals("World", getMessageText(result[2]))
    }

    @Test
    fun `injected message should contain day of week and gap in hours`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 12, 0, 0)), // 2 小时
        )
        val result = applyTimeReminder(messages)
        val injected = getMessageText(result[1])
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
        val injected = getMessageText(result[1])
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
        assertEquals(5, result.size) // 3 条原始 + 2 条注入
        assertTrue(getMessageText(result[0]) == "Msg 1")
        assertTrue(getMessageText(result[1]).contains("<time_reminder>"))
        assertTrue(getMessageText(result[2]) == "Msg 2")
        assertTrue(getMessageText(result[3]).contains("<time_reminder>"))
        assertTrue(getMessageText(result[4]) == "Msg 3")
    }

    @Test
    fun `empty messages should return empty`() {
        val result = applyTimeReminder(emptyList())
        assertEquals(0, result.size)
    }
}
