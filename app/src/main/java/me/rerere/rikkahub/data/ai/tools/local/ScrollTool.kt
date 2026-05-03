package me.rerere.rikkahub.data.ai.tools.local

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.ActionLogEntry
import me.rerere.rikkahub.service.RikkaAccessibilityService

private val ALLOWED_DIRECTIONS = setOf("up", "down", "left", "right")
private const val SCROLL_GESTURE_MS = 300L

private fun findScrollableAt(
    root: AccessibilityNodeInfo,
    x: Int,
    y: Int,
): AccessibilityNodeInfo? {
    val rect = Rect()
    fun matches(n: AccessibilityNodeInfo): Boolean {
        n.getBoundsInScreen(rect)
        return n.isScrollable && rect.contains(x, y)
    }
    fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c)?.let { return it }
        }
        return if (matches(n)) n else null
    }
    return walk(root)
}

private fun findFirstScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (root.isScrollable) return root
    for (i in 0 until root.childCount) {
        val c = root.getChild(i) ?: continue
        findFirstScrollable(c)?.let { return it }
    }
    return null
}

fun scrollTool(): Tool = Tool(
    name = "scroll",
    description = """
        Scroll the active window in the given direction (up/down/left/right). If x and y are
        provided, scrolls the scrollable container at that point; otherwise scrolls the first
        scrollable container found. Falls back to a swipe gesture if no scrollable container
        can be located. Returns {success: bool, reason?: string}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("direction", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("up"); add("down"); add("left"); add("right")
                    })
                })
                put("x", buildJsonObject { put("type", "number"); put("description", "Optional anchor px") })
                put("y", buildJsonObject { put("type", "number"); put("description", "Optional anchor px") })
            },
            required = listOf("direction")
        )
    },
    execute = { input ->
        val direction = input.jsonObject["direction"]?.jsonPrimitive?.contentOrNull
        if (direction == null || direction !in ALLOWED_DIRECTIONS) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "direction must be one of [up, down, left, right]")
                    }.toString()
                )
            )
        }
        val anchorX = input.jsonObject["x"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() }
        val anchorY = input.jsonObject["y"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() }

        val payload = AccessibilityServiceHandle.withService { svc ->
            val root = svc.rootInActiveWindow
                ?: return@withService buildJsonObject { put("error", "no_active_window") }

            val target = if (anchorX != null && anchorY != null) {
                findScrollableAt(root, anchorX.toInt(), anchorY.toInt())
            } else {
                findFirstScrollable(root)
            }

            val ok = if (target != null) {
                val action = when (direction) {
                    "down", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                target.performAction(action)
            } else {
                // Fallback: dispatch a swipe gesture across the screen center
                val rect = Rect()
                root.getBoundsInScreen(rect)
                val cx = (rect.left + rect.right) / 2f
                val cy = (rect.top + rect.bottom) / 2f
                val w = (rect.width() / 3f).coerceAtLeast(100f)
                val h = (rect.height() / 3f).coerceAtLeast(100f)
                val (sx, sy, ex, ey) = when (direction) {
                    "down" -> floatArrayOf(cx, cy + h, cx, cy - h)
                    "up" -> floatArrayOf(cx, cy - h, cx, cy + h)
                    "right" -> floatArrayOf(cx + w, cy, cx - w, cy)
                    else /* left */ -> floatArrayOf(cx - w, cy, cx + w, cy)
                }.let { arr -> Quadruple(arr[0], arr[1], arr[2], arr[3]) }
                val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
                svc.dispatchGestureAsync(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0L, SCROLL_GESTURE_MS))
                        .build()
                )
            }
            svc.appendLog(
                ActionLogEntry(
                    type = "scroll",
                    paramsSummary = "$direction" + (if (target != null) " (node)" else " (fallback swipe)"),
                    success = ok,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            buildJsonObject {
                put("success", ok)
                if (!ok) put("reason", "no_scroll_action_accepted")
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
