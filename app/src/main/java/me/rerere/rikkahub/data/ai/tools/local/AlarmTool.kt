package me.rerere.rikkahub.data.ai.tools.local

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Local alarm tools (device-only, not cloud-synced).
 *
 * Create uses [AlarmClock.ACTION_SET_ALARM] so the system Clock app owns the alarm
 * (no SCHEDULE_EXACT_ALARM / no silent AlarmManager). User confirms in Clock UI.
 *
 * Query can only return the next system alarm via [AlarmManager.getNextAlarmClock]
 * — Android has no public API for listing all user alarms.
 */
internal fun buildAlarmQueryTool(context: Context): Tool = Tool(
    name = "alarm_query",
    description = """
        Query the next scheduled system alarm on this Android device.
        Returns trigger time, timezone, and whether a show-intent is available.
        IMPORTANT: Android does not allow apps to list all alarms; only the next
        AlarmClock-style alarm is available. If none is set, returns empty.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {})
    },
    execute = {
        val am = context.getSystemService(AlarmManager::class.java)
        val next = am?.nextAlarmClock
        if (next == null) {
            val payload = buildJsonObject {
                put("has_next_alarm", false)
                put(
                    "message",
                    "No next system alarm is currently scheduled (or the clock app does not expose one).",
                )
                put("limitation", "Android only exposes the next alarm, not a full alarm list.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val triggerMs = next.triggerTime
        val zone = ZoneId.systemDefault()
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(triggerMs), zone)
        val payload = buildJsonObject {
            put("has_next_alarm", true)
            put("trigger_epoch_ms", triggerMs)
            put("trigger_iso", zdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("timezone", zone.id)
            put("hour", zdt.hour)
            put("minute", zdt.minute)
            put("has_show_intent", next.showIntent != null)
            put(
                "limitation",
                "Only the next system alarm is available; full alarm lists are not exposed by Android.",
            )
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

internal fun buildAlarmCreateTool(context: Context): Tool = Tool(
    name = "alarm_create",
    description = """
        Create a new clock alarm by opening the system Clock app with prefilled values.
        Requires hour (0-23) and minute (0-59). Optional message/label and weekly days
        (1=Sunday .. 7=Saturday, Android AlarmClock convention).
        The user will confirm or edit the alarm in the Clock UI — this tool does not
        silently schedule background alarms. Does not require SCHEDULE_EXACT_ALARM.
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("hour", buildJsonObject {
                    put("type", "integer")
                    put("description", "Hour of day in 24-hour format (0-23).")
                })
                put("minute", buildJsonObject {
                    put("type", "integer")
                    put("description", "Minute (0-59).")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional alarm label/message shown in the Clock app.")
                })
                put("days", buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "Optional weekly repeat days. Integers 1=Sunday, 2=Monday, ... 7=Saturday.",
                    )
                    put(
                        "items",
                        buildJsonObject {
                            put("type", "integer")
                        },
                    )
                })
                put("vibrate", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether the alarm should vibrate. Default true.")
                })
                put("skip_ui", buildJsonObject {
                    put("type", "boolean")
                    put(
                        "description",
                        "If true, try to skip Clock UI (may be ignored by OEM). Default false — keep UI for user confirmation.",
                    )
                })
            },
            required = listOf("hour", "minute"),
        )
    },
    execute = { args ->
        if (!canCreateAlarmViaClock(context)) {
            val payload = buildJsonObject {
                put("error", "NO_CLOCK_APP")
                put(
                    "message",
                    "No system app can handle ACTION_SET_ALARM. Install or enable a Clock app.",
                )
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val params = args.jsonObject
        val hour = params["hour"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val minute = params["minute"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (hour == null || hour !in 0..23 || minute == null || minute !in 0..59) {
            val payload = buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", "hour must be 0-23 and minute must be 0-59.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val message = params["message"]?.jsonPrimitive?.contentOrNull
        val vibrate = params["vibrate"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val skipUi = params["skip_ui"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val days = params["days"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
            ?.filter { it in 1..7 }
            ?.distinct()
            .orEmpty()

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_VIBRATE, vibrate)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            if (!message.isNullOrBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
            }
            if (days.isNotEmpty()) {
                putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
            val payload = buildJsonObject {
                put("ok", true)
                put("hour", hour)
                put("minute", minute)
                if (!message.isNullOrBlank()) put("message", message)
                if (days.isNotEmpty()) {
                    put(
                        "days",
                        buildJsonArray { days.forEach { add(it) } },
                    )
                }
                put("vibrate", vibrate)
                put("skip_ui", skipUi)
                put(
                    "note",
                    "Opened system Clock to create the alarm. User may still need to confirm in the Clock app.",
                )
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", "START_FAILED")
                put("message", e.message ?: "Failed to open Clock app for alarm creation.")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    },
)

internal fun buildAlarmShowTool(context: Context): Tool = Tool(
    name = "alarm_show",
    description = """
        Open the system Clock app's alarms list UI so the user can review or edit alarms.
        Use when the user wants to see all alarms (full list is not readable by apps).
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {})
    },
    execute = {
        if (!canShowAlarms(context)) {
            val payload = buildJsonObject {
                put("error", "NO_CLOCK_APP")
                put("message", "No system app can handle ACTION_SHOW_ALARMS.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }
        try {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", true)
                        put("note", "Opened system alarms UI.")
                    }.toString(),
                ),
            )
        } catch (e: Exception) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "START_FAILED")
                        put("message", e.message ?: "Failed to open alarms UI.")
                    }.toString(),
                ),
            )
        }
    },
)

fun canCreateAlarmViaClock(context: Context): Boolean {
    val intent = Intent(AlarmClock.ACTION_SET_ALARM)
    return context.packageManager
        .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .isNotEmpty()
}

fun canShowAlarms(context: Context): Boolean {
    val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
    return context.packageManager
        .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .isNotEmpty()
}

fun canUseAlarmTools(context: Context): Boolean =
    canCreateAlarmViaClock(context) || canShowAlarms(context)
