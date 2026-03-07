package me.rerere.rikkahub.data.ai.tools

import android.content.Context
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
import me.rerere.rikkahub.data.ai.tools.termux.TermuxCommandManager
import me.rerere.rikkahub.data.ai.tools.termux.TermuxRunCommandRequest
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.model.Assistant
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
    @SerialName("termux_exec")
    data object TermuxExec : LocalToolOption()

    @Serializable
    @SerialName("termux_python")
    data object TermuxPython : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()
}

class LocalTools(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val termuxCommandManager: TermuxCommandManager,
    private val eventBus: AppEventBus,
) {
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
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                kotlinx.serialization.json.buildJsonArray {
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

    private fun termuxExecTool(
        needsApproval: Boolean,
        settingsStore: SettingsStore,
        termuxCommandManager: TermuxCommandManager,
    ): Tool {
        return Tool(
            name = "termux_exec",
            description = """
                Run a shell command in local Termux and return stdout plus stderr as plain text.
            """.trimIndent().replace("\n", " "),
            needsApproval = needsApproval,
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "Shell command to execute")
                        })
                    },
                    required = listOf("command"),
                )
            },
            execute = execute@{ input ->
                val params = input.jsonObject
                val command = params["command"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: error("command is required")
                val settings = settingsStore.settingsFlow.value

                val result = runCatching {
                    termuxCommandManager.run(
                        TermuxRunCommandRequest(
                            commandPath = TERMUX_BASH_PATH,
                            arguments = listOf("-lc", command),
                            workdir = settings.termuxWorkdir,
                            background = settings.termuxRunInBackground,
                            timeoutMs = settings.termuxTimeoutMs,
                            label = "RikkaHub termux_exec",
                        )
                    )
                }.getOrElse { e ->
                    val message = buildString {
                        append(e.message ?: e.javaClass.name)
                        append("\n")
                        append(
                            "Ensure Termux is installed; set allow-external-apps=true in " +
                                "~/.termux/termux.properties; grant com.termux.permission.RUN_COMMAND to this app " +
                                "in system settings."
                        )
                    }
                    return@execute listOf(UIMessagePart.Text(message))
                }

                val output = buildString {
                    append(result.stdout)
                    if (result.stderr.isNotBlank()) {
                        if (isNotEmpty() && !endsWith('\n')) append('\n')
                        append(result.stderr)
                    }
                    val errMsg = result.errMsg
                    if (!errMsg.isNullOrBlank()) {
                        if (isNotEmpty() && !endsWith('\n')) append('\n')
                        append(errMsg)
                    }
                }
                listOf(UIMessagePart.Text(output))
            }
        )
    }

    private fun termuxPythonTool(
        needsApproval: Boolean,
        settingsStore: SettingsStore,
        termuxCommandManager: TermuxCommandManager,
    ): Tool {
        return Tool(
            name = "termux_python",
            description = """
                Run Python code in local Termux and return stdout plus stderr as plain text.
            """.trimIndent().replace("\n", " "),
            needsApproval = needsApproval,
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("code", buildJsonObject {
                            put("type", "string")
                            put("description", "Python code to execute")
                        })
                    },
                    required = listOf("code"),
                )
            },
            execute = execute@{ input ->
                val params = input.jsonObject
                val code = params["code"]?.jsonPrimitive?.contentOrNull ?: error("code is required")
                val settings = settingsStore.settingsFlow.value

                val termuxResult = runCatching {
                    termuxCommandManager.run(
                        TermuxRunCommandRequest(
                            commandPath = TERMUX_PYTHON3_PATH,
                            arguments = listOf("-"),
                            workdir = settings.termuxWorkdir,
                            stdin = code,
                            background = settings.termuxRunInBackground,
                            timeoutMs = settings.termuxTimeoutMs,
                            label = "RikkaHub termux_python",
                        )
                    )
                }.getOrElse { e ->
                    val message = buildString {
                        append(e.message ?: e.javaClass.name)
                        append("\n")
                        append("Ensure Termux is installed and Python is installed in Termux (pkg install python).")
                    }
                    return@execute listOf(UIMessagePart.Text(message))
                }

                val output = buildString {
                    append(termuxResult.stdout)
                    if (termuxResult.stderr.isNotBlank()) {
                        if (isNotEmpty() && !endsWith('\n')) append('\n')
                        append(termuxResult.stderr)
                    }
                    val errMsg = termuxResult.errMsg
                    if (!errMsg.isNullOrBlank()) {
                        if (isNotEmpty() && !endsWith('\n')) append('\n')
                        append(errMsg)
                    }
                }

                listOf(UIMessagePart.Text(output))
            }
        )
    }

    fun getTools(
        options: List<LocalToolOption>,
        assistant: Assistant,
        overrideTermuxNeedsApproval: Boolean? = null,
    ): List<Tool> {
        val termuxNeedsApproval = overrideTermuxNeedsApproval ?: settingsStore.settingsFlow.value.termuxNeedsApproval
        val enabled = options.toSet()
        return LocalToolCatalog.options.mapNotNull { option ->
            if (!enabled.contains(option)) return@mapNotNull null
            when (option) {
                LocalToolOption.JavascriptEngine -> javascriptTool
                LocalToolOption.TimeInfo -> timeTool
                LocalToolOption.Clipboard -> clipboardTool
                LocalToolOption.TermuxExec -> {
                    termuxExecTool(
                        needsApproval = termuxNeedsApproval,
                        settingsStore = settingsStore,
                        termuxCommandManager = termuxCommandManager,
                    )
                }

                LocalToolOption.TermuxPython -> {
                    termuxPythonTool(
                        needsApproval = termuxNeedsApproval,
                        settingsStore = settingsStore,
                        termuxCommandManager = termuxCommandManager,
                    )
                }

                LocalToolOption.Tts -> ttsTool
                LocalToolOption.AskUser -> askUserTool
            }
        }
    }

    companion object {
        private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
        private const val TERMUX_PYTHON3_PATH = "/data/data/com.termux/files/usr/bin/python3"
    }
}
