package me.rerere.rikkahub.data.ai.tools.local

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
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
import me.rerere.rikkahub.service.ActionLogEntry

private val ALLOWED_BY = setOf("text", "content_description", "view_id_resource_name")

private fun findMatches(
    root: AccessibilityNodeInfo,
    by: String,
    value: String,
): List<AccessibilityNodeInfo> {
    return when (by) {
        "view_id_resource_name" -> root.findAccessibilityNodeInfosByViewId(value).orEmpty()
        "text" -> root.findAccessibilityNodeInfosByText(value).orEmpty().filter {
            it.text?.toString() == value
        }
        "content_description" -> {
            val out = mutableListOf<AccessibilityNodeInfo>()
            fun walk(n: AccessibilityNodeInfo) {
                if (n.contentDescription?.toString() == value) out.add(n)
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { walk(it) }
                }
            }
            walk(root)
            out
        }
        else -> emptyList()
    }
}

private fun parseSelector(input: kotlinx.serialization.json.JsonElement): Triple<String?, String?, String?> {
    val by = input.jsonObject["by"]?.jsonPrimitive?.contentOrNull
    val value = input.jsonObject["value"]?.jsonPrimitive?.contentOrNull
    val pkg = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
    return Triple(by, value, pkg)
}

fun findNodeTool(): Tool = Tool(
    name = "find_node",
    description = """
        Find accessibility nodes in the active window matching a selector. by: text |
        content_description | view_id_resource_name. Returns {matches: [...]} with at most 50
        node summaries. Use read_window_tree first to see what's available, then this for
        targeted lookups.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("by", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("text"); add("content_description"); add("view_id_resource_name") })
                    put("description", "Selector axis")
                })
                put("value", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact value to match")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional foreground package guard")
                })
            },
            required = listOf("by", "value")
        )
    },
    execute = { input ->
        val (by, value, pkgFilter) = parseSelector(input)
        if (by == null || by !in ALLOWED_BY || value == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "by must be one of [text, content_description, view_id_resource_name] and value is required")
                    }.toString()
                )
            )
        }
        val payload = AccessibilityServiceHandle.withService { svc ->
            val root = svc.rootInActiveWindow
            if (root == null) {
                return@withService buildJsonObject {
                    put("error", "no_active_window")
                    put("matches", buildJsonArray {})
                }
            }
            val pkg = root.packageName?.toString().orEmpty()
            if (pkgFilter != null && pkgFilter != pkg) {
                return@withService buildJsonObject {
                    put("error", "wrong_foreground_app")
                    put("current", pkg)
                    put("matches", buildJsonArray {})
                }
            }
            val matches = findMatches(root, by, value).take(50)
            svc.appendLog(
                ActionLogEntry(
                    type = "find_node",
                    paramsSummary = "$by=\"${value.take(40)}\" -> ${matches.size}",
                    success = true,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            buildJsonObject {
                put("matches", buildJsonArray {
                    matches.forEachIndexed { i, n -> add(nodeToJson(n, root.windowId, i)) }
                })
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun clickNodeTool(): Tool = Tool(
    name = "click_node",
    description = """
        Find an accessibility node by selector and tap it via ACTION_CLICK. If the matched node
        is not clickable but a clickable ancestor exists, the ancestor is clicked instead.
        nth (default 0) disambiguates when multiple nodes match. Returns {success, clicked, ...}
        or a structured error.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("by", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("text"); add("content_description"); add("view_id_resource_name") })
                })
                put("value", buildJsonObject { put("type", "string") })
                put("package_name", buildJsonObject { put("type", "string") })
                put("nth", buildJsonObject {
                    put("type", "integer")
                    put("description", "Zero-based index when multiple nodes match (default 0)")
                })
            },
            required = listOf("by", "value")
        )
    },
    execute = { input ->
        val (by, value, pkgFilter) = parseSelector(input)
        val nth = input.jsonObject["nth"]?.jsonPrimitive?.intOrNull ?: 0
        if (by == null || by !in ALLOWED_BY || value == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "by must be one of [text, content_description, view_id_resource_name] and value is required")
                    }.toString()
                )
            )
        }
        if (nth < 0) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "nth must be >= 0")
                    }.toString()
                )
            )
        }
        val payload = AccessibilityServiceHandle.withService { svc ->
            val root = svc.rootInActiveWindow
            if (root == null) {
                return@withService buildJsonObject { put("error", "no_active_window") }
            }
            val pkg = root.packageName?.toString().orEmpty()
            if (pkgFilter != null && pkgFilter != pkg) {
                return@withService buildJsonObject {
                    put("error", "wrong_foreground_app")
                    put("current", pkg)
                }
            }
            val matches = findMatches(root, by, value)
            if (matches.isEmpty()) {
                return@withService buildJsonObject { put("error", "no_match") }
            }
            if (nth >= matches.size) {
                return@withService buildJsonObject {
                    put("error", "nth_out_of_range")
                    put("available", matches.size)
                }
            }
            val target = matches[nth]
            val clickable = svc.resolveClickable(target)
                ?: return@withService buildJsonObject { put("error", "no_clickable_ancestor") }
            val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            svc.appendLog(
                ActionLogEntry(
                    type = "click_node",
                    paramsSummary = "$by=\"${value.take(40)}\" nth=$nth -> ${if (ok) "ok" else "fail"}",
                    success = ok,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            buildJsonObject {
                put("success", ok)
                put("clicked", buildJsonObject {
                    val rect = android.graphics.Rect()
                    clickable.getBoundsInScreen(rect)
                    put("bounds", buildJsonArray {
                        add(rect.left); add(rect.top); add(rect.right); add(rect.bottom)
                    })
                    put("text", clickable.text?.toString() ?: "")
                    put("resolved_via_ancestor", clickable !== target)
                })
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
