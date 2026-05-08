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
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.service.ActionLogEntry

private const val DEFAULT_SWIPE_MS = 300L

private fun numOrNull(input: kotlinx.serialization.json.JsonElement, key: String): Double? =
    input.jsonObject[key]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 }

fun swipeTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "swipe",
    description = """
        Swipe between two absolute screen coordinates. Default duration is 300ms; provide
        duration_ms (>= 50, <= 5000) to override. Returns {success: bool, reason?: string} or
        the standard service-not-active envelope.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("start_x", buildJsonObject { put("type", "number"); put("description", "Start x px") })
                put("start_y", buildJsonObject { put("type", "number"); put("description", "Start y px") })
                put("end_x", buildJsonObject { put("type", "number"); put("description", "End x px") })
                put("end_y", buildJsonObject { put("type", "number"); put("description", "End y px") })
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Swipe duration in milliseconds (default 300, range 50-5000)")
                })
            },
            required = listOf("start_x", "start_y", "end_x", "end_y")
        )
    },
    execute = { input ->
        AgentTurnTracker.recordAutomationAction()
        me.rerere.rikkahub.service.RikkaAccessibilityService.instance?.let { wakeScreenIfNeeded(it) }
        val sx = numOrNull(input, "start_x")
        val sy = numOrNull(input, "start_y")
        val ex = numOrNull(input, "end_x")
        val ey = numOrNull(input, "end_y")
        if (sx == null || sy == null || ex == null || ey == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "start_x, start_y, end_x, end_y are required and must be non-negative numbers")
                    }.toString()
                )
            )
        }
        val duration = input.jsonObject["duration_ms"]?.jsonPrimitive?.longOrNull ?: DEFAULT_SWIPE_MS
        if (duration < 50L || duration > 5000L) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "duration_ms must be between 50 and 5000")
                    }.toString()
                )
            )
        }
        val payload = AccessibilityServiceHandle.withService { svc ->
            val path = svc.buildSwipePath(sx.toFloat(), sy.toFloat(), ex.toFloat(), ey.toFloat())
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
                .build()
            val ok = svc.dispatchGestureAsync(gesture)
            svc.appendLog(
                ActionLogEntry(
                    type = "swipe",
                    paramsSummary = "(${sx.toInt()},${sy.toInt()})->(${ex.toInt()},${ey.toInt()}) ${duration}ms",
                    success = ok,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            buildJsonObject {
                put("success", ok)
                if (!ok) put("reason", "gesture_cancelled_or_timeout")
            }
        }
        streamer.streamIfHeadless(invocationContext, "Swipe (${sx.toInt()},${sy.toInt()})->(${ex.toInt()},${ey.toInt()}) ${duration}ms")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
