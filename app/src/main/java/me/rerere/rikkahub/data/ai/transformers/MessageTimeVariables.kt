package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.ui.UIMessage
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal data class MessageTimeVariables(
    val date: String,
    val time: String,
)

internal fun UIMessage.timeVariables(locale: Locale = Locale.getDefault()): MessageTimeVariables {
    val value = createdAt.toJavaLocalDateTime()
    return MessageTimeVariables(
        date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(value),
        time = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale).format(value),
    )
}
