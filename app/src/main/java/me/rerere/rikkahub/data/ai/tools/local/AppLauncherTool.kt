package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import kotlinx.coroutines.delay
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
import me.rerere.rikkahub.service.RikkaAccessibilityService

private suspend fun waitForForegroundPackage(
    expectedPkg: String,
    timeoutMs: Long = 2500,
    stepMs: Long = 150,
): String? {
    val deadline = System.currentTimeMillis() + timeoutMs
    var current: String? = null
    while (System.currentTimeMillis() < deadline) {
        val svc = RikkaAccessibilityService.instance ?: return null
        current = svc.rootInActiveWindow?.packageName?.toString()
        if (current == expectedPkg) return current
        delay(stepMs)
    }
    return current
}

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
        // Wake the screen if it's off; without this the activity launches behind the lock
        // screen and any subsequent read_window_tree call will see no_active_window. We do
        // not bypass a real PIN/biometric keyguard - that requires the user.
        val wasOff = !ScreenWaker.isInteractive(context)
        val woke = if (wasOff) ScreenWaker.wakeIfOff(context) else false
        val keyLocked = ScreenWaker.isKeyguardLocked(context)
        val keySecure = ScreenWaker.isKeyguardSecure(context)

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            // Confirm the launched app actually took focus. If the user is physically
            // in another app (e.g. RikkaHub's own chat), the launch can be silently
            // ignored and subsequent screen-automation calls will loop on
            // wrong_foreground_app. Only meaningful when AccessibilityService is bound;
            // when unbound we cannot verify and report confirmed_foreground:false.
            val accessibilityRunning = RikkaAccessibilityService.instance != null
            val finalForeground: String? = if (accessibilityRunning && !keyLocked) {
                waitForForegroundPackage(pkg)
            } else null
            val confirmed = finalForeground == pkg
            if (accessibilityRunning && !keyLocked && !confirmed) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "launch_did_not_focus")
                            put("requested", pkg)
                            put("current_foreground", finalForeground.orEmpty())
                            put(
                                "recovery",
                                "The launch intent was dispatched but the OS did not move ${pkg} to the foreground within 2.5s. The user is likely actively viewing another app (often RikkaHub itself) — do NOT pass package_name to read_window_tree on this turn. Either ask the user to switch to ${pkg}, or call read_window_tree with no package_name guard so you can see whatever IS currently on screen."
                            )
                            if (wasOff) put("woke_screen", woke)
                        }.toString()
                    )
                )
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("package", pkg)
                        put("confirmed_foreground", confirmed)
                        if (!accessibilityRunning) {
                            put("note", "AccessibilityService not bound — could not verify foreground. Treat success as best-effort.")
                        }
                        if (wasOff) put("woke_screen", woke)
                        if (keyLocked) {
                            put("keyguard_locked", true)
                            put("keyguard_secure", keySecure)
                            if (keySecure) {
                                put("warn", "Screen is woken but PIN/biometric keyguard is up. The user must unlock for the launched app to be visible and drivable.")
                            }
                        }
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
        List installed apps. Default mode shows apps with a launcher activity (the ones the
        user sees in their app drawer). When `filter` is provided OR `include_no_launcher`
        is true, ALSO returns service-only addons that have no launcher icon - critical for
        detecting things like Termux:API (com.termux.api), Termux:Boot, etc., which are real
        installed packages but have no app-drawer entry. Each row carries
        {label, package, has_launcher}. Use this to discover the package name for launch_app
        or to confirm a specific addon package is present.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("filter", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional case-insensitive substring to match against label or package. Setting this auto-includes addon packages (no launcher activity).")
                })
                put("user_only", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true (default), only apps installed by the user; false includes system apps")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Cap on the number of returned apps (default 200)")
                })
                put("include_no_launcher", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, also returns packages that have no launcher activity (service-only addons). Auto-true when filter is non-empty.")
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
        val includeNoLauncher = (input.jsonObject["include_no_launcher"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false) || filter != null

        val pm = context.packageManager

        // Set of packages that DO have a launcher activity. Used to set has_launcher on each
        // row regardless of whether we walked them via the launcher query or the full
        // package list.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val launcherResolved = pm.queryIntentActivities(launcherIntent, 0)
        val launcherPkgs = launcherResolved.mapNotNull { it.activityInfo?.packageName }.toHashSet()

        data class Row(val label: String, val pkg: String, val hasLauncher: Boolean)

        val rows = mutableListOf<Row>()
        val seen = mutableSetOf<String>()

        // 1) Walk launcher apps first so we get clean labels via ResolveInfo.loadLabel.
        for (info in launcherResolved) {
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
            rows.add(Row(label, pkg, hasLauncher = true))
            if (rows.size >= limit) break
        }

        // 2) If asked, also walk the full installed-package list and pick up addons without
        //    a launcher activity. Termux:API (com.termux.api), Termux:Boot, dialer addons,
        //    etc. only show up here.
        if (rows.size < limit && includeNoLauncher) {
            @Suppress("DEPRECATION")
            val all = try { pm.getInstalledPackages(0) } catch (_: Throwable) { emptyList() }
            for (pkgInfo in all) {
                val pkg = pkgInfo.packageName ?: continue
                if (pkg in seen) continue
                seen.add(pkg)
                val appInfo = pkgInfo.applicationInfo ?: continue
                if (userOnly) {
                    val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val isUpdated = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    if (isSystem && !isUpdated) continue
                }
                val label = appInfo.loadLabel(pm)?.toString().orEmpty()
                if (filter != null && !label.lowercase().contains(filter) && !pkg.lowercase().contains(filter)) {
                    continue
                }
                rows.add(Row(label, pkg, hasLauncher = pkg in launcherPkgs))
                if (rows.size >= limit) break
            }
        }

        rows.sortWith(compareBy({ it.label.lowercase() }, { it.pkg }))

        val payload = buildJsonObject {
            put("count", rows.size)
            put("apps", buildJsonArray {
                rows.forEach { row ->
                    addJsonObject {
                        put("label", row.label)
                        put("package", row.pkg)
                        put("has_launcher", row.hasLauncher)
                    }
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/**
 * Hand a URL to whatever app is registered as the system handler for it (browsers for
 * http/https, the dialer for tel:, the maps app for geo:, etc.). Massively cheaper than
 * driving Chrome's URL bar via accessibility set_text — for a "search hello in chrome"
 * request, just call open_url("https://www.google.com/search?q=hello") and the browser
 * lands directly on the results page.
 *
 * The package_name override exists for cases where the user explicitly names a non-default
 * browser; otherwise the system shows whatever default is configured.
 */
fun openUrlTool(context: Context): Tool = Tool(
    name = "open_url",
    description = """
        Open a URL in the system's default handler app (browser for http/https, dialer for
        tel:, maps for geo:, mailto: for email, etc.). Strongly preferred over
        launch_app + screen automation when the user asks you to "search X in chrome",
        "open google.com", "call this number", "show me this address on a map", or any
        request that maps cleanly to a URL — typing into a browser URL bar via accessibility
        is unreliable and slow. Optionally pass package_name to force a specific app
        (e.g. com.android.chrome) when multiple handlers exist. Auto-wakes the screen if
        it was off.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Full URL with scheme. http(s):// for web, tel: for phone, geo:lat,lng for maps, mailto: for email.")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional: force a specific handler app (e.g. com.android.chrome). Omit for system default.")
                })
            },
            required = listOf("url")
        )
    },
    execute = { input ->
        val url = input.jsonObject["url"]?.jsonPrimitive?.contentOrNull
        if (url.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "url is required") }.toString()
                )
            )
        }
        val pkg = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }

        val wasOff = !ScreenWaker.isInteractive(context)
        val woke = if (wasOff) ScreenWaker.wakeIfOff(context) else false
        val keyLocked = ScreenWaker.isKeyguardLocked(context)
        val keySecure = ScreenWaker.isKeyguardSecure(context)

        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) setPackage(pkg)
        }
        // Resolve before dispatch so we can return a useful error when no app handles the URL
        // scheme (e.g. mailto: without a mail client installed).
        val resolved = context.packageManager.resolveActivity(intent, 0)
        if (resolved == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "no_handler")
                        put("recovery", "No installed app handles this URL scheme. Try a different URL or install an appropriate app first.")
                        put("url", url)
                        if (pkg != null) put("package", pkg)
                    }.toString()
                )
            )
        }
        try {
            context.startActivity(intent)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("url", url)
                        put("handler", resolved.activityInfo?.packageName ?: "")
                        if (wasOff) put("woke_screen", woke)
                        if (keyLocked) {
                            put("keyguard_locked", true)
                            put("keyguard_secure", keySecure)
                            if (keySecure) {
                                put("warn", "Screen is woken but PIN/biometric keyguard is up. The user must unlock for the URL handler to be visible.")
                            }
                        }
                    }.toString()
                )
            )
        } catch (t: Throwable) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "open_failed")
                        put("reason", t.message ?: t::class.java.simpleName)
                    }.toString()
                )
            )
        }
    }
)
