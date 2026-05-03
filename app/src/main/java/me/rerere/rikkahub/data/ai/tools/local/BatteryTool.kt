package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun batteryTool(context: Context): Tool = Tool(
    name = "get_battery_status",
    description = """
        Get the current battery status of the device, including charge percentage,
        charging state, plug type, health, temperature, voltage, and battery technology.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val intent: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val payload = if (intent == null) {
            buildJsonObject { put("error", "battery status unavailable") }
        } else {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val plugged = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
                else -> "none"
            }
            val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "unspecified_failure"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                else -> "unknown"
            }
            val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            val technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
            buildJsonObject {
                put("percent", percent)
                put("charging", charging)
                put("plugged", plugged)
                put("health", health)
                if (tempTenths != Int.MIN_VALUE) {
                    put("temperature_c", tempTenths / 10.0)
                }
                put("voltage_mv", voltage)
                put("technology", technology ?: "")
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
