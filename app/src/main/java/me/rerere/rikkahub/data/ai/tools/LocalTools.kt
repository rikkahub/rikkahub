package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.data.model.TodoItem
import me.rerere.rikkahub.data.model.TodoState
import me.rerere.rikkahub.data.model.TodoStatus
import me.rerere.rikkahub.sandbox.SandboxEngine
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.uuid.Uuid

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("sandbox_fs")
    @Deprecated("Renamed to ChaquoPy", ReplaceWith("ChaquoPy"))
    data object SandboxFs : LocalToolOption()

    @Serializable
    @SerialName("chaquopy_tools")
    data object ChaquoPy : LocalToolOption()

    @Serializable
    @SerialName("container_runtime")
    data object Container : LocalToolOption()

    @Serializable
    @SerialName("workflow_todo")
    data object WorkflowTodo : LocalToolOption()

    @Serializable
    @SerialName("subagent")
    data object SubAgent : LocalToolOption()

    @Serializable
    @SerialName("matplotlib")
    @Deprecated("Matplotlib is now part of ChaquoPy tools")
    data object Matplotlib : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("sandbox_file")
    data object SandboxFile : LocalToolOption()
}

class LocalTools(
    private val context: Context,
    private val prootManager: PRootManager,
) {
    val javascriptTool by lazy {
        Tool(
            name = "eval_javascript",
            description = """
                Execute JavaScript code using QuickJS engine (ES2020).
                The result is the value of the last expression in the code.
                Console output (log/info/warn/error) is captured and returned in 'logs'.
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
            execute = { args ->
                val logs = arrayListOf<String>()
                val quickJs = QuickJSContext.create()
                quickJs.setConsole(object : QuickJSContext.Console {
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

                val code = args.jsonObject["code"]?.jsonPrimitive?.contentOrNull ?: error("code is required")
                val result = quickJs.evaluate(code)
                val payload = buildJsonObject {
                    if (logs.isNotEmpty()) {
                        put("logs", JsonPrimitive(logs.joinToString("\n")))
                    }
                    put(
                        "result", when (result) {
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
                Returns date/time strings, timezone, and timestamp.
            """.trimIndent().replace("\n", " "),
            parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
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
            description = "Read or write plain text from the device clipboard.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("read"); add("write") })
                            put("description", "Operation to perform: read or write")
                        })
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "Text to write (required for write)")
                        })
                    },
                    required = listOf("action")
                )
            },
            execute = { args ->
                val params = args.jsonObject
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

    fun createSandboxFileTool(sandboxId: Uuid): Tool {
        return Tool(
            name = "sandbox_file",
            description = """
                Sandbox filesystem operations with isolated workspace.
                Use operation + params, e.g. list/read/write/delete/mkdir/copy/move/zip_create.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("operation", buildJsonObject {
                            put("type", "string")
                            put("description", "Sandbox operation name")
                        })
                        put("params", buildJsonObject {
                            put("type", "object")
                            put("description", "Operation params object")
                        })
                    },
                    required = listOf("operation", "params")
                )
            },
            execute = { args ->
                val operation = args.jsonObject["operation"]?.jsonPrimitive?.contentOrNull
                    ?: error("operation is required")
                val params = args.jsonObject["params"]?.jsonObject ?: JsonObject(emptyMap())
                val result = SandboxEngine.execute(
                    context = context,
                    assistantId = sandboxId.toString(),
                    operation = operation,
                    params = jsonObjectToMap(params)
                )
                listOf(UIMessagePart.Text(result.toString()))
            }
        )
    }

    fun createSandboxPythonTool(sandboxId: Uuid): Tool {
        return Tool(
            name = "sandbox_python",
            description = """
                Run Python in app sandbox (Chaquopy). Includes matplotlib plotting.
                Use operation + params. For plotting, operation=matplotlib_plot and params.code.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("operation", buildJsonObject {
                            put("type", "string")
                            put("description", "python_exec, matplotlib_plot, or other sandbox python operation")
                        })
                        put("params", buildJsonObject {
                            put("type", "object")
                            put("description", "Operation params object")
                        })
                    },
                    required = listOf("operation", "params")
                )
            },
            execute = { args ->
                val operation = args.jsonObject["operation"]?.jsonPrimitive?.contentOrNull
                    ?: error("operation is required")
                val params = args.jsonObject["params"]?.jsonObject ?: JsonObject(emptyMap())

                val result = if (operation == "matplotlib_plot") {
                    val code = params["code"]?.jsonPrimitive?.contentOrNull ?: error("params.code is required")
                    SandboxEngine.executeMatplotlibPlot(
                        context = context,
                        assistantId = sandboxId.toString(),
                        code = code
                    )
                } else {
                    SandboxEngine.execute(
                        context = context,
                        assistantId = sandboxId.toString(),
                        operation = operation,
                        params = jsonObjectToMap(params)
                    )
                }

                listOf(UIMessagePart.Text(result.toString()))
            }
        )
    }

    fun createContainerRuntimeTool(): Tool {
        return Tool(
            name = "container_runtime",
            description = """
                Manage container runtime lifecycle.
                action can be: status, initialize, start, stop, destroy.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("status")
                                add("initialize")
                                add("start")
                                add("stop")
                                add("destroy")
                            })
                        })
                    },
                    required = listOf("action")
                )
            },
            execute = { args ->
                val action = args.jsonObject["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
                val payload = when (action) {
                    "status" -> buildJsonObject {
                        put("success", true)
                        put("state", prootManager.containerState.value.toString())
                        put("running", prootManager.isRunning)
                        put("initialized", prootManager.checkInitializationStatus())
                    }

                    "initialize" -> {
                        val result = prootManager.initialize()
                        buildJsonObject {
                            put("success", result.isSuccess)
                            put("action", "initialize")
                            result.exceptionOrNull()?.message?.let { put("error", it) }
                        }
                    }

                    "start" -> {
                        val result = prootManager.start()
                        buildJsonObject {
                            put("success", result.isSuccess)
                            put("action", "start")
                            result.exceptionOrNull()?.message?.let { put("error", it) }
                        }
                    }

                    "stop" -> {
                        val result = prootManager.stop()
                        buildJsonObject {
                            put("success", result.isSuccess)
                            put("action", "stop")
                            result.exceptionOrNull()?.message?.let { put("error", it) }
                        }
                    }

                    "destroy" -> {
                        val result = prootManager.destroy()
                        buildJsonObject {
                            put("success", result.isSuccess)
                            put("action", "destroy")
                            result.exceptionOrNull()?.message?.let { put("error", it) }
                        }
                    }

                    else -> error("unknown action: $action")
                }

                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    fun createContainerShellTool(sandboxId: Uuid): Tool {
        return Tool(
            name = "container_shell",
            description = """
                Execute shell command in container runtime (Alpine in PRoot).
                Container must be initialized and started first.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "Shell command")
                        })
                    },
                    required = listOf("command")
                )
            },
            execute = { args ->
                val command = args.jsonObject["command"]?.jsonPrimitive?.contentOrNull ?: error("command is required")
                val result = prootManager.executeShell(
                    sandboxId = sandboxId.toString(),
                    command = command
                )
                listOf(UIMessagePart.Text(result.toString()))
            }
        )
    }

    fun createContainerPythonTool(sandboxId: Uuid): Tool {
        return Tool(
            name = "container_python",
            description = """
                Execute Python code in container runtime.
                Optional packages can be provided and will be installed by pip before execution.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("code", buildJsonObject {
                            put("type", "string")
                            put("description", "Python code")
                        })
                        put("packages", buildJsonObject {
                            put("type", "array")
                            put("description", "Optional pip packages")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                    },
                    required = listOf("code")
                )
            },
            execute = { args ->
                val code = args.jsonObject["code"]?.jsonPrimitive?.contentOrNull ?: error("code is required")
                val packages = args.jsonObject["packages"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList()
                val result = prootManager.executePython(
                    sandboxId = sandboxId.toString(),
                    code = code,
                    packages = packages
                )
                listOf(UIMessagePart.Text(result.toString()))
            }
        )
    }

    fun getTools(
        options: List<LocalToolOption>,
        sandboxId: Uuid? = null,
        todoStateProvider: (() -> TodoState?)? = null,
        onTodoStateUpdate: ((TodoState) -> Unit)? = null,
    ): List<Tool> {
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

        if (sandboxId != null && (options.contains(LocalToolOption.SandboxFile) || options.contains(LocalToolOption.SandboxFs))) {
            tools.add(createSandboxFileTool(sandboxId))
        }

        if (sandboxId != null && (options.contains(LocalToolOption.ChaquoPy) || options.contains(LocalToolOption.Matplotlib))) {
            tools.add(createSandboxPythonTool(sandboxId))
        }

        if (sandboxId != null && options.contains(LocalToolOption.Container)) {
            tools.add(createContainerRuntimeTool())
            tools.add(createContainerShellTool(sandboxId))
            tools.add(createContainerPythonTool(sandboxId))
        }

        if (options.contains(LocalToolOption.WorkflowTodo) &&
            todoStateProvider != null && onTodoStateUpdate != null
        ) {
            tools.add(createTodoUpdateTool(todoStateProvider, onTodoStateUpdate))
            tools.add(createTodoReadTool(todoStateProvider))
        }

        return tools
    }

    @Deprecated("Use getTools() with explicit options", ReplaceWith("getTools(options, sandboxId)"))
    fun createSandboxFsTool(sandboxId: Uuid): Tool = createSandboxFileTool(sandboxId)
}

private fun createTodoUpdateTool(
    todoStateProvider: () -> TodoState?,
    onTodoStateUpdate: (TodoState) -> Unit
): Tool {
    return Tool(
        name = "todo_update",
        description = "Update todo list items. Each item has id/title/status(TODO|DOING|DONE)/note.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("todos", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("id", buildJsonObject { put("type", "string") })
                                put("title", buildJsonObject { put("type", "string") })
                                put("status", buildJsonObject {
                                    put("type", "string")
                                    put("enum", buildJsonArray { add("TODO"); add("DOING"); add("DONE") })
                                })
                                put("note", buildJsonObject { put("type", "string") })
                            })
                        })
                    })
                },
                required = listOf("todos")
            )
        },
        needsApproval = false,
        execute = { args ->
            val current = todoStateProvider() ?: TodoState(isEnabled = true)
            val todosJson = args.jsonObject["todos"]?.jsonArray ?: error("todos is required")

            val todos = todosJson.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val status = when (obj["status"]?.jsonPrimitive?.contentOrNull) {
                    "DOING" -> TodoStatus.DOING
                    "DONE" -> TodoStatus.DONE
                    else -> TodoStatus.TODO
                }
                TodoItem(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: Uuid.random().toString(),
                    title = title,
                    status = status,
                    note = obj["note"]?.jsonPrimitive?.contentOrNull
                )
            }

            val updated = current.copy(todos = todos, isEnabled = true)
            onTodoStateUpdate(updated)

            val payload = buildJsonObject {
                put("success", true)
                put("count", todos.size)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
}

private fun createTodoReadTool(
    todoStateProvider: () -> TodoState?
): Tool {
    return Tool(
        name = "todo_read",
        description = "Read current todo list.",
        parameters = { null },
        needsApproval = false,
        execute = {
            val current = todoStateProvider() ?: TodoState(isEnabled = true)
            val payload = buildJsonObject {
                put("enabled", current.isEnabled)
                put("count", current.todos.size)
                put("todos", buildJsonArray {
                    current.todos.forEach { todo ->
                        add(buildJsonObject {
                            put("id", todo.id)
                            put("title", todo.title)
                            put("status", todo.status.name)
                            todo.note?.let { note -> put("note", note) }
                        })
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
}

private fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    jsonObject.forEach { (key, value) ->
        map[key] = jsonElementToValue(value)
    }
    return map
}

private fun jsonElementToValue(element: JsonElement): Any {
    return when (element) {
        is JsonPrimitive -> {
            when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.boolean
                element.intOrNull != null -> element.intOrNull!!
                element.longOrNull != null -> element.longOrNull!!
                element.doubleOrNull != null -> element.doubleOrNull!!
                else -> element.content
            }
        }

        is JsonObject -> jsonObjectToMap(element)
        is JsonArray -> element.map { jsonElementToValue(it) }
        else -> element.toString()
    }
}


