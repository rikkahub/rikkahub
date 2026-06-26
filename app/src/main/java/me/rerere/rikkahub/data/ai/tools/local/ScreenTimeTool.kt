package me.rerere.rikkahub.data.ai.tools.local

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal fun buildScreenTimeTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "get_screen_time",
    description = """
        Get the user's app screen usage (screen time) over a time range.
        Specify a custom interval with 'begin'/'end', or use the 'range' preset (today/week).
        Returns the total foreground time and a per-app breakdown sorted by usage time (descending).
        The device timezone is '${ZoneId.systemDefault()}' (UTC offset ${OffsetDateTime.now().offset});
        times without an explicit offset are interpreted in this timezone.
        Requires the 'Usage access' special permission; if it is not granted, the device's usage
        access settings page is opened automatically and an error is returned.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("begin", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Start time (inclusive). Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                            "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds. " +
                            "When provided, 'range' is ignored."
                    )
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "End time (exclusive), same formats as 'begin'. Defaults to now."
                    )
                })
                put("range", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("today")
                            add("week")
                        }
                    )
                    put(
                        "description",
                        "Convenience preset, used only when 'begin' is omitted: today or week. Default today."
                    )
                })
                put("top", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of top apps to return, sorted by usage time. Default 10.")
                })
            }
        )
    },
    execute = {
        if (!context.hasUsageStatsPermission()) {
            eventBus.emit(AppEvent.OpenUsageAccessSettings)
            val payload = buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Usage access permission is not granted. The system settings page has been " +
                        "opened; please ask the user to enable 'Usage access' for this app and try again."
                )
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val params = it.jsonObject
        val top = params["top"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 50) ?: 10

        val now = ZonedDateTime.now()
        val zone = now.zone
        val beginRaw = params["begin"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val rangePreset = params["range"]?.jsonPrimitive?.contentOrNull ?: "today"

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            endTime = endRaw?.let { raw -> parseUsageTime(raw, zone) } ?: now
            startTime = if (beginRaw != null) {
                parseUsageTime(beginRaw, zone)
            } else when (rangePreset) {
                "week" -> now.minusDays(7)
                else -> now.toLocalDate().atStartOfDay(zone)
            }
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", e.message ?: "Invalid time format for begin/end.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        if (!startTime.isBefore(endTime)) {
            val payload = buildJsonObject {
                put("error", "INVALID_RANGE")
                put("message", "begin must be earlier than end.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val isCustom = beginRaw != null || endRaw != null
        val endMs = endTime.toInstant().toEpochMilli()
        val startMs = startTime.toInstant().toEpochMilli()

        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = context.packageManager
        val stats = usageStatsManager.queryAndAggregateUsageStats(startMs, endMs)

        val apps = stats.values
            .filter { stat -> stat.totalTimeInForeground > 0 }
            .sortedByDescending { stat -> stat.totalTimeInForeground }
            .take(top)

        val totalMs = stats.values.sumOf { stat -> stat.totalTimeInForeground }

        val payload = buildJsonObject {
            put("range", if (isCustom) "custom" else rangePreset)
            put("start", startTime.withNano(0).toString())
            put("end", endTime.withNano(0).toString())
            put("total_ms", totalMs)
            put("total_minutes", totalMs / 60000)
            put("apps", buildJsonArray {
                apps.forEach { stat ->
                    add(buildJsonObject {
                        put("package", stat.packageName)
                        put("app_name", resolveAppName(pm, stat.packageName))
                        put("total_ms", stat.totalTimeInForeground)
                        put("total_minutes", stat.totalTimeInForeground / 60000)
                    })
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private fun resolveAppName(pm: PackageManager, packageName: String): String {
    return runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

/**
 * 解析 begin/end 时间参数, 依次尝试: epoch 毫秒 -> 带偏移日期时间 -> Instant ->
 * 本地日期时间 -> 本地日期(当天 0 点). 全部失败时抛出异常.
 */
private fun parseUsageTime(raw: String, zone: ZoneId): ZonedDateTime {
    val text = raw.trim()
    text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
    runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
    runCatching { return Instant.parse(text).atZone(zone) }
    runCatching { return LocalDateTime.parse(text).atZone(zone) }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
    error("Invalid time format: '$raw'. Use ISO-8601 date/date-time or epoch milliseconds.")
}
