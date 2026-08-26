package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.webkit.MimeTypeMap
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.ai.tools.local.ContentUriResolver
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalAgentSession
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalScreen
import me.rerere.workspace.BackgroundStatus
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
import java.util.Locale

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
private const val MAX_PHONE_EXPORT_BYTES = 64L * 1024 * 1024

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
    "workspace_terminal_start" to true,
    "workspace_terminal_send" to true,
    "workspace_terminal_read" to false,
    "workspace_terminal_kill" to true,
    "workspace_terminal_list" to false,
    "workspace_run_background" to true,
    "workspace_background_status" to false,
    "workspace_background_kill" to false,
    "workspace_export_file_to_phone" to true,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")
    val terminalSessionManager = getKoin().get<WorkspaceTerminalSessionManager>()
    val workspaceRoot = workspaceRepository.getById(workspaceId)?.root
        ?: error("Workspace not found: $workspaceId")

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
        createTerminalStartTool(workspaceRoot, ::needsApproval, terminalSessionManager),
        createTerminalSendTool(workspaceRoot, ::needsApproval, terminalSessionManager),
        createTerminalReadTool(workspaceRoot, ::needsApproval, terminalSessionManager),
        createTerminalKillTool(workspaceRoot, ::needsApproval, terminalSessionManager),
        createTerminalListTool(workspaceRoot, ::needsApproval, terminalSessionManager),
        createRunBackgroundTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
        createBackgroundStatusTool(workspaceId, ::needsApproval, workspaceRepository),
        createBackgroundKillTool(workspaceId, ::needsApproval, workspaceRepository),
        createPhoneExportTool(workspaceId, ::needsApproval, workspaceRepository),
    )
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)

private fun createTerminalStartTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    terminalSessionManager: WorkspaceTerminalSessionManager,
) = Tool(
    name = "workspace_terminal_start",
    description = "Open or reuse a persistent terminal tab from the bound workspace. This is the same PTY shown by the built-in Workspace Terminal, not a second shell. Set create_new_tab=true when an isolated tab is needed.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional existing workspace terminal session id to select")
                })
                put("create_new_tab", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Create a new tab instead of reusing the selected tab. Defaults to false.")
                })
            },
        )
    },
    needsApproval = { needsApproval("workspace_terminal_start") },
    execute = {
        val params = it.jsonObject
        val session = terminalSessionManager.ensureAgentSession(
            root = workspaceId,
            tabId = params["session_id"]?.jsonPrimitive?.longOrNull,
            createNewTab = params["create_new_tab"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
        listOf(UIMessagePart.Text(session.toJson().toString()))
    },
)

private fun createTerminalSendTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    terminalSessionManager: WorkspaceTerminalSessionManager,
) = Tool(
    name = "workspace_terminal_send",
    description = "Send text and/or control keys to a persistent built-in workspace terminal session, then return its screen. Use wait_for to wait for a prompt or other text. Control keys use names such as C-c, C-d, Enter, Up, Down, Tab, and Esc.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Session id from workspace_terminal_start")
                })
                put("input", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to type. Optional when keys is supplied.")
                })
                put("enter", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Press Enter after input and keys. Defaults to true.")
                })
                put("keys", buildJsonObject {
                    put("type", "array")
                    put("description", "Control key names to send")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("wait_for", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional text to wait for on the terminal screen")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Wait timeout in seconds, max 120. Defaults to 10.")
                })
            },
            required = listOf("session_id"),
        )
    },
    needsApproval = { needsApproval("workspace_terminal_send") },
    execute = {
        val params = it.jsonObject
        val sessionId = params["session_id"]?.jsonPrimitive?.longOrNull
            ?: error("session_id is required")
        val waitFor = params.string("wait_for")
        val timeoutMillis = params["timeout_seconds"]?.jsonPrimitive?.intOrNull
            ?.coerceIn(0, 120)?.times(1_000L)
            ?: 10_000L
        val keys = params["keys"]?.jsonArray?.mapNotNull { key ->
            key.jsonPrimitive.contentOrNull
        }.orEmpty()
        val screen = terminalSessionManager.sendAgentInput(
            root = workspaceId,
            tabId = sessionId,
            input = params.string("input"),
            keys = keys,
            pressEnter = params["enter"]?.jsonPrimitive?.booleanOrNull ?: true,
            waitFor = waitFor,
            timeoutMillis = timeoutMillis,
        )
        listOf(UIMessagePart.Text(screen.toJson().toString()))
    },
)

