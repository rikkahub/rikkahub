package me.rerere.rikkahub.data.ai.transformers

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime as JavaLocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private val COMMON_DATE_TIME_PATTERNS = listOf(
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"),
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm"),
    DateTimeFormatter.ofPattern("uuuu/MM/dd HH:mm:ss"),
    DateTimeFormatter.ofPattern("uuuu/MM/dd HH:mm"),
    DateTimeFormatter.ofPattern("uuuu-MM-dd"),
    DateTimeFormatter.ofPattern("uuuu/MM/dd"),
)

private val UTC_OFFSET_REGEX = Regex("""^UTC([+-]\d{1,2})(?::?(\d{2}))?$""", setOf(RegexOption.IGNORE_CASE))

internal fun Duration.humanize(withSuffix: Boolean): String {
    val absoluteDuration = if (isNegative) negated() else this
    val totalSeconds = absoluteDuration.seconds
    if (totalSeconds <= 0) return "just now"

    val totalMinutes = absoluteDuration.toMinutes()
    val totalHours = absoluteDuration.toHours()
    val totalDays = absoluteDuration.toDays()
    val phrase = when {
        totalSeconds < 45 -> "a few seconds"
        totalSeconds < 90 -> "a minute"
        totalMinutes < 45 -> "${totalMinutes.coerceAtLeast(1)} minutes"
        totalMinutes < 90 -> "an hour"
        totalHours < 22 -> "${totalHours.coerceAtLeast(1)} hours"
        totalHours < 36 -> "a day"
        totalDays < 26 -> "${totalDays.coerceAtLeast(1)} days"
        totalDays < 45 -> "a month"
        totalDays < 320 -> "${(totalDays / 30.0).roundToInt().coerceAtLeast(1)} months"
        totalDays < 548 -> "a year"
        else -> "${(totalDays / 365.0).roundToInt().coerceAtLeast(1)} years"
    }
    if (!withSuffix) return phrase
    return if (isNegative) "$phrase ago" else "in $phrase"
}

internal fun ZonedDateTime.formatTime(offsetSpec: String): String {
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    val target = UTC_OFFSET_REGEX.matchEntire(offsetSpec.trim())?.let { match ->
        val hours = match.groupValues[1].toIntOrNull() ?: return@let null
        val minutes = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return@let null
        ZoneOffset.ofHoursMinutes(hours, hours.signCompatibleMinutes(minutes))
    } ?: offset
    return withZoneSameInstant(target).format(formatter)
}

internal fun ZonedDateTime.formatDate(): String {
    return format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
}

internal fun ZonedDateTime.formatWeekday(): String {
    return format(DateTimeFormatter.ofPattern("EEEE"))
}

internal fun ZonedDateTime.formatMomentStyle(rawFormat: String): String {
    val trimmed = rawFormat.trim()
    if (trimmed.isEmpty()) return format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    return when (trimmed) {
        "LT" -> format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        "L" -> format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
        "LL" -> format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
        "LLL" -> format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT))
        "LLLL" -> format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT))
        else -> {
            val javaPattern = trimmed.toJavaDateTimePattern()
            runCatching { format(DateTimeFormatter.ofPattern(javaPattern)) }
                .getOrElse { format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
        }
    }
}

internal fun parseDateTime(
    rawValue: String,
    zoneId: ZoneId,
): ZonedDateTime? {
    val trimmed = rawValue.trim()
    if (trimmed.isEmpty()) return null

    runCatching { return ZonedDateTime.parse(trimmed) }
    runCatching { return OffsetDateTime.parse(trimmed).toZonedDateTime() }
    runCatching { return Instant.parse(trimmed).atZone(zoneId) }
    runCatching { return JavaLocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zoneId) }
    runCatching { return LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(zoneId) }

    COMMON_DATE_TIME_PATTERNS.forEach { formatter ->
        runCatching { return JavaLocalDateTime.parse(trimmed, formatter).atZone(zoneId) }
        runCatching { return LocalDate.parse(trimmed, formatter).atStartOfDay(zoneId) }
    }
    return null
}

private fun Int.signCompatibleMinutes(minutes: Int): Int {
    return when {
        this < 0 -> -minutes.absoluteValue
        this > 0 -> minutes.absoluteValue
        else -> minutes
    }
}

private fun String.toJavaDateTimePattern(): String {
    val replacements = listOf(
        "YYYY" to "uuuu",
        "YY" to "uu",
        "dddd" to "EEEE",
        "ddd" to "EEE",
        "DD" to "dd",
        "D" to "d",
        "MMMM" to "MMMM",
        "MMM" to "MMM",
        "MM" to "MM",
        "M" to "M",
        "HH" to "HH",
        "H" to "H",
        "hh" to "hh",
        "h" to "h",
        "mm" to "mm",
        "m" to "m",
        "ss" to "ss",
        "s" to "s",
        "A" to "a",
        "a" to "a",
        "ZZ" to "xx",
        "Z" to "XXX",
    )
    var value = this
    replacements.forEach { (momentToken, javaToken) ->
        value = value.replace(momentToken, javaToken)
    }
    return value
}

internal fun kotlinx.datetime.LocalDateTime.toJavaLocalDateTime(): JavaLocalDateTime {
    return JavaLocalDateTime.of(year, month.ordinal + 1, day, hour, minute, second, nanosecond)
}
