package me.rerere.rikkahub.utils

import android.content.Context
import me.rerere.rikkahub.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * 将 LocalDate 转换为聊天列表中使用的日期标签
 * @param context Context 用于获取本地化字符串资源
 * @return 格式化的日期标签 (今天/昨天/具体日期)
 */
fun LocalDate.toChatDateLabel(context: Context): String {
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)

    return when (this) {
        today -> context.getString(R.string.chat_page_today)
        yesterday -> context.getString(R.string.chat_page_yesterday)
        else -> toLocalString(year != today.year)
    }
}

fun Instant.toLocalDate(): String {
    val zoneId = ZoneId.systemDefault()
    val localDateTime = this.atZone(zoneId).toLocalDateTime()

    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(localDateTime)
}

fun Instant.toLocalDateTime(): String {
    val zoneId = ZoneId.systemDefault()
    val localDateTime = this.atZone(zoneId).toLocalDateTime()

    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(localDateTime)
}

fun Instant.toLocalTime(): String {
    val zoneId = ZoneId.systemDefault()
    val localDateTime = this.atZone(zoneId).toLocalDateTime()

    return DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(localDateTime)
}

fun LocalDateTime.toLocalString(): String {
    val locale = Locale.getDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    return formatter.format(this)
}

fun LocalDate.toLocalString(includeYear: Boolean): String {
    val locale = Locale.getDefault()
    val formatter = if (includeYear) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    } else {
        if (isMonthFirstLocale(locale)) {
            // Month-day format (e.g., "Sep 20" for US English)
            DateTimeFormatterBuilder()
                .appendText(ChronoField.MONTH_OF_YEAR, TextStyle.SHORT)
                .appendLiteral(' ')
                .appendValue(ChronoField.DAY_OF_MONTH)
                .toFormatter(locale)
        } else {
            // Day-month format (e.g., "20 sep" for Swedish)
            DateTimeFormatterBuilder()
                .appendValue(ChronoField.DAY_OF_MONTH)
                .appendLiteral(' ')
                .appendText(ChronoField.MONTH_OF_YEAR, TextStyle.SHORT)
                .toFormatter(locale)
        }
    }

    return formatter.format(this)
}

private fun isMonthFirstLocale(locale: Locale): Boolean {
    val monthFirstCountries = setOf(
        "US", // 美国
        "PH", // 菲律宾
        "CA", // 加拿大(虽然魁北克可能使用日-月格式)
        "CN", // 中国
    )
    return monthFirstCountries.contains(locale.country)
}