private fun createTerminalReadTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    terminalSessionManager: WorkspaceTerminalSessionManager,
) = Tool(
    name = "workspace_terminal_read",
    description = "Read the current screen of a persistent built-in workspace terminal session without sending input. Use wait_for and timeout_seconds when waiting for a long-running command or prompt.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Session id from workspace_terminal_start")
                })
                put("wait_for", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional text to wait for on the terminal screen")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Wait timeout in seconds, max 120. Defaults to 10.")
                })
            },
            required = listOf("session_id"),
        )
    },
    needsApproval = { needsApproval("workspace_terminal_read") },
    execute = {
        val params = it.jsonObject
        val sessionId = params["session_id"]?.jsonPrimitive?.longOrNull
            ?: error("session_id is required")
        val timeoutMillis = params["timeout_seconds"]?.jsonPrimitive?.intOrNull
            ?.coerceIn(0, 120)?.times(1_000L)
            ?: 10_000L
        val screen = terminalSessionManager.readAgentScreen(
            root = workspaceId,
            tabId = sessionId,
            waitFor = params.string("wait_for"),
            timeoutMillis = timeoutMillis,
        )
        listOf(UIMessagePart.Text(screen.toJson().toString()))
    },
)

private fun createTerminalKillTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    terminalSessionManager: WorkspaceTerminalSessionManager,
) = Tool(
    name = "workspace_terminal_kill",
    description = "Close a persistent built-in workspace terminal tab and end its shell session.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Session id to close")
                })
            },
            required = listOf("session_id"),
        )
    },
    needsApproval = { needsApproval("workspace_terminal_kill") },
    execute = {
        val sessionId = it.jsonObject["session_id"]?.jsonPrimitive?.longOrNull
            ?: error("session_id is required")
        val killed = terminalSessionManager.killAgentSession(workspaceId, sessionId)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("session_id", sessionId)
                    put("killed", killed)
                }.toString()
            )
        )
    },
)

private fun createTerminalListTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    terminalSessionManager: WorkspaceTerminalSessionManager,
) = Tool(
    name = "workspace_terminal_list",
    description = "List persistent terminal tabs currently owned by the bound workspace.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    needsApproval = { needsApproval("workspace_terminal_list") },
    execute = {
        val sessions = terminalSessionManager.listAgentSessions(workspaceId)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("sessions", buildJsonArray {
                        sessions.forEach { add(it.toJson()) }
                    })
                }.toString()
            )
        )
    },
)

private fun WorkspaceTerminalAgentSession.toJson() = buildJsonObject {
    put("session_id", id)
    put("tab_number", number)
    put("cwd", cwd)
    put("running", running)
    put("finished", finished)
}

private fun WorkspaceTerminalScreen.toJson() = buildJsonObject {
    put("session", session.toJson())
    put("screen", screen)
    put("columns", columns)
    put("rows", rows)
    put("cursor_row", cursorRow)
    put("cursor_column", cursorColumn)
}

private fun createRunBackgroundTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_run_background",
    description = buildString {
        append("Run a shell command persistently in the background in the bound workspace Rootfs. ")
        append("Use it for dev servers, long-running installs, or file watchers. The command runs in the foreground of its own process; do not append '&'. ")
        append("Returns a task id. Poll it with workspace_background_status and stop it with workspace_background_kill. ")
        append("Use cwd relative to /workspace.")
        if (!defaultCwd.isNullOrBlank()) append(" Defaults to '$defaultCwd'.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run persistently")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory relative to /workspace")
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_run_background") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val status = workspaceRepository.startBackground(workspaceId, command, cwd)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("id", status.id)
            put("status", "running")
            put("command", status.command)
            put("cwd", status.cwd)
        }.toString()))
    },
)

private fun createBackgroundStatusTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_background_status",
    description = "Check the status and captured output of background processes started with workspace_run_background. Omit id to list all processes for this workspace.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by workspace_run_background. Omit to list all.")
                })
            },
        )
    },
    needsApproval = { needsApproval("workspace_background_status") },
    execute = {
        val taskId = it.jsonObject.string("id")
        val statuses = if (taskId == null) {
            workspaceRepository.listBackground(workspaceId)
        } else {
            listOfNotNull(workspaceRepository.backgroundStatus(workspaceId, taskId))
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("processes", buildJsonArray {
                statuses.forEach { add(it.toJson()) }
            })
        }.toString()))
    },
)

