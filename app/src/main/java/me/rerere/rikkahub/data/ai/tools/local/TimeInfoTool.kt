package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

internal fun buildTimeInfoTool(): Tool = Tool(
    name = "get_time_info",
    description = """
        Get the current local date and time info from the device.
        Returns year/month/day, weekday, ISO date/time strings, timezone, and timestamp.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { }
        )
    },
    execute = {
        val now = ZonedDateTime.now()
        val date = now.toLocalDate()
        val time = now.toLocalTime().withNano(0)
        val weekday = now.dayOfWeek
        val payload = buildJsonObject {
            put("year", date.year)
            put("month", date.monthValue)
            put("day", date.dayOfMonth)
            put("weekday", weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()))
            put("weekday_en", weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            put("weekday_index", weekday.value)
            put("date", date.toString())
            put("time", time.toString())
            put("datetime", now.withNano(0).toString())
            put("timezone", now.zone.id)
            put("utc_offset", now.offset.id)
            put("timestamp_ms", now.toInstant().toEpochMilli())
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
