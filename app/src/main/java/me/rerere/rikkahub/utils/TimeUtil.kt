package me.rerere.rikkahub.utils

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

/**
 * 将本地日期时间格式化为日期字符串，与 [Instant.toLocalDate] 输出格式一致。
 *
 * 用于消息模板的 {{ date }} 变量：接收消息自身的创建时间（java.time.LocalDateTime），
 * 直接格式化而不经过时区转换，使历史消息的时间戳跨请求保持稳定，避免破坏 prompt 缓存。
 */
fun LocalDateTime.toLocalDateString(): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)

/**
 * 将本地日期时间格式化为时间字符串，与 [Instant.toLocalTime] 输出格式一致。
 *
 * 用于消息模板的 {{ time }} 变量：接收消息自身的创建时间（java.time.LocalDateTime），
 * 使历史消息的时间戳跨请求保持稳定，避免破坏 prompt 缓存。
 */
fun LocalDateTime.toLocalTimeString(): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)

fun LocalDateTime.toLocalString(): String {
    val locale = Locale.getDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    return formatter.format(this)
}

/**
 * 消息时间显示：当天只显示时间（如 14:30），非当天显示「月日 + 时间」（如 5月20日 14:30）。
 */
fun LocalDateTime.toMessageTimeString(): String {
    val locale = Locale.getDefault()
    return if (this.toLocalDate() == LocalDate.now()) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(this)
    } else {
        this.toLocalString()
    }
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
