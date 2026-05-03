package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.provider.Settings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun getBrightnessTool(context: Context): Tool = Tool(
    name = "get_brightness",
    description = """
        Get the device's current screen brightness (0-255) and whether automatic
        brightness mode is enabled.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val cr = context.contentResolver
        val value = try {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Settings.SettingNotFoundException) {
            128
        }
        val isAuto = try {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (_: Settings.SettingNotFoundException) {
            false
        }
        val payload = buildJsonObject {
            put("value", value)
            put("is_auto_mode", isAuto)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun setBrightnessTool(context: Context): Tool = Tool(
    name = "set_brightness",
    description = """
        Set the device's screen brightness (0-255). Requires WRITE_SETTINGS permission.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("value", buildJsonObject {
                    put("type", "integer")
                    put("description", "Target brightness, 0-255")
                })
            },
            required = listOf("value")
        )
    },
    execute = {
        val params = it.jsonObject
        val raw = params["value"]?.jsonPrimitive?.intOrNull
            ?: error("value is required")
        if (!PermissionHelper.hasWriteSettings(context)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "WRITE_SETTINGS not granted") }.toString()
                )
            )
        }
        val clamped = raw.coerceIn(0, 255)
        val payload = try {
            val cr = context.contentResolver
            Settings.System.putInt(
                cr,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, clamped)
            buildJsonObject {
                put("success", true)
                put("value", clamped)
            }
        } catch (_: SecurityException) {
            buildJsonObject { put("error", "WRITE_SETTINGS not granted") }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