private fun createBackgroundKillTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_background_kill",
    description = "Stop a background process started with workspace_run_background.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by workspace_run_background")
                })
            },
            required = listOf("id"),
        )
    },
    needsApproval = { needsApproval("workspace_background_kill") },
    execute = {
        val taskId = it.jsonObject.string("id") ?: error("id is required")
        val killed = workspaceRepository.killBackground(workspaceId, taskId)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("id", taskId)
            put("killed", killed)
        }.toString()))
    },
)

private fun createPhoneExportTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_export_file_to_phone",
    description = "Copy a file from the bound workspace Rootfs to a directory the user has explicitly granted through Android's system picker. Use /workspace for workspace files. The target directory must be a persisted content:// URI.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute file path inside Rootfs, such as /workspace/image.png")
                })
                put("destination", buildJsonObject {
                    put("type", "string")
                    put("description", "Granted content:// directory URI returned by list_files or grant_directory_access")
                })
                put("filename", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional output filename; defaults to the source filename")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Replace an existing file with the same name")
                })
            },
            required = listOf("path", "destination"),
        )
    },
    needsApproval = { needsApproval("workspace_export_file_to_phone") },
    execute = { input ->
        val params = input.jsonObject
        val sourcePath = params.string("path")?.trim()?.takeIf { it.startsWith("/") }
            ?: error("path must be an absolute Rootfs path")
        val destinationUri = params.string("destination")?.trim()
            ?: error("destination is required")
        val context = getKoin().get<Context>()
        val destination = ContentUriResolver.resolve(context, destinationUri)
            ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "directory_not_granted")
                put("detail", "Call grant_directory_access first and pass its content_uri.")
            }.toString()))
        if (!destination.isDirectory) error("destination must be a directory")

        val size = workspaceRepository.rootfsFileSize(workspaceId, sourcePath)
        require(size <= MAX_PHONE_EXPORT_BYTES) {
            "File is too large to export (${size / 1024 / 1024}MB, max ${MAX_PHONE_EXPORT_BYTES / 1024 / 1024}MB)."
        }
        val filename = params.string("filename")?.trim()?.takeIf { it.isNotEmpty() }
            ?: sourcePath.substringAfterLast('/').ifBlank { error("source filename is empty") }
        require(filename != "." && filename != ".." && !filename.contains('/')) {
            "filename must be a single file name"
        }
        val overwrite = params["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
        val existing = destination.listFiles().firstOrNull { it.name == filename }
        if (existing != null && !overwrite) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "file_exists")
                put("detail", "A file with that name already exists. Set overwrite=true to replace it.")
                put("path", existing.uri.toString())
            }.toString()))
        }
        if (existing?.isDirectory == true) error("destination filename points to a directory")
        if (existing != null && !existing.delete()) error("Unable to replace existing destination file")

        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(filename.substringAfterLast('.', "").lowercase(Locale.ROOT))
            ?: "application/octet-stream"
        val target = destination.createFile(mime, filename)
            ?: error("The phone storage provider refused file creation")
        try {
            val output = context.contentResolver.openOutputStream(target.uri)
                ?: error("The phone storage provider refused file writing")
            workspaceRepository.exportRootfsFile(workspaceId, sourcePath, output)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("source", sourcePath)
            put("destination", target.uri.toString())
            put("filename", filename)
            put("bytes_written", size)
        }.toString()))
    },
)

private fun BackgroundStatus.toJson() = buildJsonObject {
    put("id", id)
    put("command", command)
    put("cwd", cwd)
    put("status", if (running) "running" else "exited")
    if (!running) put("exitCode", exitCode)
    put("startedAt", startedAtMillis)
    put("stdout", stdout)
    put("stderr", stderr)
    if (droppedStdout > 0) put("droppedStdout", droppedStdout)
    if (droppedStderr > 0) put("droppedStderr", droppedStderr)
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

// 免强制审批的可写安全区: 工作区文件目录, 以及临时目录 /tmp
private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp")

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(name: String): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isOutsideWritableRoots()
    }.getOrDefault(true)

private fun String.isOutsideWritableRoots(): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return WRITABLE_ROOT_PREFIXES.none { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
