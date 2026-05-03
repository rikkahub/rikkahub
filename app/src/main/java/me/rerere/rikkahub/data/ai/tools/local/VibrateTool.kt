package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun vibrateTool(context: Context): Tool = Tool(
    name = "vibrate",
    description = """
        Vibrate the device for a short duration, or follow a custom on/off pattern.
        Use sparingly — vibration is intrusive.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Vibration duration in milliseconds (default 500, max 5000)")
                })
                put("pattern", buildJsonObject {
                    put("type", "array")
                    put("description", "Alternating off/on milliseconds (max 20 entries). Mutually exclusive with duration_ms")
                    put("items", buildJsonObject { put("type", "integer") })
                })
            }
        )
    },
    execute = {
        val params = it.jsonObject
        val durationParam = params["duration_ms"]?.jsonPrimitive?.intOrNull
        val patternParam: JsonArray? = (params["pattern"] as? JsonArray)
            ?: params["pattern"]?.let { el -> runCatching { el.jsonArray }.getOrNull() }

        if (durationParam != null && patternParam != null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "provide either duration_ms or pattern, not both")
                    }.toString()
                )
            )
        }

        val vibrator = context.getSystemService(Vibrator::class.java)
        if (vibrator == null || !vibrator.hasVibrator()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "vibrator unavailable") }.toString()
                )
            )
        }

        val effect: VibrationEffect = if (patternParam != null) {
            if (patternParam.isEmpty()) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject { put("error", "pattern must not be empty") }.toString()
                    )
                )
            }
            val longs = patternParam
                .take(20)
                .map { entry -> (entry.jsonPrimitive.intOrNull ?: 0).coerceAtLeast(0).toLong() }
                .toLongArray()
            VibrationEffect.createWaveform(longs, -1)
        } else {
            val ms = (durationParam ?: 500).coerceIn(1, 5000).toLong()
            VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
        }

        vibrator.vibrate(effect)
        val payload = buildJsonObject { put("success", true) }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
