package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SubAgentProgressManager
import me.rerere.rikkahub.data.ai.subagent.SubAgentResult
import me.rerere.rikkahub.sandbox.SandboxEngine
import me.rerere.rikkahub.data.model.TodoStatus
import me.rerere.rikkahub.data.model.TodoItem
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText
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
    @Deprecated("Matplotlib is now part of SandboxFs, kept for backward compatibility")
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
    private val prootManager: me.rerere.rikkahub.data.container.PRootManager,
    private val backgroundProcessManager: me.rerere.rikkahub.data.container.BackgroundProcessManager,
    val subAgentExecutor: me.rerere.rikkahub.data.ai.subagent.SubAgentExecutor? = null,
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
                )
            },
            execute = {
                val logs = arrayListOf<String>()
                val ctx = QuickJSContext.create()
                ctx.setConsole(object : QuickJSContext.Console {
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
                val result = ctx.evaluate(code)
                val resultText = buildString {
                    if (logs.isNotEmpty()) {
                        appendLine("Logs:")
                        appendLine(logs.joinToString("\n"))
                        appendLine()
                    }
                    append("Result: ")
                    append(
                        when (result) {
                            is QuickJSObject -> result.stringify()
                            else -> result.toString()
                        }
                    )
                }
                listOf(UIMessagePart.Text(resultText))
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
                val now = java.time.ZonedDateTime.now()
                val date = now.toLocalDate()
                val time = now.toLocalTime().withNano(0)
                val weekday = now.dayOfWeek
                val resultText = buildString {
                    appendLine("Current Time Information:")
                    appendLine("Year: ${date.year}")
                    appendLine("Month: ${date.monthValue}")
                    appendLine("Day: ${date.dayOfMonth}")
                    appendLine("Weekday: ${weekday.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())}")
                    appendLine("Weekday (EN): ${weekday.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)}")
                    appendLine("Date: $date")
                    appendLine("Time: $time")
                    appendLine("DateTime: ${now.withNano(0)}")
                    appendLine("Timezone: ${now.zone.id}")
                    appendLine("UTC Offset: ${now.offset.id}")
                    appendLine("Timestamp (ms): ${now.toInstant().toEpochMilli()}")
                }
                listOf(UIMessagePart.Text(resultText))
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
                val resultText = when (action) {
                    "read" -> {
                        "Clipboard content: ${readClipboardText(context)}"
                    }

                    "write" -> {
                        val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
                        writeClipboardText(context, text)
                        "Text written to clipboard: $text"
                    }

                    else -> error("unknown action: $action, must be one of [read, write]")
                }
                listOf(UIMessagePart.Text(resultText))
            }
        )
    }

    // ========== 沙箱工具集合（5个独立工具，包含 Matplotlib 绘图）==========
    
    /**
     * 工具 1: 沙箱文件操作
     * 基础文件管理：读写、复制移动、压缩解压等
     */
    fun createSandboxFileTool(sandboxId: Uuid): Tool = createSandboxTool(
        name = "sandbox_file",
        description = "沙箱文件操作。文件用 file_path，目录用 path。最大 50MB。",
        operations = listOf(
            "write" to "写入文件：{file_path, content}",
            "read" to "读取文件：{file_path}",
            "delete" to "删除文件/目录：{file_path, recursive?}",
            "list" to "列出目录：{path, show_hidden?}",
            "mkdir" to "创建目录：{dir_path}",
            "copy" to "复制：{src, dst}",
            "move" to "移动/重命名：{src, dst}",
            "stat" to "文件信息：{file_path}",
            "exists" to "检查存在：{file_path}",
            "zip_create" to "创建 ZIP：{zip_name, source_paths: []}"
        ),
        sandboxId = sandboxId
    )
    
    /**
     * 工具 2: Python 代码执行与数据可视化
     * Python 执行、代码分析、编译检查、Matplotlib 绘图
     */
    fun createSandboxPythonTool(sandboxId: Uuid): Tool = createSandboxTool(
        name = "sandbox_python",
        description = "执行 Python 3.11 代码。预装：numpy、pandas、matplotlib、requests。解压Windows ZIP文件：将反斜杠(\\)转换为正斜杠(/)，解压到指定目录，避免根目录污染。",
        operations = listOf(
            "python_exec" to "执行代码：{code}。定义 'result' 变量返回数据",
            "matplotlib_plot" to "生成图表：{code}。无需指定保存路径，Matplotlib 自动处理；中文注释时不要指定字体，使用系统默认字体。返回 image_url（格式 file:///path/to/img.png）可直接显示",
            "analyze_code" to "代码分析：{file_path, language, operation}",
            "compile_check" to "语法检查：{file_path, language}"
        ),
        sandboxId = sandboxId
    )
    
    /**
     * 工具 3: Shell 执行
     * Shell 命令和脚本执行（Android Toybox 限制）
     */
    fun createSandboxShellTool(sandboxId: Uuid): Tool = createSandboxTool(
        name = "sandbox_shell",
        description = "Android Toybox shell。如需完整 Linux（apk、git、wget），请用 container_shell。",
        operations = listOf(
            "exec" to "执行命令：{command}",
            "exec_script" to "执行脚本：{script} 或 {script_path}, {env?}, {timeout?}"
        ),
        sandboxId = sandboxId
    )
    
    /**
     * 工具 3b: Shell 执行（只读模式）
     * 只允许只读命令，用于Explore和Plan代理
     */
    fun createSandboxShellReadonlyTool(sandboxId: Uuid): Tool {
        return Tool(
            name = "sandbox_shell_readonly",
            description = """
                READONLY shell command execution for exploration and analysis.
                Allowed commands: ls, cat, grep, find, head, tail, wc, sort, uniq, awk, sed, tr, cut, echo, pwd, whoami, date, stat, file, readlink, which, git (status/log/diff/show/branch only)
                BLOCKED commands: cp, mv, rm, mkdir, rmdir, touch, chmod, chown, dd, tee, redirect operators (>, >>), git (add/commit/push/checkout)
                Use for: Searching files, reading content, analyzing structure
                WARNING: Any attempt to modify files will be rejected with error.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("operation", buildJsonObject {
                            put("type", "string")
                            put("description", "Operation: exec (single command) or exec_script (script)")
                            put("enum", buildJsonArray { add("exec"); add("exec_script") })
                        })
                        put("params", buildJsonObject {
                            put("type", "object")
                            put("description", "Operation parameters. For exec: {command}. For exec_script: {script} or {script_path}")
                        })
                    },
                    required = listOf("operation", "params")
                )
            },
            execute = { args ->
                val operation = args.jsonObject["operation"]?.jsonPrimitive?.contentOrNull
                val paramsObj = args.jsonObject["params"]?.jsonObject

                // 验证参数
                if (operation == null) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive("Missing required parameter: operation"))
                    }.toString()))
                }
                if (paramsObj == null) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive("Missing required parameter: params"))
                    }.toString()))
                }
                if (operation != "exec" && operation != "exec_script") {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive("Operation '$operation' not available. Only 'exec' and 'exec_script' are allowed in readonly mode"))
                    }.toString()))
                }

                // 获取命令内容
                val command = when (operation) {
                    "exec" -> paramsObj["command"]?.jsonPrimitive?.contentOrNull
                    "exec_script" -> paramsObj["script"]?.jsonPrimitive?.contentOrNull 
                        ?: paramsObj["script_path"]?.jsonPrimitive?.contentOrNull?.let { path ->
                            // 如果是script_path，先读取文件内容
                            "cat $path"
                        }
                    else -> null
                }

                if (command.isNullOrBlank()) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive("Missing required parameter: command/script/script_path"))
                    }.toString()))
                }

                // ========== 白名单安全检查 ==========
                val validationResult = validateReadonlyShellCommand(command)
                if (!validationResult.isValid) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive("SECURITY VIOLATION: ${validationResult.errorMessage}.\nBlocked command: ${validationResult.blockedCommand}\nYour command: $command"))
                        put("stdout", JsonPrimitive(""))
                        put("stderr", JsonPrimitive(""))
                        put("exitCode", JsonPrimitive(-1))
                    }.toString()))
                }

                // 安全检查通过，执行命令
                val params = jsonObjectToMap(paramsObj)
                val result = SandboxEngine.execute(context, sandboxId.toString(), operation, params)
                listOf(UIMessagePart.Text(result.toString()))
            }
        )
    }
    
    /**
     * 工具 4: 数据处理
     * Excel、PDF、图片、SQLite、下载
     */
    fun createSandboxDataTool(sandboxId: Uuid): Tool = createSandboxTool(
        name = "sandbox_data",
        description = "数据处理：Excel、PDF、图片、SQLite、下载",
        operations = listOf(
            "process_image" to "图片处理：{input_path, operation, output_path?}",
            "convert_excel" to "Excel 转换：{input_path, format: csv/json, output_path?}",
            "extract_pdf_text" to "PDF 提取文本：{input_path, output_path?, pages?}",
            "sqlite_query" to "SQL 查询：{db_path, query, params?, max_rows?}",
            "sqlite_tables" to "数据库结构：{db_path, detail?}",
            "download_file" to "HTTP 下载：{url, output_path?, timeout?, headers?}"
        ),
        sandboxId = sandboxId
    )
    
    /**
     * 工具 5: 开发工具
     * Git完整工作流、ktlint、pip 包管理
     */
    fun createSandboxDevTool(sandboxId: Uuid): Tool = createSandboxTool(
        name = "sandbox_dev",
        description = "开发工具：Git 工作流、ktlint",
        operations = listOf(
            "git_init" to "初始化仓库：{path}",
            "git_add" to "添加文件：{path, file_path}",
            "git_commit" to "提交更改：{path, message, author_name?, author_email?}",
            "git_status" to "查看状态：{path}",
            "git_branch" to "分支操作：{path, action, branch_name?}",
            "git_checkout" to "切换分支：{path, branch_name? | file_path?, create?}",
            "git_log" to "提交历史：{path, max_count?}",
            "git_diff" to "查看差异：{path, file_path?, staged?}",
            "git_rm" to "删除文件：{path, file_path, cached?}",
            "git_mv" to "重命名：{path, src, dst}",
            "install_tool" to "安装工具：{tool: ktlint, version?, force?}"
        ),
        sandboxId = sandboxId
    )
    
    /**
     * 容器运行时 Shell 执行工具（PRoot）
     * 仅当容器运行时启用且就绪时暴露
     */
    fun createContainerShellTool(sandboxId: Uuid): Tool {
        return Tool(
            name = "container_shell",
            description = """完整 Linux Shell（Alpine），支持 apk、git、wget、Python3。超时 5 分钟。
【重要】安装开发工具：使用 apk add 安装（如 apk add python3、apk add nodejs、apk add g++ 等）。所有开发工具都应通过 apk 包管理器安装，不要尝试解压 ZIP 包。
【禁止】禁止使用此工具启动服务（如 uvicorn、npm start、redis-server 等），启动服务会导致客户端卡死 5 分钟。如需启动服务，请使用 container_shell_bg 工具。
【故障排查】如 apk 安装失败，先配置 DNS：echo 'nameserver 8.8.8.8' > /etc/resolv.conf""".trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "Shell 命令，如 'ls -la'、'curl https://example.com'")
                        })
                    },
                    required = listOf("command")
                )
            },
            execute = { args ->
                val command = args.jsonObject["command"]?.jsonPrimitive?.contentOrNull
                    ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive("Missing required parameter: command"))
                        put("exitCode", -1)
                        put("stdout", "")
                        put("stderr", "")
                    }.toString()))

                // 安全检查：阻止删除系统关键文件
                val securityCheck = checkContainerCommandSecurity(command)
                if (!securityCheck.isAllowed) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive("Security violation: ${securityCheck.errorMessage}"))
                        put("exitCode", -1)
                        put("stdout", "")
                        put("stderr", "Operation blocked: cannot modify system files. You can only delete user-installed dev tools (e.g., /usr/local/*, /home/*, /root/*).")
                    }.toString()))
                }

                // 调用 PRootManager 执行（全局单例，无需创建容器）
                val result = prootManager.executeShell(
                    sandboxId = sandboxId.toString(),
                    command = command
                )
                listOf(UIMessagePart.Text(result.toString()))
            }
        )
    }

    /**
     * 容器后台执行工具（非阻塞）
     * 适用于启动长期运行的服务（如 uvicorn、nginx、数据库等）
     */
    fun createContainerShellBgTool(sandboxId: Uuid): Tool {
        return Tool(
            name = "container_shell_bg",
            description = """在容器后台执行Shell命令并立即返回，不等待命令完成。
适用于启动长期运行的服务（如 uvicorn、nginx、redis-server 等）。
启动后返回进程ID，可用于后续查询状态、查看日志或终止进程。

【使用场景】
✅ 启动需要持续运行的服务（Web服务器、数据库、缓存等）
✅ 启动开发服务器（npm run dev、python manage.py runserver）
✅ 命令不会自动退出，需要持续监听端口或处理请求

❌ 不要用于：
- 一次性执行的命令（编译、测试、文件操作）
- 短时间内会完成的脚本
- 需要立即获取输出结果的命令

【进程管理】
启动后可使用 container_process 工具管理进程：
- 查看进程状态：action=list
- 查看进程日志：action=logs, processId=<进程ID>
- 终止进程：action=kill, processId=<进程ID>
- 清理已结束进程：action=clean""".trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "要后台执行的Shell命令")
                        })
                        put("tag", buildJsonObject {
                            put("type", "string")
                            put("description", "可选的进程标签，便于识别（如 'web-server', 'database'）")
                        })
                    },
                    required = listOf("command")
                )
            },
            execute = { args ->
                val command = args.jsonObject["command"]?.jsonPrimitive?.contentOrNull
                    ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive("Missing required parameter: command"))
                    }.toString()))

                val tag = args.jsonObject["tag"]?.jsonPrimitive?.contentOrNull

                // 调用 BackgroundProcessManager
                val result = backgroundProcessManager.startBackgroundProcess(
                    sandboxId = sandboxId.toString(),
                    command = command,
                    tag = tag
                )

                val response = buildJsonObject {
                    put("success", JsonPrimitive(result.success))
                    put("processId", JsonPrimitive(result.processId))
                    put("status", JsonPrimitive(result.status.name))
                    put("message", JsonPrimitive(result.message))
                    if (result.stdoutFile != null) {
                        put("stdoutFile", JsonPrimitive(result.stdoutFile))
                    }
                    if (result.stderrFile != null) {
                        put("stderrFile", JsonPrimitive(result.stderrFile))
                    }
                    if (result.pid != null) {
                        put("pid", JsonPrimitive(result.pid))
                    }

                    if (result.success) {
                        put("hint", JsonPrimitive("""
                            |进程已启动在后台。
                            |使用 container_process 工具管理：
                            |- 查看状态：action=list
                            |- 查看日志：action=logs, processId=${result.processId}
                            |- 终止进程：action=kill, processId=${result.processId}
                        """.trimMargin()))
                    }
                }

                listOf(UIMessagePart.Text(response.toString()))
            }
        )
    }

    /**
     * 容器进程管理工具
     */
    fun createContainerProcessTool(sandboxId: Uuid): Tool {
        return Tool(
            name = "container_process",
            description = """管理容器中启动的后台进程。

支持的操作：
- list: 列出所有后台进程
- status: 查看指定进程状态
- logs: 查看进程输出日志（stdout/stderr）
- kill: 终止指定进程
- clean: 清理已结束的进程记录

【操作说明】
1. list - 列出所有后台进程
   参数：无
   返回：所有进程的列表（processId, command, status, tag, createdAt, pid等）

2. status - 查看指定进程状态
   参数：processId（必需）
   返回：进程的详细信息（包括运行时长、退出码等）

3. logs - 查看进程日志
   参数：processId（必需）, stream（可选，stdout或stderr，默认stdout）, offset（可选，默认0）, limit（可选，默认1000）
   返回：日志内容

4. kill - 终止进程
   参数：processId（必需）
   返回：终止结果

5. clean - 清理已结束的进程
   参数：无
   返回：清理的进程数量""".trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("list")
                                add("status")
                                add("logs")
                                add("kill")
                                add("clean")
                            })
                            put("description", "操作类型")
                        })
                        put("processId", buildJsonObject {
                            put("type", "string")
                            put("description", "进程ID（status/logs/kill操作必需）")
                        })
                        put("stream", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("stdout")
                                add("stderr")
                            })
                            put("description", "日志流类型（logs操作，默认stdout）")
                        })
                        put("offset", buildJsonObject {
                            put("type", "integer")
                            put("description", "日志偏移量（logs操作，默认0）")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "最大日志行数（logs操作，默认1000）")
                        })
                    },
                    required = listOf("action")
                )
            },
            execute = { args ->
                val action = args.jsonObject["action"]?.jsonPrimitive?.contentOrNull
                    ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive("Missing required parameter: action"))
                    }.toString()))

                val response = when (action) {
                    "list" -> handleListProcesses(sandboxId.toString())
                    "status" -> handleGetProcessStatus(args.jsonObject, sandboxId.toString())
                    "logs" -> runBlocking { handleReadLogs(args.jsonObject) }
                    "kill" -> runBlocking { handleKillProcess(args.jsonObject) }
                    "clean" -> runBlocking { handleCleanup(sandboxId.toString()) }
                    else -> buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive("Unknown action: $action"))
                    }
                }

                listOf(UIMessagePart.Text(response.toString()))
            }
        )
    }

    /**
     * 处理 list 操作
     */
    private fun handleListProcesses(sandboxId: String): JsonObject {
        val processes = backgroundProcessManager.getProcessesBySandbox(sandboxId)

        val processesJson = buildJsonArray {
            processes.forEach { info ->
                add(buildJsonObject {
                    put("processId", info.processId)
                    put("command", info.command)
                    put("status", info.status.name)
                    put("tag", JsonPrimitive(info.tag ?: ""))
                    put("createdAt", info.createdAt)
                    put("startedAt", JsonPrimitive(info.startedAt ?: 0))
                    put("pid", JsonPrimitive(info.pid ?: -1))
                    info.exitCode?.let { put("exitCode", it) }
                })
            }
        }

        return buildJsonObject {
            put("success", JsonPrimitive(true))
            put("count", JsonPrimitive(processes.size))
            put("processes", processesJson)
        }
    }

    /**
     * 处理 status 操作
     */
    private fun handleGetProcessStatus(args: JsonObject, sandboxId: String): JsonObject {
        val processId = args["processId"]?.jsonPrimitive?.contentOrNull
            ?: return buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", JsonPrimitive("Missing required parameter: processId"))
            }

        val info = backgroundProcessManager.getProcess(processId)
            ?: return buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", JsonPrimitive("Process not found: $processId"))
            }

        // 计算运行时长
        val duration = when {
            info.exitedAt != null && info.startedAt != null -> info.exitedAt - info.startedAt
            info.startedAt != null -> System.currentTimeMillis() - info.startedAt
            else -> 0
        }

        return buildJsonObject {
            put("success", JsonPrimitive(true))
            put("processId", info.processId)
            put("command", info.command)
            put("status", info.status.name)
            put("tag", JsonPrimitive(info.tag ?: ""))
            put("createdAt", info.createdAt)
            put("startedAt", JsonPrimitive(info.startedAt ?: 0))
            put("exitedAt", JsonPrimitive(info.exitedAt ?: 0))
            put("pid", JsonPrimitive(info.pid ?: -1))
            put("exitCode", JsonPrimitive(info.exitCode ?: -1))
            put("durationMs", duration)
            put("stdoutFile", info.stdoutPath)
            put("stderrFile", info.stderrPath)
        }
    }

    /**
     * 处理 logs 操作
     */
    private suspend fun handleReadLogs(args: JsonObject): JsonObject {
        val processId = args["processId"]?.jsonPrimitive?.contentOrNull
            ?: return buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", JsonPrimitive("Missing required parameter: processId"))
            }

        val stream = args["stream"]?.jsonPrimitive?.contentOrNull ?: "stdout"
        val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 1000

        val result = backgroundProcessManager.readProcessLogs(
            processId = processId,
            stream = stream,
            offset = offset,
            limit = limit
        )

        return if (result.error != null) {
            buildJsonObject {
                put("success", JsonPrimitive(false as Boolean))
                put("error", JsonPrimitive(result.error))
            }
        } else {
            buildJsonObject {
                put("success", JsonPrimitive(true))
                put("processId", processId)
                put("stream", stream)
                put("offset", offset)
                put("limit", limit)
                put("totalLines", result.totalLines)
                put("hasMore", result.hasMore)
                put("lines", buildJsonArray {
                    result.lines.forEach { add(it) }
                })
            }
        }
    }

    /**
     * 处理 kill 操作
     */
    private suspend fun handleKillProcess(args: JsonObject): JsonObject {
        val processId = args["processId"]?.jsonPrimitive?.contentOrNull
            ?: return buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", JsonPrimitive("Missing required parameter: processId"))
            }

        val result = backgroundProcessManager.killProcess(processId)

        return buildJsonObject {
            put("success", JsonPrimitive(result.success))
            put("processId", processId)
            put("status", result.status.name)
            put("message", result.message)
        }
    }

    /**
     * 处理 clean 操作
     */
    private suspend fun handleCleanup(sandboxId: String): JsonObject {
        // 清理24小时前已结束的进程
        val cleanedCount = backgroundProcessManager.cleanupOldProcesses(24 * 60 * 60 * 1000L)

        return buildJsonObject {
            put("success", JsonPrimitive(true))
            put("message", JsonPrimitive("Cleaned up $cleanedCount old process records"))
        }
    }

    /**
     * 通用沙箱工具创建函数
     */
    private fun createSandboxTool(
        name: String,
        description: String,
        operations: List<Pair<String, String>>,
        sandboxId: Uuid
    ): Tool {
        val operationList = operations.map { it.first }
        val operationDescriptions = operations.joinToString("; ") { "${it.first}: ${it.second}" }
        
        return Tool(
            name = name,
            description = "$description Operations: $operationDescriptions",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("operation", buildJsonObject {
                            put("type", "string")
                            put("description", "Operation: ${operationList.joinToString(", ")}")
                            put("enum", buildJsonArray {
                                operationList.forEach { add(it) }
                            })
                        })
                        put("params", buildJsonObject {
                            put("type", "object")
                            put("description", "Operation parameters (see operation description)")
                        })
                    },
                    required = listOf("operation", "params")
                )
            },
            execute = { args ->
                val operation = args.jsonObject["operation"]?.jsonPrimitive?.contentOrNull
                val paramsObj = args.jsonObject["params"]?.jsonObject
                
                val result = when {
                    operation == null -> buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive("Missing required parameter: operation"))
                    }
                    paramsObj == null -> buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive("Missing required parameter: params"))
                    }
                    !operationList.contains(operation) -> buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive("Operation '$operation' not available in $name. Available: ${operationList.joinToString(", ")}"))
                    }
                    operation == "matplotlib_plot" -> {
                        // Matplotlib 绘图使用专门的执行方法
                        val code = paramsObj["code"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (code.isBlank()) {
                            buildJsonObject {
                                put("success", JsonPrimitive(false))
                                put("error", JsonPrimitive("Missing required parameter: code"))
                            }
                        } else {
                            SandboxEngine.executeMatplotlibPlot(context, sandboxId.toString(), code)
                        }
                    }
                    else -> {
                        val params = jsonObjectToMap(paramsObj)
                        SandboxEngine.execute(context, sandboxId.toString(), operation, params)
                    }
                }
                listOf(UIMessagePart.Text(result.toString()))
            }
        )
    }

      /**
       * 获取工具列表（新版 - 5个独立沙箱工具）
       *
       * 容器运行时工具暴露逻辑变更（全局单例）：
       * - 全局单例容器（非 per-conversation）
       * - 仅当容器状态为 Running 时暴露工具
       * - 所有使用容器工具的对话共享同一容器实例
       * - 沙箱目录依然 per-conversation 隔离
       * - 容器工具已合并到 ChaquoPy 工具中（开了 ChaquoPy 且容器运行时才暴露）
       *
       * @param options 启用的工具选项
       * @param sandboxId 沙箱 ID（使用沙箱功能时必需，通常使用 conversationId）
       * @param subAgents 启用的子代理列表
       * @param settings 当前设置（用于子代理执行）
       * @param parentModel 主对话使用的模型（子代理将继承此模型，除非配置了专用模型）
       * @param parentWorkflowPhase 父代理的Workflow阶段（用于子代理权限控制）
       * @param mcpTools 可用的MCP工具（用于子代理）
       */
      fun getTools(
          options: List<LocalToolOption>,
          sandboxId: Uuid? = null,
          workflowStateProvider: (() -> me.rerere.rikkahub.data.model.WorkflowState?)? = null,
          onWorkflowStateUpdate: ((me.rerere.rikkahub.data.model.WorkflowState) -> Unit)? = null,
          todoStateProvider: (() -> me.rerere.rikkahub.data.model.TodoState?)? = null,
          onTodoStateUpdate: ((me.rerere.rikkahub.data.model.TodoState) -> Unit)? = null,
          subAgents: List<me.rerere.rikkahub.data.model.SubAgent> = emptyList(),
          settings: me.rerere.rikkahub.data.datastore.Settings? = null,
          parentModel: me.rerere.ai.provider.Model? = null,
          parentWorkflowPhase: me.rerere.rikkahub.data.model.WorkflowPhase? = null,
          mcpTools: List<Tool> = emptyList(),
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

        // ✅ 文件管理工具 - 需要开关
        if (sandboxId != null && options.contains(LocalToolOption.SandboxFile)) {
            tools.add(createSandboxFileTool(sandboxId))
        }

        // ✅ ChaquoPy 工具（独立）
        if (sandboxId != null && options.contains(LocalToolOption.ChaquoPy)) {
            tools.add(createSandboxPythonTool(sandboxId))
            tools.add(createSandboxShellTool(sandboxId))
            tools.add(createSandboxDataTool(sandboxId))
            tools.add(createSandboxDevTool(sandboxId))
        }

        // ✅ 容器工具（独立开关）
        if (sandboxId != null && options.contains(LocalToolOption.Container) && prootManager.isRunning) {
            tools.add(createContainerShellTool(sandboxId))
            tools.add(createContainerShellBgTool(sandboxId))
            tools.add(createContainerProcessTool(sandboxId))
        }

        // ✅ Workflow TODO 工具 - 独立开关控制（不再依赖 WorkflowState）
        if (todoStateProvider != null && onTodoStateUpdate != null &&
            options.contains(LocalToolOption.WorkflowTodo)) {
            tools.add(createTodoUpdateTool(todoStateProvider, onTodoStateUpdate))
            tools.add(createTodoReadTool(todoStateProvider))
        }

        // ✅ 子代理工具 - 独立开关控制
        if (sandboxId != null && subAgents.isNotEmpty() && subAgentExecutor != null &&
            settings != null && parentModel != null &&
            options.contains(LocalToolOption.SubAgent)) {
            tools.add(createSpawnSubagentTool(
                subAgents = subAgents,
                sandboxId = sandboxId,
                settings = settings,
                parentModel = parentModel,
                parentWorkflowPhase = parentWorkflowPhase,
                containerEnabled = prootManager.isRunning,
                mcpTools = mcpTools
            ))
        }

        return tools
    }
    
    /**
     * 获取旧版单一沙箱工具（向后兼容）
     * 注意：新版本推荐使用 getTools() 获取 5 个独立工具
     */
    @Deprecated("Use getTools() with 5 separate tools instead", ReplaceWith("getTools(options, sandboxId)"))
    fun createSandboxFsTool(sandboxId: Uuid): Tool {
        // 创建一个聚合所有操作的工具作为向后兼容
        return createSandboxFileTool(sandboxId)
    }
    
    /**
     * 获取工具描述列表（用于 AI 提示）
     */
    fun getToolDescriptions(options: List<LocalToolOption>): String {
        return options.mapNotNull { option ->
            when (option) {
                LocalToolOption.JavascriptEngine -> "JavaScript Engine"
                LocalToolOption.ChaquoPy -> "ChaquoPy Tools (4): sandbox_python, sandbox_shell, sandbox_data, sandbox_dev"
                LocalToolOption.Container -> {
                    if (prootManager.isRunning) {
                        "Container Tools (3): container_shell, container_shell_bg, container_process"
                    } else {
                        null // 容器未运行时不显示
                    }
                }
                LocalToolOption.TimeInfo -> "Time Info"
                LocalToolOption.Clipboard -> "Clipboard Tool"
                LocalToolOption.WorkflowTodo -> "Workflow TODO"
                LocalToolOption.SubAgent -> "SubAgent"
                LocalToolOption.SandboxFile -> "Sandbox File"
                else -> null // 忽略已废弃的选项
            }
        }.joinToString(", ")
    }
}

/**
 * Shell只读命令白名单验证结果
 */
private data class ShellValidationResult(
    val isValid: Boolean,
    val blockedCommand: String? = null,
    val errorMessage: String? = null
)

/**
 * 允许的只读Shell命令列表
 * 分为基础命令和带限制的命令（如git只允许特定子命令）
 */
private val READONLY_BASIC_COMMANDS = setOf(
    // 文件查看
    "ls", "cat", "head", "tail", "less", "more", "file", "stat", "readlink",
    // 文本处理
    "grep", "egrep", "fgrep", "awk", "sed", "tr", "cut", "sort", "uniq", "wc",
    "strings", "xxd", "hexdump", "od",
    // 查找
    "find", "locate", "which", "whereis",
    // 系统信息
    "pwd", "whoami", "id", "date", "uname", "hostname", "uptime",
    "env", "printenv", "getprop",
    // 其他只读
    "echo", "printf", "test", "[", "[[",
    "seq", "yes", "true", "false", "sleep",
    // 网络只读
    "ping", "curl", "wget", "nslookup", "dig", "host",
    // 压缩查看
    "tar", "gzip", "gunzip", "zcat", "bzcat", "xzcat",
    "zipinfo", "unzip", "jar", "ar",
    // 其他
    "ps", "top", "df", "du", "free", "mount", "lsmod", "lsusb", "lspci"
)

/**
 * 带限制子命令的命令映射
 * key: 主命令, value: 允许的子命令集合（空集合表示只允许主命令本身，不允许任何子命令）
 */
private val READONLY_RESTRICTED_COMMANDS = mapOf(
    "git" to setOf("status", "log", "diff", "show", "branch", "remote", "config", "ls-files", 
                   "ls-tree", "rev-parse", "describe", "tag", "stash", "blame", "grep",
                   "cat-file", "for-each-ref", "verify-commit", "verify-tag"),
    "docker" to setOf("ps", "images", "inspect", "logs", "top", "stats", "version", "info"),
    "kubectl" to setOf("get", "describe", "logs", "top", "version", "cluster-info"),
    "npm" to setOf("list", "view", "search", "config", "version"),
    "pip" to setOf("list", "show", "search", "freeze", "config"),
    "gradle" to setOf("tasks", "dependencies", "projects", "properties", "wrapper"),
    "mvn" to setOf("help", "dependency:tree", "dependency:list"),
    "adb" to setOf("devices", "version", "help", "shell"),
    "sqlite3" to setOf() // sqlite3只允许主命令，不允许子命令（避免执行破坏性SQL）
)

/**
 * 危险操作符和关键字（禁止）
 */
private val DANGEROUS_OPERATORS = setOf(
    ">", ">>", "<", "<<", "<<<",   // 重定向
    "&", "&&", "||", ";", "|",     // 逻辑和管道（管道需要特殊处理）
    "`", "$",                        // 命令替换
    "&>", ">&", "2>", "2>>"          // 错误重定向
)

/**
 * 需要特殊检查管道命令的允许命令
 * 这些命令可以出现在管道中，但仍需检查整个管道是否包含危险命令
 */
private val PIPE_SAFE_COMMANDS = READONLY_BASIC_COMMANDS

/**
 * 验证Shell命令是否只读安全
 * 
 * 验证逻辑：
 * 1. 检查是否包含危险操作符（重定向、命令替换等）
 * 2. 解析命令，提取所有执行的命令名
 * 3. 对每个命令进行白名单检查
 * 4. 特殊处理受限命令（如git只允许特定子命令）
 * 5. 允许管道，但检查管道中的所有命令
 */
private fun validateReadonlyShellCommand(command: String): ShellValidationResult {
    // 第1步：检查危险字符和操作符
    val dangerousCharCheck = checkDangerousCharacters(command)
    if (!dangerousCharCheck.isValid) {
        return dangerousCharCheck
    }

    // 第2步：解析命令，提取所有命令名
    val commands = parseShellCommands(command)
    
    // 第3步：逐个检查命令
    for (cmdInfo in commands) {
        val validation = validateSingleCommand(cmdInfo)
        if (!validation.isValid) {
            return validation
        }
    }

    return ShellValidationResult(isValid = true)
}

/**
 * 检查危险字符和操作符
 */
private fun checkDangerousCharacters(command: String): ShellValidationResult {
    // 检查重定向操作符（>, >>, <等）
    val redirectPattern = Regex("""[<>]+""")
    if (redirectPattern.containsMatchIn(command)) {
        return ShellValidationResult(
            isValid = false,
            blockedCommand = command.take(50),
            errorMessage = "Redirect operators (>, >>, <) are not allowed in readonly mode"
        )
    }

    // 检查命令替换（$() 或 ``）
    val commandSubPattern = Regex("""\$\(|`[^`]*`""")
    if (commandSubPattern.containsMatchIn(command)) {
        return ShellValidationResult(
            isValid = false,
            blockedCommand = command.take(50),
            errorMessage = "Command substitution (\$() or ``) is not allowed in readonly mode"
        )
    }

    // 检查后台执行(&)
    if (command.contains("&") && !command.contains("&&")) {
        return ShellValidationResult(
            isValid = false,
            blockedCommand = command.take(50),
            errorMessage = "Background execution (&) is not allowed"
        )
    }

    // 检查分号（多命令）- 允许，但会逐个检查
    // 检查逻辑或 || - 允许，但会逐个检查

    return ShellValidationResult(isValid = true)
}

/**
 * 解析Shell命令，提取所有命令信息
 * 返回命令列表，每个包含：命令名、子命令（如果有）、原始片段
 */
private fun parseShellCommands(command: String): List<CommandInfo> {
    val commands = mutableListOf<CommandInfo>()
    
    // 先按管道、分号、逻辑操作符分割
    // 顺序：先分号和逻辑，再管道
    val commandGroups = splitByDelimiters(command, listOf(";", "&&", "||"))
    
    for (group in commandGroups) {
        val trimmedGroup = group.trim()
        if (trimmedGroup.isEmpty()) continue
        
        // 在组内按管道分割
        val pipelineCommands = splitByPipe(trimmedGroup)
        
        for (pipeCmd in pipelineCommands) {
            val trimmedCmd = pipeCmd.trim()
            if (trimmedCmd.isEmpty()) continue
            
            val cmdInfo = parseSingleCommand(trimmedCmd)
            commands.add(cmdInfo)
        }
    }
    
    return commands
}

/**
 * 命令信息数据类
 */
private data class CommandInfo(
    val fullCommand: String,      // 完整命令字符串
    val baseCommand: String,      // 基础命令名（如git）
    val subCommand: String?,      // 子命令（如status）
    val arguments: List<String>   // 参数列表
)

/**
 * 按分隔符分割命令（保留分隔符后的部分）
 */
private fun splitByDelimiters(command: String, delimiters: List<String>): List<String> {
    val result = mutableListOf<String>()
    var current = StringBuilder()
    var i = 0
    
    while (i < command.length) {
        var matched = false
        for (delim in delimiters.sortedByDescending { it.length }) {
            if (command.substring(i).startsWith(delim)) {
                if (current.isNotEmpty()) {
                    result.add(current.toString().trim())
                    current = StringBuilder()
                }
                i += delim.length
                matched = true
                break
            }
        }
        if (!matched) {
            current.append(command[i])
            i++
        }
    }
    
    if (current.isNotEmpty()) {
        result.add(current.toString().trim())
    }
    
    return result
}

/**
 * 按管道符分割（考虑引号内的管道符）
 */
private fun splitByPipe(command: String): List<String> {
    val result = mutableListOf<String>()
    var current = StringBuilder()
    var inSingleQuote = false
    var inDoubleQuote = false
    
    for (char in command) {
        when (char) {
            '\'' -> if (!inDoubleQuote) inSingleQuote = !inSingleQuote
            '"' -> if (!inSingleQuote) inDoubleQuote = !inDoubleQuote
            '|' -> {
                if (!inSingleQuote && !inDoubleQuote) {
                    result.add(current.toString())
                    current = StringBuilder()
                    continue
                }
            }
        }
        current.append(char)
    }
    
    result.add(current.toString())
    return result
}

/**
 * 解析单个命令，提取命令名、子命令、参数
 */
private fun parseSingleCommand(command: String): CommandInfo {
    // 移除前导的空白和常见前缀
    var trimmed = command.trim()
    
    // 处理赋值（如VAR=value command）
    val assignPattern = Regex("""^[A-Za-z_][A-Za-z0-9_]*=\S*\s+""")
    while (assignPattern.containsMatchIn(trimmed)) {
        trimmed = trimmed.substring(assignPattern.find(trimmed)!!.range.last).trim()
    }
    
    // 分割成tokens（简单分割，不考虑复杂引号）
    val tokens = splitCommandTokens(trimmed)
    if (tokens.isEmpty()) {
        return CommandInfo(command, "", null, emptyList())
    }
    
    val baseCommand = tokens[0]
    var subCommand: String? = null
    val arguments = mutableListOf<String>()
    
    // 查找子命令（第一个非选项参数）
    for (i in 1 until tokens.size) {
        val token = tokens[i]
        if (!token.startsWith("-")) {
            subCommand = token
            // 剩余的是参数
            arguments.addAll(tokens.subList(i + 1, tokens.size))
            break
        } else {
            arguments.add(token)
        }
    }
    
    return CommandInfo(command, baseCommand, subCommand, arguments)
}

/**
 * 简单分割命令tokens（处理空格和引号）
 */
private fun splitCommandTokens(command: String): List<String> {
    val tokens = mutableListOf<String>()
    var current = StringBuilder()
    var inSingleQuote = false
    var inDoubleQuote = false
    
    for (char in command) {
        when {
            char == '\'' && !inDoubleQuote -> {
                inSingleQuote = !inSingleQuote
            }
            char == '"' && !inSingleQuote -> {
                inDoubleQuote = !inDoubleQuote
            }
            char.isWhitespace() && !inSingleQuote && !inDoubleQuote -> {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current = StringBuilder()
                }
            }
            else -> current.append(char)
        }
    }
    
    if (current.isNotEmpty()) {
        tokens.add(current.toString())
    }
    
    return tokens
}

/**
 * 验证单个命令是否在白名单中
 */
private fun validateSingleCommand(cmdInfo: CommandInfo): ShellValidationResult {
    val baseCmd = cmdInfo.baseCommand
    
    // 空命令检查
    if (baseCmd.isEmpty()) {
        return ShellValidationResult(isValid = true)
    }
    
    // 检查是否在基础白名单中
    if (READONLY_BASIC_COMMANDS.contains(baseCmd)) {
        return ShellValidationResult(isValid = true)
    }
    
    // 检查是否在受限命令映射中
    val allowedSubCommands = READONLY_RESTRICTED_COMMANDS[baseCmd]
    if (allowedSubCommands != null) {
        // 是受控命令，检查子命令
        val subCmd = cmdInfo.subCommand
        
        if (subCmd == null) {
            // 没有子命令，检查是否允许单独使用主命令
            // 对于git等，通常不允许单独使用，需要子命令
            return if (baseCmd == "git") {
                ShellValidationResult(
                    isValid = false,
                    blockedCommand = baseCmd,
                    errorMessage = "'git' requires a subcommand. Allowed: ${allowedSubCommands.joinToString(", ")}"
                )
            } else {
                ShellValidationResult(isValid = true)
            }
        }
        
        // 检查子命令是否在允许列表中
        return if (allowedSubCommands.contains(subCmd)) {
            ShellValidationResult(isValid = true)
        } else {
            ShellValidationResult(
                isValid = false,
                blockedCommand = "$baseCmd $subCmd",
                errorMessage = "'$baseCmd $subCmd' is not allowed. Allowed $baseCmd subcommands: ${allowedSubCommands.joinToString(", ")}"
            )
        }
    }
    
    // 命令不在任何白名单中
    return ShellValidationResult(
        isValid = false,
        blockedCommand = baseCmd,
        errorMessage = "Command '$baseCmd' is not in the readonly whitelist. Allowed commands include: ls, cat, grep, find, git (status/log/diff only), etc."
    )
}

/**
 * 将 JsonObject 转换为 Map<String, Any>
 */
private fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    jsonObject.forEach { (key, value) ->
        map[key] = jsonElementToValue(value)
    }
    return map
}

/**
 * 将 JsonElement 转换为对应的 Kotlin 类型
 */
private fun jsonElementToValue(element: JsonElement): Any {
    return when (element) {
        is JsonPrimitive -> {
            when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.boolean
                element.intOrNull != null -> element.int
                element.longOrNull != null -> element.long
                element.doubleOrNull != null -> element.double
                else -> element.content
            }
        }
        is JsonObject -> jsonObjectToMap(element)
        is JsonArray -> element.map { jsonElementToValue(it) }
        else -> element.toString()
    }
}

/**
 * 创建 TODO 更新工具（独立于 Workflow）
 *
 * @param todoStateProvider 获取当前 TodoState 的函数
 * @param onTodoStateUpdate 更新 TodoState 的回调
 */
private fun createTodoUpdateTool(
    todoStateProvider: () -> me.rerere.rikkahub.data.model.TodoState?,
    onTodoStateUpdate: (me.rerere.rikkahub.data.model.TodoState) -> Unit
): Tool {
    return Tool(
        name = "todo_update",
        description = "Update the TODO list. Accepts JSON array of todo items with id, title, status (TODO/DOING/DONE), and optional note.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("todos", buildJsonObject {
                        put("type", "array")
                        put("description", "Array of todo items")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("id", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Unique identifier for the todo item")
                                })
                                put("title", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Title of the todo item")
                                })
                                put("status", buildJsonObject {
                                    put("type", "string")
                                    put("enum", buildJsonArray { add("TODO"); add("DOING"); add("DONE") })
                                    put("description", "Status of the todo item")
                                })
                                put("note", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Optional note for the todo item")
                                })
                            })
                        })
                    })
                }
            )
        },
        needsApproval = false,
        execute = { arguments ->
            try {
                val currentState = todoStateProvider()
                if (currentState == null) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "Todo is not enabled for this conversation")
                    }.toString()))
                }

                val todosJson = arguments.jsonObject["todos"]
                if (todosJson == null) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "Missing 'todos' parameter")
                    }.toString()))
                }

                val todos = mutableListOf<me.rerere.rikkahub.data.model.TodoItem>()
                if (todosJson is kotlinx.serialization.json.JsonArray) {
                    for (item in todosJson) {
                        if (item is kotlinx.serialization.json.JsonObject) {
                            val id = when (val idElem = item["id"]) {
                                is kotlinx.serialization.json.JsonPrimitive -> idElem.content
                                else -> kotlin.uuid.Uuid.random().toString()
                            }
                            val title = when (val titleElem = item["title"]) {
                                is kotlinx.serialization.json.JsonPrimitive -> titleElem.content
                                else -> ""
                            }
                            val statusStr = when (val statusElem = item["status"]) {
                                is kotlinx.serialization.json.JsonPrimitive -> statusElem.content
                                else -> "TODO"
                            }
                            val note = when (val noteElem = item["note"]) {
                                is kotlinx.serialization.json.JsonPrimitive -> noteElem.content
                                else -> null
                            }

                            val status = when (statusStr) {
                                "DOING" -> me.rerere.rikkahub.data.model.TodoStatus.DOING
                                "DONE" -> me.rerere.rikkahub.data.model.TodoStatus.DONE
                                else -> me.rerere.rikkahub.data.model.TodoStatus.TODO
                            }

                            todos.add(me.rerere.rikkahub.data.model.TodoItem(
                                id = id,
                                title = title,
                                status = status,
                                note = note
                            ))
                        }
                    }
                }

                val newState = currentState.copy(todos = todos, isEnabled = true)
                onTodoStateUpdate(newState)

                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("message", "Updated ${todos.size} todo items")
                }.toString()))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "Failed to update todos: ${e.message}")
                }.toString()))
            }
        }
    )
}

/**
 * 创建 TODO 读取工具（独立于 Workflow）
 *
 * @param todoStateProvider 获取当前 TodoState 的函数
 */
private fun createTodoReadTool(
    todoStateProvider: () -> me.rerere.rikkahub.data.model.TodoState?
): Tool {
    return Tool(
        name = "todo_read",
        description = "Read the current TODO list.",
        parameters = { null },
        needsApproval = false,
        execute = {
            try {
                val currentState = todoStateProvider()
                if (currentState == null) {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "Todo is not enabled for this conversation")
                    }.toString()))
                }

                val todosJson = buildJsonArray {
                    for (todo in currentState.todos) {
                        add(buildJsonObject {
                            put("id", todo.id)
                            put("title", todo.title)
                            put("status", todo.status.name)
                            if (todo.note != null) {
                                put("note", todo.note)
                            }
                        })
                    }
                }

                listOf(UIMessagePart.Text(buildJsonObject {
                    put("todos", todosJson)
                    put("count", currentState.todos.size)
                }.toString()))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "Failed to read todos: ${e.message}")
                }.toString()))
            }
        }
    )
}

/**
 * 创建子代理生成工具
 * 主代理通过调用此工具来生成子代理处理特定任务
 */
private fun LocalTools.createSpawnSubagentTool(
    subAgents: List<me.rerere.rikkahub.data.model.SubAgent>,
    sandboxId: Uuid,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    parentModel: me.rerere.ai.provider.Model,
    parentWorkflowPhase: me.rerere.rikkahub.data.model.WorkflowPhase? = null,
    containerEnabled: Boolean,
    mcpTools: List<Tool>
): Tool = Tool(
    name = "spawn_subagent",
    description = """
        生成子代理并行处理任务。场景：Explore搜文件、Plan做架构、Task执行代码。
        可用：${subAgents.joinToString(", ") { it.name }}。Explore/Plan只读，Task可读写。
        返回：result(结果), success(成功?), agent(名), error(错), duration_ms(耗时)
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("agent_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Name of the sub-agent to spawn")
                    put("enum", buildJsonArray {
                        subAgents.forEach { add(it.name) }
                    })
                })
                put("task", buildJsonObject {
                    put("type", "string")
                    put("description", "Clear description of the task for the sub-agent. Be specific about what you want it to do.")
                })
                put("context", buildJsonObject {
                    put("type", "string")
                    put("description", "Relevant context, code snippets, or background information (optional)")
                })
                put("files", buildJsonObject {
                    put("type", "array")
                    put("description", "File paths in sandbox to pass to sub-agent (optional). Use relative paths from sandbox root.")
                    put("items", buildJsonObject {
                        put("type", "string")
                    })
                })
            },
            required = listOf("agent_name", "task")
        )
    },
    execute = { args ->
        val agentName = args.jsonObject["agent_name"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", JsonPrimitive("Missing required parameter: agent_name"))
            }.toString()))

        val task = args.jsonObject["task"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", JsonPrimitive("Missing required parameter: task"))
            }.toString()))

        val context = args.jsonObject["context"]?.jsonPrimitive?.contentOrNull ?: ""
        val files = args.jsonObject["files"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive.contentOrNull
        } ?: emptyList()

        // 查找子代理配置
        val subAgent = subAgents.find { it.name.equals(agentName, ignoreCase = true) }
            ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", JsonPrimitive("Sub-agent '$agentName' not found. Available: ${
                    subAgents.joinToString(", ") { it.name }
                }"))
            }.toString()))

        // 生成toolCallId（用于追踪进度）
        val toolCallId = "subagent_${agentName}_${System.currentTimeMillis()}"

        // 启动子代理任务并收集进度（后台执行）
        val localToolsInstance = this
        val progressFlow = subAgentExecutor!!.execute(
            subAgent = subAgent,
            task = task,
            context = context,
            files = files,
            sandboxId = sandboxId,
            settings = settings,
            parentModel = parentModel,
            parentWorkflowPhase = parentWorkflowPhase,
            containerEnabled = containerEnabled,
            localTools = localToolsInstance,
            mcpTools = mcpTools
        )

        // 启动进度收集（后台运行）
        SubAgentProgressManager.startSubAgent(toolCallId, args.jsonObject, progressFlow)

        // 等待执行完成并获取最终结果
        val result = SubAgentProgressManager.getFinalResult(toolCallId, timeoutMs = 900000)
            ?: SubAgentResult(
                success = false,
                result = "",
                error = "Sub-agent execution timed out or failed",
                duration = 900000
            )

        // 标记任务为已完成（不立即清理，保留状态供UI读取）
        SubAgentProgressManager.markCompleted(toolCallId)

        // 获取最终的进度状态用于metadata
        val finalProgressState = SubAgentProgressManager.getProgressState(toolCallId)

        // 构建精简的结果（保留完整result，减少冗余数据）
        val resultJson = buildJsonObject {
            put("success", JsonPrimitive(result.success))
            put("agent", JsonPrimitive(agentName))
            put("result", JsonPrimitive(result.result))  // ✅ 完整结果，保持不变
            result.error?.let { put("error", JsonPrimitive(it)) }
            put("duration_ms", JsonPrimitive(result.duration))
            put("toolCallId", JsonPrimitive(toolCallId))

            // 精简的执行历史（只保留工具调用次数）
            finalProgressState?.let { state ->
                put("executionHistory", buildJsonObject {
                    put("toolCalls", JsonPrimitive(state.totalToolCalls))
                    // 删除 toolCalls（不必要的工具调用过程）
                    // 删除 textPreview（结果已在result字段中）
                })
            }

            // ✅ 保留 messageHistory，但只返回最后1条非系统消息
            if (result.messages.isNotEmpty()) {
                val recentMessages = result.messages
                    .filter { it.role != MessageRole.SYSTEM }  // 过滤系统提示
                    .takeLast(1)  // 只取最后1条

                put("messageHistory", buildJsonArray {
                    recentMessages.forEach { msg ->
                        add(buildJsonObject {
                            put("role", JsonPrimitive(msg.role.name))
                            put("content", JsonPrimitive(msg.toText()))
                        })
                    }
                })
            }
        }
        listOf(UIMessagePart.Text(resultJson.toString()))
    }
)

/**
 * 容器命令安全检查结果
 */
private data class ContainerSecurityResult(
    val isAllowed: Boolean,
    val errorMessage: String? = null
)

/**
 * 系统关键路径列表 - 禁止删除或修改
 */
private val PROTECTED_SYSTEM_PATHS = setOf(
    "/bin", "/sbin", "/usr/bin", "/usr/sbin",
    "/lib", "/lib64", "/usr/lib", "/usr/lib64",
    "/etc", "/dev", "/proc", "/sys", "/run", "/tmp"
    // 移除 /usr/local/bin 和 /usr/local/sbin 的保护
    // 允许用户自由管理开发工具（Python, Node.js, Go 等）
)

/**
 * 危险命令模式 - 需要检查是否操作受保护路径
 */
private val DANGEROUS_COMMAND_PATTERNS = listOf(
    // rm 命令
    "rm", "rm -rf", "rm -r", "rm -f", "rm -fr",
    // mv 命令
    "mv", "mv -f",
    // 其他破坏性命令
    "dd", "mkfs", "fdisk", "parted",
    "chmod", "chown"
)

/**
 * 检查容器命令安全性
 * 阻止删除/修改系统关键文件，只允许操作用户安装的开发工具
 */
private fun checkContainerCommandSecurity(command: String): ContainerSecurityResult {
    val trimmedCommand = command.trim().lowercase()

    // 提取命令中的路径参数
    val pathPattern = """(/[a-zA-Z0-9._/-]+)""".toRegex()
    val foundPaths = pathPattern.findAll(command).map { it.value }.toList()

    // 检查是否包含危险命令
    val isDangerousCommand = DANGEROUS_COMMAND_PATTERNS.any { pattern ->
        trimmedCommand.startsWith(pattern) || trimmedCommand.contains(" $pattern ")
    }

    if (!isDangerousCommand) {
        return ContainerSecurityResult(isAllowed = true)
    }

    // 检查是否操作受保护路径
    for (path in foundPaths) {
        val normalizedPath = path.removeSuffix("/")

        // 检查是否直接匹配受保护路径或是其子目录
        for (protectedPath in PROTECTED_SYSTEM_PATHS) {
            if (normalizedPath == protectedPath ||
                normalizedPath.startsWith("$protectedPath/")) {
                return ContainerSecurityResult(
                    isAllowed = false,
                    errorMessage = "Cannot modify system path: $path. " +
                        "System path $protectedPath is protected for system stability. " +
                        "You can delete user-installed tools in /usr/local (python, node, go, rust, etc.)"
                )
            }
        }
    }

    return ContainerSecurityResult(isAllowed = true)
}
