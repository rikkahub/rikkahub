package me.rerere.rikkahub.data.ai.tools.local

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.service.ActionLogEntry

private const val DEFAULT_MAX_NODES = 500
private const val MAX_NODES_HARD_CEILING = 2000

internal fun nodeToJson(
    node: AccessibilityNodeInfo,
    windowId: Int,
    traversalIndex: Int,
): JsonObject {
    val rect = Rect()
    node.getBoundsInScreen(rect)
    return buildJsonObject {
        put("node_id", "${windowId}:${traversalIndex}")
        put("bounds", buildJsonArray {
            add(rect.left); add(rect.top); add(rect.right); add(rect.bottom)
        })
        put("class", node.className?.toString() ?: "")
        node.text?.toString()?.takeIf { it.isNotEmpty() }?.let { put("text", it) }
        node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let {
            put("content_description", it)
        }
        node.viewIdResourceName?.takeIf { it.isNotEmpty() }?.let { put("view_id", it) }
        put("clickable", node.isClickable)
        put("scrollable", node.isScrollable)
        put("editable", node.isEditable)
        put("enabled", node.isEnabled)
    }
}

internal fun defaultFilter(n: AccessibilityNodeInfo, depth: Int): Boolean {
    if (!n.isVisibleToUser) return false
    if (n.isClickable || n.isScrollable || n.isEditable) return true
    val text = n.text?.toString().orEmpty()
    val cd = n.contentDescription?.toString().orEmpty()
    return text.isNotEmpty() || cd.isNotEmpty()
}

fun readWindowTreeTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "read_window_tree",
    description = "Snapshot of the active window's a11y node tree. Default filters to visible nodes that are clickable / scrollable / editable / have text or content_description. verbose=true skips the filter (use sparingly). max_nodes caps result (default 500, max 2000). package_name optionally restricts + errors if the foreground app doesn't match.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("verbose", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, return all nodes (default false applies the filter)")
                })
                put("max_nodes", buildJsonObject {
                    put("type", "integer")
                    put("description", "Cap on returned nodes (default 500, max 2000)")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "If set, return wrong_foreground_app error if the foreground app does not match")
                })
            }
        )
    },
    execute = { input ->
        me.rerere.rikkahub.service.RikkaAccessibilityService.instance?.let { wakeScreenIfNeeded(it) }
        val verbose = input.jsonObject["verbose"]?.jsonPrimitive?.booleanOrNull ?: false
        val maxNodesRaw = input.jsonObject["max_nodes"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_NODES
        val maxNodes = maxNodesRaw.coerceIn(1, MAX_NODES_HARD_CEILING)
        val pkgFilter = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull

        val payload = AccessibilityServiceHandle.withService { svc ->
            val root = svc.rootInActiveWindow
            if (root == null) {
                svc.appendLog(
                    ActionLogEntry(
                        type = "read_window_tree",
                        paramsSummary = "no_active_window",
                        success = false,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
                return@withService buildJsonObject {
                    put("error", "no_active_window")
                    put("nodes", buildJsonArray { })
                }
            }
            val pkg = root.packageName?.toString().orEmpty()
            if (pkgFilter != null && pkgFilter != pkg) {
                svc.appendLog(
                    ActionLogEntry(
                        type = "read_window_tree",
                        paramsSummary = "wrong_pkg current=$pkg",
                        success = false,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
                return@withService buildJsonObject {
                    put("error", "wrong_foreground_app")
                    put("current", pkg)
                    put("nodes", buildJsonArray { })
                }
            }
            val nodes = mutableListOf<JsonObject>()
            val (emitted, seen, truncated) = svc.traverseTree(
                root = root,
                filter = if (verbose) ({ _, _ -> true }) else (::defaultFilter),
                cap = maxNodes,
                emit = { n, _, idx ->
                    nodes.add(nodeToJson(n, root.windowId, idx))
                }
            )
            svc.appendLog(
                ActionLogEntry(
                    type = "read_window_tree",
                    paramsSummary = "$emitted/$seen nodes, pkg=$pkg" + if (verbose) ", verbose" else "",
                    success = true,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            buildJsonObject {
                put("nodes", buildJsonArray { nodes.forEach { add(it) } })
                put("truncated", truncated)
                put("total_seen", seen)
                put("package", pkg)
                root.window?.title?.toString()?.let { put("window_title", it) } ?: put("window_title", "")
            }
        }
        streamer.streamIfHeadless(invocationContext, "ReadWindowTree")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
