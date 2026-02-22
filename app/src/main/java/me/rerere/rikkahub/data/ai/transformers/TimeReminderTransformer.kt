package me.rerere.rikkahub.data.ai.transformers

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.toJavaInstant

private const val TIME_GAP_THRESHOLD_SECONDS = 3600L // 1 小时

/**
 * 时间提醒注入转换器
 *
 * 在时间间隔较大的消息之前自动注入 <time_reminder>，帮助 AI 了解对话的时间间隔
 */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.assistant.enableTimeReminder) return messages
        return applyTimeReminder(messages)
    }
}

internal fun applyTimeReminder(messages: List<UIMessage>): List<UIMessage> {
    val result = mutableListOf<UIMessage>()
    val tz = TimeZone.currentSystemDefault()

    for (i in messages.indices) {
        val current = messages[i]
        if (i > 0) {
            val previous = messages[i - 1]
            val prevInstant = previous.createdAt.toInstant(tz)
            val currInstant = current.createdAt.toInstant(tz)
            val gapSeconds = (currInstant - prevInstant).inWholeSeconds

            if (gapSeconds > TIME_GAP_THRESHOLD_SECONDS) {
                result.add(buildTimeReminderMessage(gapSeconds, currInstant))
            }
        }
        result.add(current)
    }

    return result
}

private fun buildTimeReminderMessage(gapSeconds: Long, instant: Instant): UIMessage {
    val javaInstant = instant.toJavaInstant()
    val dayOfWeek = javaInstant.atZone(ZoneId.systemDefault()).dayOfWeek
        .getDisplayName(TextStyle.FULL, Locale.getDefault())
    val timeStr = javaInstant.toLocalDateTime()
    val gapText = formatGap(gapSeconds)
    val content = "<time_reminder>Current time: $dayOfWeek, $timeStr ($gapText since last message)</time_reminder>"
    return UIMessage.user(content)
}

private fun formatGap(seconds: Long): String {
    return when {
        seconds < 3600 -> "${seconds / 60} min"
        seconds < 86400 -> "${seconds / 3600} h"
        else -> "${seconds / 86400} d"
    }
}
