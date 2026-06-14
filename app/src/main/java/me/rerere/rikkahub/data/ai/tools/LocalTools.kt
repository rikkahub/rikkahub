package me.rerere.rikkahub.data.ai.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("open_app")
    data object OpenApp : LocalToolOption()

    @Serializable
    @SerialName("list_app")
    data object ListApp : LocalToolOption()
}

/** A launchable app: package name + human-readable label. */
data class AppInfo(val packageName: String, val label: String)

/**
 * Intent-only app launch/enumeration seam. Pulled behind an interface so the no-throw payload logic
 * of [openAppTool]/[listAppTool] is exercisable on the pure JVM without Android — the production
 * implementation is [AndroidAppLauncher]; tests inject a fake.
 */
interface AppLauncher {
    /**
     * Launch the app identified by [packageName]. Returns true if a launch intent existed and the
     * activity was started, false if the package is not installed / has no launchable activity.
     */
    fun launch(packageName: String): Boolean

    /** Enumerate launchable apps (package + label) via the LAUNCHER intent filter. */
    fun listApps(): List<AppInfo>
}

/**
 * Production [AppLauncher] over a real Android [Context]. `open_app` uses
 * [PackageManager.getLaunchIntentForPackage] (null ⇒ not launchable ⇒ caller reports an error, never
 * throws) and starts it with [Intent.FLAG_ACTIVITY_NEW_TASK] (required: launched from the non-Activity
 * app context). `list_app` enumerates via ACTION_MAIN + CATEGORY_LAUNCHER and returns package + label
 * only — no QUERY_ALL_PACKAGES; a `<queries>` LAUNCHER filter in the manifest scopes visibility.
 */
class AndroidAppLauncher(private val context: Context) : AppLauncher {
    override fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        // A non-null launch intent does NOT guarantee startActivity succeeds: the package can be
        // uninstalled between resolve and launch (ActivityNotFoundException) or the target activity may
        // not be exported (SecurityException). Honor the interface contract — every launch failure maps
        // to false, never an escaping throw — so the caller reports the structured error payload. The
        // failure is fully surfaced to the model via that payload, not swallowed.
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    override fun listApps(): List<AppInfo> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(mainIntent, 0).map { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo
            AppInfo(
                packageName = activityInfo.packageName,
                label = resolveInfo.loadLabel(pm).toString(),
            )
        }
    }
}

/**
 * `open_app(package)` — launch an installed app by package name. Returns `{success:false,error}` (never
 * throws) when the package has no launchable activity, so the model can see why and recover. Low-risk,
 * Intent-only: no AccessibilityService, no lease, no approval gate.
 */
