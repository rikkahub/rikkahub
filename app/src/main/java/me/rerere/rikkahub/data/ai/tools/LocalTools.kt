package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.termux.TERMUX_PTY_DEFAULT_COLUMNS
import me.rerere.rikkahub.data.ai.tools.termux.TERMUX_PTY_DEFAULT_ROWS
import me.rerere.rikkahub.data.ai.tools.termux.TermuxPtyActionResponse
import me.rerere.rikkahub.data.ai.tools.termux.TermuxCommandManager
import me.rerere.rikkahub.data.ai.tools.termux.TermuxPtySessionListResponse
import me.rerere.rikkahub.data.ai.tools.termux.TermuxPtySessionManager
import me.rerere.rikkahub.data.ai.tools.termux.TermuxRunCommandRequest
import me.rerere.rikkahub.data.ai.tools.termux.TermuxPtyToolResponse
import me.rerere.rikkahub.data.ai.tools.termux.encode
import me.rerere.rikkahub.data.ai.tools.termux.toCommandErrorToolResponse
import me.rerere.rikkahub.data.ai.tools.termux.toPtyErrorToolResponse
import me.rerere.rikkahub.data.ai.tools.termux.toToolResponse
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
    private val json: Json,
    private val settingsStore: SettingsStore,
    private val termuxCommandManager: TermuxCommandManager,
    private val termuxPtySessionManager: TermuxPtySessionManager,
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
                Do NOT write to the clipboard unless the user has explicitly requested it.
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
                                    put("selection_type", buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "enum",
                                            kotlinx.serialization.json.buildJsonArray {
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

    private fun termuxExecTool(
        needsApproval: Boolean,
        ptyInteractiveEnabled: Boolean,
        settingsStore: SettingsStore,
        termuxCommandManager: TermuxCommandManager,
        termuxPtySessionManager: TermuxPtySessionManager,
        assistant: Assistant,
    ): Tool {
        val workdir = settingsStore.settingsFlow.value.termuxWorkdir
        val baseDescription = if (ptyInteractiveEnabled) {
            """
                Run a shell command in local Termux. Current workspace path: $workdir.
                Use default mode for one-shot commands. Non-tty responses are JSON with output and status fields.
                Set tty=true only for interactive or long-running commands.
                If the response includes session_id, continue with write_stdin.
                Optional overrides: timeout_ms for non-tty; yield_time_ms, max_output_chars, cols, rows for tty.
            """.trimIndent().replace("\n", " ")
        } else {
            """
                Run a shell command in local Termux. Current workspace path: $workdir.
                Returns JSON with output and status fields.
                Optional overrides: timeout_ms.
            """.trimIndent().replace("\n", " ")
        }
        return Tool(
            name = "termux_exec",
            description = buildToolDescription(
                baseDescription = baseDescription,
                assistant = assistant,
                toolName = "termux_exec",
            ),
            needsApproval = needsApproval,
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "Shell command to execute")
                        })
                        put("timeout_ms", buildJsonObject {
                            put("type", "integer")
                            put(
                                "description",
                                if (ptyInteractiveEnabled) {
                                    "Optional timeout override for non-tty commands"
                                } else {
                                    "Optional timeout override"
                                }
                            )
                        })
                        if (ptyInteractiveEnabled) {
                            put("tty", buildJsonObject {
                                put("type", "boolean")
                                put("description", "Set to true only for interactive or long-running commands")
                            })
                            put("yield_time_ms", buildJsonObject {
                                put("type", "integer")
                                put("description", "Optional PTY yield window override when tty=true")
                            })
                            put("max_output_chars", buildJsonObject {
                                put("type", "integer")
                                put("description", "Optional PTY output chunk size override when tty=true")
                            })
                            put("cols", buildJsonObject {
                                put("type", "integer")
                                put("description", "Optional terminal column count when tty=true")
                            })
                            put("rows", buildJsonObject {
                                put("type", "integer")
                                put("description", "Optional terminal row count when tty=true")
                            })
                        }
                    },
                    required = listOf("command"),
                )
            },
            execute = execute@{ input ->
                val params = input.jsonObject
                val command = params["command"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: error("command is required")
                val settings = settingsStore.settingsFlow.value
                val tty = params["tty"]?.jsonPrimitive?.booleanOrNull == true

                if (tty && !settings.termuxPtyInteractiveEnabled) {
                    return@execute listOf(
                        UIMessagePart.Text(
                            IllegalStateException("PTY interactive commands are disabled in Termux settings.")
                                .toCommandErrorToolResponse()
                                .encode(json)
                        )
                    )
                }

                if (tty) {
                    val yieldTimeMs = (params.optionalLong("yield_time_ms") ?: settings.termuxPtyYieldTimeMs)
                        .coerceAtLeast(0L)
                    val maxOutputChars = (params.optionalInt("max_output_chars") ?: settings.termuxPtyMaxOutputChars)
                        .coerceAtLeast(256)
                    val cols = (params.optionalInt("cols") ?: TERMUX_PTY_DEFAULT_COLUMNS)
                        .coerceAtLeast(20)
                    val rows = (params.optionalInt("rows") ?: TERMUX_PTY_DEFAULT_ROWS)
                        .coerceAtLeast(5)
                    val response = runCatching {
                        termuxPtySessionManager.startSession(
                            command = command,
                            workdir = settings.termuxWorkdir,
                            yieldTimeMs = yieldTimeMs,
                            maxOutputChars = maxOutputChars,
                            cols = cols,
                            rows = rows,
                        )
                    }.getOrElse { e ->
                        return@execute listOf(
                            UIMessagePart.Text(
                                e.toPtyErrorToolResponse(setupHint = TERMUX_PTY_SETUP_HINT).encode(json)
                            )
                        )
                    }
                    return@execute listOf(UIMessagePart.Text(response.toToolResponse().encode(json)))
                }

                val timeoutMs = (params.optionalLong("timeout_ms") ?: settings.termuxTimeoutMs)
                    .coerceAtLeast(1_000L)
                val result = runCatching {
                    termuxCommandManager.run(
                        TermuxRunCommandRequest(
                            commandPath = TERMUX_BASH_PATH,
                            arguments = listOf("-lc", command),
                            workdir = settings.termuxWorkdir,
                            background = true,
                            timeoutMs = timeoutMs,
                            label = "RikkaHub termux_exec",
                        )
                    )
                }.getOrElse { e ->
                    return@execute listOf(
                        UIMessagePart.Text(
                            e.toCommandErrorToolResponse(setupHint = TERMUX_SETUP_HINT).encode(json)
                        )
                    )
                }

                listOf(UIMessagePart.Text(result.toToolResponse().encode(json)))
            }
        )
    }

    private fun termuxWriteStdinTool(
        needsApproval: Boolean,
        settingsStore: SettingsStore,
        assistant: Assistant,
    ): Tool {
        return Tool(
            name = "write_stdin",
            description = buildToolDescription(
                baseDescription = """
                    Send input to a PTY session started by termux_exec.
                    Use chars="" to poll for more output.
                    Optional overrides: yield_time_ms, max_output_chars.
                """.trimIndent().replace("\n", " "),
                assistant = assistant,
                toolName = "write_stdin",
            ),
            needsApproval = needsApproval,
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("session_id", buildJsonObject {
                            put("type", "string")
                            put("description", "The session_id returned by termux_exec in tty mode")
                        })
                        put("chars", buildJsonObject {
                            put("type", "string")
                            put("description", "Characters to send to the PTY. May be empty to poll for more output")
                        })
                        put("yield_time_ms", buildJsonObject {
                            put("type", "integer")
                            put("description", "Optional PTY yield window override")
                        })
                        put("max_output_chars", buildJsonObject {
                            put("type", "integer")
                            put("description", "Optional PTY output chunk size override")
                        })
                    },
                    required = listOf("session_id"),
                )
            },
            execute = execute@{ input ->
                val params = input.jsonObject
                val sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: error("session_id is required")
                val chars = params["chars"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val settings = settingsStore.settingsFlow.value
                val yieldTimeMs = (params.optionalLong("yield_time_ms") ?: settings.termuxPtyYieldTimeMs)
                    .coerceAtLeast(0L)
                val maxOutputChars = (params.optionalInt("max_output_chars") ?: settings.termuxPtyMaxOutputChars)
                    .coerceAtLeast(256)

                val response = runCatching {
                    termuxPtySessionManager.writeStdin(
                        sessionId = sessionId,
                        chars = chars,
                        yieldTimeMs = yieldTimeMs,
                        maxOutputChars = maxOutputChars,
                    )
                }.getOrElse { e ->
                    return@execute listOf(
                        UIMessagePart.Text(
                            e.toPtyErrorToolResponse(setupHint = TERMUX_PTY_SETUP_HINT).encode(json)
                        )
                    )
                }

                listOf(UIMessagePart.Text(response.toToolResponse().encode(json)))
            }
        )
    }

    private fun termuxListPtySessionsTool(
        assistant: Assistant,
    ): Tool {
        return Tool(
            name = "list_pty_sessions",
            description = buildToolDescription(
                baseDescription = """
                    List active PTY sessions created by termux_exec with tty=true.
                    Returns JSON with session metadata, buffered output size, and running state.
                """.trimIndent().replace("\n", " "),
                assistant = assistant,
                toolName = "list_pty_sessions",
            ),
            needsApproval = false,
            parameters = {
                InputSchema.Obj(properties = buildJsonObject { })
            },
            execute = execute@{
                val response = runCatching {
                    termuxPtySessionManager.listSessions()
                }.getOrElse { e ->
                    return@execute listOf(
                        UIMessagePart.Text(
                            TermuxPtySessionListResponse(
                                success = false,
                                running = false,
                                error = e.message ?: e.javaClass.name,
                            ).encode(json)
                        )
                    )
                }
                listOf(UIMessagePart.Text(response.encode(json)))
            }
        )
    }

    private fun termuxClosePtySessionTool(
        needsApproval: Boolean,
        assistant: Assistant,
    ): Tool {
        return Tool(
            name = "close_pty_session",
            description = buildToolDescription(
                baseDescription = """
                    Close one PTY session created by termux_exec with tty=true.
                    Set close_all=true to close every active PTY session managed by the app.
                """.trimIndent().replace("\n", " "),
                assistant = assistant,
                toolName = "close_pty_session",
            ),
            needsApproval = needsApproval,
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("session_id", buildJsonObject {
                            put("type", "string")
                            put("description", "The PTY session to close. Required unless close_all=true.")
                        })
                        put("close_all", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Close all active PTY sessions managed by the app")
                        })
                    }
                )
            },
            execute = execute@{ input ->
                val params = input.jsonObject
                val closeAll = params["close_all"]?.jsonPrimitive?.booleanOrNull == true
                val response = runCatching {
                    if (closeAll) {
                        termuxPtySessionManager.closeAllSessions()
                    } else {
                        val sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                            ?: error("session_id is required unless close_all=true")
                        termuxPtySessionManager.closeSession(sessionId)
                    }
                }.getOrElse { e ->
                    return@execute listOf(
                        UIMessagePart.Text(
                            TermuxPtyActionResponse(
                                success = false,
                                running = false,
                                error = e.message ?: e.javaClass.name,
                            ).encode(json)
                        )
                    )
                }
                listOf(UIMessagePart.Text(response.encode(json)))
            }
        )
    }

    private fun termuxPythonTool(
        needsApproval: Boolean,
        settingsStore: SettingsStore,
        termuxCommandManager: TermuxCommandManager,
        assistant: Assistant,
    ): Tool {
        return Tool(
            name = "termux_python",
            description = buildToolDescription(
                baseDescription = """
                    Run Python code in local Termux and return JSON with output and execution status.
                    Optional overrides: timeout_ms.
                """.trimIndent().replace("\n", " "),
                assistant = assistant,
                toolName = "termux_python",
            ),
            needsApproval = needsApproval,
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("code", buildJsonObject {
                            put("type", "string")
                            put("description", "Python code to execute")
                        })
                        put("timeout_ms", buildJsonObject {
                            put("type", "integer")
                            put("description", "Optional timeout override")
                        })
                    },
                    required = listOf("code"),
                )
            },
            execute = execute@{ input ->
                val params = input.jsonObject
                val code = params["code"]?.jsonPrimitive?.contentOrNull ?: error("code is required")
                val settings = settingsStore.settingsFlow.value
                val timeoutMs = (params.optionalLong("timeout_ms") ?: settings.termuxTimeoutMs)
                    .coerceAtLeast(1_000L)

                val termuxResult = runCatching {
                    termuxCommandManager.run(
                        TermuxRunCommandRequest(
                            commandPath = TERMUX_PYTHON3_PATH,
                            arguments = listOf("-"),
                            workdir = settings.termuxWorkdir,
                            stdin = code,
                            background = true,
                            timeoutMs = timeoutMs,
                            label = "RikkaHub termux_python",
                        )
                    )
                }.getOrElse { e ->
                    return@execute listOf(
                        UIMessagePart.Text(
                            e.toCommandErrorToolResponse(setupHint = TERMUX_PYTHON_SETUP_HINT).encode(json)
                        )
                    )
                }

                listOf(UIMessagePart.Text(termuxResult.toToolResponse().encode(json)))
            }
        )
    }

    fun getTools(
        options: List<LocalToolOption>,
        assistant: Assistant,
        overrideTermuxNeedsApproval: Boolean? = null,
    ): List<Tool> {
        val settings = settingsStore.settingsFlow.value
        val termuxNeedsApproval = overrideTermuxNeedsApproval ?: settings.termuxNeedsApproval
        val termuxPtyInteractiveEnabled = settings.termuxPtyInteractiveEnabled
        val enabled = options.toSet()
        return buildList {
            LocalToolCatalog.options.forEach { option ->
                if (!enabled.contains(option)) return@forEach
                when (option) {
                    LocalToolOption.JavascriptEngine -> add(javascriptTool.withAssistantPrompt(assistant))
                    LocalToolOption.TimeInfo -> add(timeTool.withAssistantPrompt(assistant))
                    LocalToolOption.Clipboard -> add(clipboardTool.withAssistantPrompt(assistant))
                    LocalToolOption.TermuxExec -> {
                        add(
                            termuxExecTool(
                                needsApproval = termuxNeedsApproval,
                                ptyInteractiveEnabled = termuxPtyInteractiveEnabled,
                                settingsStore = settingsStore,
                                termuxCommandManager = termuxCommandManager,
                                termuxPtySessionManager = termuxPtySessionManager,
                                assistant = assistant,
                            )
                        )
                        if (termuxPtyInteractiveEnabled) {
                            add(
                                termuxWriteStdinTool(
                                    needsApproval = termuxNeedsApproval,
                                    settingsStore = settingsStore,
                                    assistant = assistant,
                                )
                            )
                            add(
                                termuxListPtySessionsTool(
                                    assistant = assistant,
                                )
                            )
                            add(
                                termuxClosePtySessionTool(
                                    needsApproval = termuxNeedsApproval,
                                    assistant = assistant,
                                )
                            )
                        }
                    }

                    LocalToolOption.TermuxPython -> {
                        add(
                            termuxPythonTool(
                                needsApproval = termuxNeedsApproval,
                                settingsStore = settingsStore,
                                termuxCommandManager = termuxCommandManager,
                                assistant = assistant,
                            )
                        )
                    }

                    LocalToolOption.Tts -> add(ttsTool.withAssistantPrompt(assistant))
                    LocalToolOption.AskUser -> add(askUserTool.withAssistantPrompt(assistant))
                }
            }
        }
    }

    private fun Tool.withAssistantPrompt(assistant: Assistant): Tool {
        val description = buildToolDescription(
            baseDescription = description,
            assistant = assistant,
            toolName = name,
        )
        return if (description == this.description) this else copy(description = description)
    }

    private fun buildToolDescription(
        baseDescription: String,
        assistant: Assistant,
        toolName: String,
    ): String {
        val customPrompt = assistant.localToolPrompts[toolName]?.trim()
        return listOfNotNull(
            baseDescription.trim(),
            customPrompt?.takeIf { it.isNotBlank() }
        ).joinToString(separator = "\n")
    }

    private fun JsonObject.optionalInt(name: String): Int? {
        val primitive = this[name]?.jsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull() ?: error("$name must be an integer")
    }

    private fun JsonObject.optionalLong(name: String): Long? {
        val primitive = this[name]?.jsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull() ?: error("$name must be an integer")
    }

    companion object {
        private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
        private const val TERMUX_PYTHON3_PATH = "/data/data/com.termux/files/usr/bin/python3"
        private const val TERMUX_SETUP_HINT =
            "Setup checklist if this still fails: install Termux; set allow-external-apps=true in ~/.termux/termux.properties; " +
                "grant com.termux.permission.RUN_COMMAND to this app in system settings."
        private const val TERMUX_PTY_SETUP_HINT =
            "Setup checklist if this still fails: install Termux; set allow-external-apps=true in ~/.termux/termux.properties; " +
                "grant com.termux.permission.RUN_COMMAND to this app; install python in Termux (pkg install python). " +
                "PTY uses Python's standard library; no pip package is required."
        private const val TERMUX_PYTHON_SETUP_HINT =
            "Setup checklist if this still fails: install Termux and install python in Termux (pkg install python)."
    }
}
