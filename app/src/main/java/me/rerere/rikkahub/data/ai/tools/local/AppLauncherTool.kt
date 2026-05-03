package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Launches an installed app by package name. Useful when the LLM needs to bring a specific
 * app to the foreground before performing screen automation gestures (taps/swipes), or when
 * the user explicitly asks to "open Termux" / "open Settings" / etc.
 *
 * The companion list_installed_apps tool exposes available package names so the model does
 * not have to guess.
 */
fun launchAppTool(context: Context): Tool = Tool(
    name = "launch_app",
    description = """
        Open an installed app on the device by its package name (e.g. com.termux, com.android.settings).
        Returns {success: true} if the launch intent was dispatched. If you do not know the package name,
        first call list_installed_apps to discover available packages. The app is brought to the
        foreground; screen-automation tools (tap, swipe, read_window_tree) can then drive its UI.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Application package id, e.g. com.termux")
                })
            },
            required = listOf("package_name")
        )
    },
    execute = { input ->
        val pkg = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
        if (pkg.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "package_name is required") }.toString()
                )
            )
        }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "no_launch_intent")
                        put("package", pkg)
                        put("recovery", "Package may not be installed or has no launcher activity. Call list_installed_apps to verify.")
                    }.toString()
                )
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("package", pkg)
                    }.toString()
                )
            )
        } catch (t: Throwable) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "launch_failed")
                        put("reason", t.message ?: t::class.java.simpleName)
                    }.toString()
                )
            )
        }
    }
)

fun listInstalledAppsTool(context: Context): Tool = Tool(
    name = "list_installed_apps",
    description = """
        List installed apps that have a launcher activity (i.e. the apps the user can see in
        their app drawer). Returns an array of {package, label} objects. Use this to discover
        the package name to pass to launch_app. Optional filter narrows the result by case-
        insensitive substring match against label OR package name. user_only=true (default)
        excludes system apps; pass false to include them.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("filter", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional case-insensitive substring to match against label or package")
                })
                put("user_only", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true (default), only apps installed by the user; false includes system apps")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Cap on the number of returned apps (default 200)")
                })
            }
        )
    },
    execute = { input ->
        val filter = input.jsonObject["filter"]?.jsonPrimitive?.contentOrNull
            ?.lowercase()?.takeIf { it.isNotBlank() }
        val userOnly = input.jsonObject["user_only"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: true
        val limit = input.jsonObject["limit"]?.jsonPrimitive?.contentOrNull
            ?.toIntOrNull()?.coerceIn(1, 1000) ?: 200

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(launcherIntent, 0)

        val seen = mutableSetOf<String>()
        val rows = mutableListOf<Pair<String, String>>()
        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg in seen) continue
            seen.add(pkg)
            if (userOnly) {
                try {
                    val flags = pm.getApplicationInfo(pkg, 0).flags
                    val isSystem = (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val isUpdated = (flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    if (isSystem && !isUpdated) continue
                } catch (_: PackageManager.NameNotFoundException) {
                    continue
                }
            }
            val label = info.loadLabel(pm)?.toString().orEmpty()
            if (filter != null && !label.lowercase().contains(filter) && !pkg.lowercase().contains(filter)) {
                continue
            }
            rows.add(label to pkg)
            if (rows.size >= limit) break
        }
        rows.sortBy { it.first.lowercase() }

        val payload = buildJsonObject {
            put("count", rows.size)
            put("apps", buildJsonArray {
                rows.forEach { (label, pkg) ->
                    addJsonObject {
                        put("label", label)
                        put("package", pkg)
                    }
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