fun openAppTool(launcher: AppLauncher): Tool = Tool(
    name = "open_app",
    description = "Launch an installed app by its package name. " +
        "Returns an error if the package is not installed or has no launchable activity.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package", buildJsonObject {
                    put("type", "string")
                    put("description", "The exact package name, e.g. com.android.settings")
                })
            },
            required = listOf("package"),
        )
    },
    execute = {
        val pkg = it.jsonObject["package"]?.jsonPrimitive?.contentOrNull
            ?: error("package is required")
        // Spec contract: open_app returns a structured {success:false,error} payload, NEVER throws.
        // The launcher's no-throw contract is enforced at this boundary too — a misbehaving launcher
        // (or a launch failure surfaced as a throw) maps to launched=false, not an escaping exception
        // that the runtime would otherwise rewrite into a generic stacktrace error.
        val launched = runCatching { launcher.launch(pkg) }.getOrDefault(false)
        val payload = if (!launched) {
            buildJsonObject {
                put("success", false)
                put("error", "Package '$pkg' could not be launched (not installed, no launchable activity, or launch was rejected).")
            }
        } else {
            buildJsonObject {
                put("success", true)
                put("package", pkg)
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

/**
 * `list_app()` — enumerate launchable apps via the LAUNCHER intent filter. Returns each app's package
 * name + label only (no icons / install metadata) to keep the tool payload small. Low-risk, no approval.
 */
fun listAppTool(launcher: AppLauncher): Tool = Tool(
    name = "list_app",
    description = "List the installed apps that have a launcher entry. " +
        "Returns each app's package name and display label.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val apps = launcher.listApps()
        val payload = buildJsonObject {
            put("apps", buildJsonArray {
                apps.forEach { app ->
                    add(buildJsonObject {
                        put("package", app.packageName)
                        put("label", app.label)
                    })
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

class LocalTools(private val context: Context, private val eventBus: AppEventBus) {
    private val appLauncher: AppLauncher = AndroidAppLauncher(context)

    val javascriptTool by lazy {
        Tool(
            name = "eval_javascript",
            description = """
                Execute JavaScript code using QuickJS engine (ES2020).
                The result is the value of the last expression in the code.
                For calculations with decimals, use toFixed() to control precision.
                Console output (log/info/warn/error) is captured and returned in 'logs' field.
                No DOM or Node.js APIs available.
                Example: '1 + 2' returns 3; 'const x = 5; x * 2' returns 10.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("code", buildJsonObject {
                            put("type", "string")
                            put("description", "The JavaScript code to execute")
                        })
                    },
                    required = listOf("code")
                )
            },
            execute = {
                val logs = arrayListOf<String>()
                val context = QuickJSContext.create()
                context.setConsole(object : QuickJSContext.Console {
                    override fun log(info: String?) {
                        logs.add("[LOG] $info")
                    }

                    override fun info(info: String?) {
                        logs.add("[INFO] $info")
                    }

                    override fun warn(info: String?) {
                        logs.add("[WARN] $info")
                    }

                    override fun error(info: String?) {
                        logs.add("[ERROR] $info")
                    }
                })
                val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull
                val result = context.evaluate(code)
                val payload = buildJsonObject {
                    if (logs.isNotEmpty()) {
                        put("logs", JsonPrimitive(logs.joinToString("\n")))
                    }
                    put(
                        key = "result",
                        element = when (result) {
                            null -> JsonNull
                            is QuickJSObject -> JsonPrimitive(result.stringify())
                            else -> JsonPrimitive(result.toString())
                        }
                    )
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val timeTool by lazy {
        Tool(
            name = "get_time_info",
            description = """
                Get the current local date and time info from the device.
                Returns year/month/day, weekday, ISO date/time strings, timezone, and timestamp.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject { }
                )
            },
            execute = {
                val now = ZonedDateTime.now()
                val date = now.toLocalDate()
                val time = now.toLocalTime().withNano(0)
                val weekday = now.dayOfWeek
                val payload = buildJsonObject {
                    put("year", date.year)
                    put("month", date.monthValue)
                    put("day", date.dayOfMonth)
                    put("weekday", weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                    put("weekday_en", weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                    put("weekday_index", weekday.value)
                    put("date", date.toString())
                    put("time", time.toString())
                    put("datetime", now.withNano(0).toString())
                    put("timezone", now.zone.id)
                    put("utc_offset", now.offset.id)
                    put("timestamp_ms", now.toInstant().toEpochMilli())
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val clipboardTool by lazy {
        Tool(
            name = "clipboard_tool",
            description = """
                Read or write plain text from the device clipboard.
                Use action: read or write. For write, provide text.
                Do NOT write to the clipboard unless the user has explicitly requested it.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                buildJsonArray {
                                    add("read")
                                    add("write")
                                }
                            )
                            put("description", "Operation to perform: read or write")
                        })
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "Text to write to the clipboard (required for write)")
                        })
                    },
                    required = listOf("action")
                )
            },
            execute = {
                val params = it.jsonObject
                val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
                when (action) {
                    "read" -> {
                        val payload = buildJsonObject {
                            put("text", context.readClipboardText())
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }

                    "write" -> {
                        val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
                        context.writeClipboardText(text)
                        val payload = buildJsonObject {
                            put("success", true)
                            put("text", text)
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }

                    else -> error("unknown action: $action, must be one of [read, write]")
                }
            }
        )
    }

    val ttsTool by lazy {
        Tool(
            name = "text_to_speech",
            description = """
                Speak text aloud to the user using the device's text-to-speech engine.
                Use this when the user asks you to read something aloud, or when audio output is appropriate.
                The tool returns immediately; audio plays in the background on the device.
                Provide natural, readable text without markdown formatting.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "The text to speak aloud")
                        })
                    },
                    required = listOf("text")
                )
            },
            execute = {
                val text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    ?: error("text is required")
                eventBus.emit(AppEvent.Speak(text))
                val payload = buildJsonObject {
                    put("success", true)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val askUserTool by lazy {
        Tool(
            name = "ask_user",
            description = """
                Ask the user one or more questions when you need clarification, additional information, or confirmation.
                Each question can optionally provide a list of suggested options for the user to choose from.
                The user may select an option or provide their own free-text answer for each question.
                The answers will be returned as a JSON object mapping question IDs to the user's responses.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("questions", buildJsonObject {
                            put("type", "array")
                            put("description", "List of questions to ask the user")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("id", buildJsonObject {
                                        put("type", "string")
                                        put("description", "Unique identifier for this question")
                                    })
                                    put("question", buildJsonObject {
                                        put("type", "string")
                                        put("description", "The question text to display to the user")
                                    })
                                    put("options", buildJsonObject {
                                        put("type", "array")
                                        put(
                                            "description",
                                            "Optional list of suggested options for the user to choose from"
                                        )
                                        put("items", buildJsonObject {
                                            put("type", "string")
                                        })
                                    })
                                    put("selection_type", buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "enum",
                                            buildJsonArray {
                                                add("text")
                                                add("single")
                                                add("multi")
                                            }
                                        )
                                        put(
                                            "description",
                                            "Answer type: text (free text input, default), single (select exactly one option), multi (select one or more options)"
                                        )
                                    })
                                })
                                put("required", buildJsonArray {
                                    add("id")
                                    add("question")
                                })
                            })
                        })
                    },
                    required = listOf("questions")
                )
            },
            needsApproval = true,
            execute = {
                error("ask_user tool should be handled by HITL flow")
            }
        )
    }

    val openAppTool by lazy { openAppTool(appLauncher) }

    val listAppTool by lazy { listAppTool(appLauncher) }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        tools.addAll(toolsFor(options, appLauncher))
        return tools
    }

    companion object {
        /**
         * Dispatch the Intent-only app tools ([openAppTool]/[listAppTool]) for the enabled options
         * over the given [appLauncher]. Extracted so the open_app/list_app wiring is JVM-testable
         * without constructing a [LocalTools] (which needs an Android [Context]).
         */
        fun toolsFor(options: List<LocalToolOption>, appLauncher: AppLauncher): List<Tool> {
            val tools = mutableListOf<Tool>()
            if (options.contains(LocalToolOption.OpenApp)) {
                tools.add(openAppTool(appLauncher))
            }
            if (options.contains(LocalToolOption.ListApp)) {
                tools.add(listAppTool(appLauncher))
            }
            return tools
        }
    }
}
