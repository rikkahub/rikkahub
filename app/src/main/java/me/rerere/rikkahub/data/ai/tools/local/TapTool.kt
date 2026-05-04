package me.rerere.rikkahub.data.ai.tools.local

import android.accessibilityservice.GestureDescription
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AgentTurnTracker
import me.rerere.rikkahub.service.ActionLogEntry

private const val DEFAULT_LONG_PRESS_MS = 600L

private fun coordOrError(jsonObj: kotlinx.serialization.json.JsonElement, key: String): Double? {
    val v = jsonObj.jsonObject[key]?.jsonPrimitive?.doubleOrNull
    return if (v != null && v.isFinite() && v >= 0.0) v else null
}

fun tapTool(): Tool = Tool(
    name = "tap",
    description = """
        Tap at absolute screen coordinates (pixels). Requires the AccessibilityService to be enabled.
        Returns {success: true} if the gesture completed, or {success: false, reason} if cancelled.
        If the AccessibilityService is not active, returns {error, recovery} so you can ask the user to enable it.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x", buildJsonObject {
                    put("type", "number")
                    put("description", "Absolute x in pixels of the active display")
                })
                put("y", buildJsonObject {
                    put("type", "number")
                    put("description", "Absolute y in pixels of the active display")
                })
            },
            required = listOf("x", "y")
        )
    },
    execute = { input ->
        AgentTurnTracker.recordAutomationAction()
        val x = coordOrError(input, "x")
        val y = coordOrError(input, "y")
        if (x == null || y == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "x and y are required and must be non-negative numbers")
                    }.toString()
                )
            )
        }
        val payload = AccessibilityServiceHandle.withService { svc ->
            val path = svc.buildTapPath(x.toFloat(), y.toFloat())
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
                .build()
            val ok = svc.dispatchGestureAsync(gesture)
            svc.appendLog(
                ActionLogEntry(
                    type = "tap",
                    paramsSummary = "(${x.toInt()}, ${y.toInt()})",
                    success = ok,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            buildJsonObject {
                put("success", ok)
                if (!ok) put("reason", "gesture_cancelled_or_timeout")
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun longPressTool(): Tool = Tool(
    name = "long_press",
    description = """
        Long-press at absolute screen coordinates (pixels). Default duration is 600ms; provide
        duration_ms (>= 100, <= 5000) to override. Same return shape as tap.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x", buildJsonObject {
                    put("type", "number")
                    put("description", "Absolute x in pixels")
                })
                put("y", buildJsonObject {
                    put("type", "number")
                    put("description", "Absolute y in pixels")
                })
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Hold duration in milliseconds (default 600, range 100-5000)")
                })
            },
            required = listOf("x", "y")
        )
    },
    execute = { input ->
        AgentTurnTracker.recordAutomationAction()
        val x = coordOrError(input, "x")
        val y = coordOrError(input, "y")
        if (x == null || y == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "x and y are required and must be non-negative numbers")
                    }.toString()
                )
            )
        }
        val durationRaw = input.jsonObject["duration_ms"]?.jsonPrimitive?.longOrNull
            ?: DEFAULT_LONG_PRESS_MS
        if (durationRaw < 100L || durationRaw > 5000L) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "duration_ms must be between 100 and 5000")
                    }.toString()
                )
            )
        }
        val payload = AccessibilityServiceHandle.withService { svc ->
            val path = svc.buildTapPath(x.toFloat(), y.toFloat())
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationRaw))
                .build()
            val ok = svc.dispatchGestureAsync(gesture)
            svc.appendLog(
                ActionLogEntry(
                    type = "long_press",
                    paramsSummary = "(${x.toInt()}, ${y.toInt()}) ${durationRaw}ms",
                    success = ok,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            buildJsonObject {
                put("success", ok)
                if (!ok) put("reason", "gesture_cancelled_or_timeout")
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
