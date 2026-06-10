package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_list_files" to false,
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_delete_file" to true,
    "workspace_move_file" to true,
    "workspace_shell" to true,
)

/**
 * Resolves whether [name] needs user approval. A null [overrides] means the stored policy blob was
 * corrupt/unparseable: fail CLOSED (require approval) rather than fall back to the relaxed defaults,
 * so a tampered/garbled column can never silently downgrade a tool to no-approval.
 */
fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>?): Boolean {
    if (overrides == null) return true
    return overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false
}

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    // No row for this id -> expose nothing. For a present row, toolApprovalOverrides() returns null
    // when the policy blob is corrupt, and resolveWorkspaceToolApproval then fails closed (approval
    // required for every tool) instead of relaxing to the defaults.
    val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
    val approvalOverrides = workspace.toolApprovalOverrides()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    // SECURITY GATE (issue #197 design-gate, section C / ranked risk #1): the write-capable and
    // shell verbs (workspace_write_file/edit_file/delete_file/move_file/shell) are an LLM-driven
    // arbitrary-write / code-execution sink and are "gated on the security design, sideload-flavored
    // until reviewed". Until that flavor/feature-gate + shell-enablement enforcement lands (workspace
    // hardening pass), slice 5 exposes ONLY the read-only verbs. The factory functions below remain
    // so the hardening pass can re-enable them behind the gate without re-porting.
    return listOf(
        createListFilesTool(workspaceId, ::needsApproval, workspaceRepository),
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
    )
}

private fun createListFilesTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_list_files",
    description = """
        List files in the assistant's bound workspace. Use area "files" for the working directory and "linux" for the installed Rootfs.
        Response format: entries[].path, name, isDirectory, sizeBytes, updatedAt.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = false)
                putAreaProperty()
            }
        )
    },
    needsApproval = needsApproval("workspace_list_files"),
    execute = {
        val params = it.jsonObject
        val path = params.string("path").orEmpty()
        val area = params.area()
        val entries = workspaceRepository.listFiles(workspaceId, area, path)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("entries", buildJsonArray {
                        entries.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("path", entry.path)
                                    put("name", entry.name)
                                    put("isDirectory", entry.isDirectory)
                                    put("sizeBytes", entry.sizeBytes)
                                    put("updatedAt", entry.updatedAt)
                                }
                            )
                        }
                    })
                }.toString()
            )
        )
    },
)

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a UTF-8 text file from the assistant's bound workspace files area. Paths are relative to the workspace files root.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = needsApproval("workspace_read_file"),
    execute = {
        val path = it.jsonObject.string("path") ?: error("path is required")
        val text = workspaceRepository.readText(workspaceId, path)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    put("text", text)
                }.toString()
            )
        )
    },
)

// Gated (issue #197 design-gate, section C): re-enabled by the workspace hardening pass behind the
// sideload/security flavor. Kept built-but-unwired so the gated pass need not re-port it.
@Suppress("unused")
private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file to the assistant's bound workspace files area. Paths are relative to the workspace files root.
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
    needsApproval = needsApproval("workspace_write_file"),
    execute = {
        val params = it.jsonObject
        val path = params.string("path") ?: error("path is required")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeText(workspaceId, path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

// Gated (issue #197 design-gate, section C): re-enabled by the workspace hardening pass behind the
// sideload/security flavor. Kept built-but-unwired so the gated pass need not re-port it.
@Suppress("unused")
private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file in the assistant's bound workspace files area by replacing exact text.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
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
    needsApproval = needsApproval("workspace_edit_file"),
    execute = {
        val params = it.jsonObject
        val path = params.string("path") ?: error("path is required")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readText(workspaceId, path)
        // Count NON-overlapping occurrences: replace/replaceFirst match non-overlapping, so an
        // overlapping window scan (e.g. "aa" in "aaa" -> 2) would mis-reject single edits and
        // over-report replacements. See countNonOverlappingOccurrences.
        val occurrences = countNonOverlappingOccurrences(original, oldText)
        require(occurrences > 0) { "old_text was not found in $path" }
        if (!replaceAll) {
            require(occurrences == 1) {
                "old_text occurs $occurrences times in $path; set replace_all=true to replace all occurrences"
            }
        }

        val updated = if (replaceAll) original.replace(oldText, newText) else original.replaceFirst(oldText, newText)
        val entry = workspaceRepository.writeText(workspaceId, path, updated, overwrite = true)
        val diff = generateUnifiedDiff(original, updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", if (replaceAll) occurrences else 1)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // the diff goes into metadata for the UI diff view; it is NOT sent to the API with the tool result
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

// Gated (issue #197 design-gate, section C): re-enabled by the workspace hardening pass behind the
// sideload/security flavor. Kept built-but-unwired so the gated pass need not re-port it.
@Suppress("unused")
private fun createDeleteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_delete_file",
    description = """
        Delete a file or directory in the assistant's bound workspace. Use recursive=true for directories.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                putAreaProperty()
                put("recursive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Required when deleting a directory. Defaults to false.")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = needsApproval("workspace_delete_file"),
    execute = {
        val params = it.jsonObject
        val path = params.string("path") ?: error("path is required")
        val area = params.area()
        val recursive = params["recursive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val deleted = workspaceRepository.deleteFile(workspaceId, area, path, recursive)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("success", deleted)
                    put("path", path)
                }.toString()
            )
        )
    },
)

// Gated (issue #197 design-gate, section C): re-enabled by the workspace hardening pass behind the
// sideload/security flavor. Kept built-but-unwired so the gated pass need not re-port it.
@Suppress("unused")
private fun createMoveFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_move_file",
    description = """
        Move or rename a file or directory in the assistant's bound workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source", buildJsonObject {
                    put("type", "string")
                    put("description", "Source path relative to the workspace files root")
                })
                put("target", buildJsonObject {
                    put("type", "string")
                    put("description", "Target path relative to the workspace files root")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite the target if it exists. Defaults to false.")
                })
            },
            required = listOf("source", "target"),
        )
    },
    needsApproval = needsApproval("workspace_move_file"),
    execute = {
        val params = it.jsonObject
        val source = params.string("source") ?: error("source is required")
        val target = params.string("target") ?: error("target is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val entry = workspaceRepository.moveFile(workspaceId, source, target, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

// Gated (issue #197 design-gate, section C / ranked risk #1): the LLM-driven PRoot shell sink.
// Re-enabled ONLY by the workspace hardening pass behind the sideload/security flavor, AND that
// re-enablement MUST also enforce the shell-enablement invariant at the WorkspaceRepository.executeCommand
// sink (require workspace.shellEnabled && shellStatus == READY) — currently unenforced. Kept
// built-but-unwired so the gated pass need not re-port it.
@Suppress("unused")
private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_shell",
    description = """
        Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace.
        Use cwd for a path relative to the workspace files root. Requires Rootfs to be installed and ready.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory relative to the workspace files root. Defaults to root.")
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
    needsApproval = needsApproval("workspace_shell"),
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = params.string("cwd").orEmpty()
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
                }.toString()
            )
        )
    },
)

/**
 * Counts how many times [needle] occurs in [haystack] using NON-overlapping matching, the same
 * semantics as [String.replace] / [String.replaceFirst]. This must match the replacement engine so
 * the edit-file exactly-once guard and the reported `replacements` count are consistent (an
 * overlapping window scan diverges for self-overlapping needles like "aa" in "aaa").
 */
internal fun countNonOverlappingOccurrences(haystack: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var index = haystack.indexOf(needle)
    while (index >= 0) {
        count++
        index = haystack.indexOf(needle, index + needle.length)
    }
    return count
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun kotlinx.serialization.json.JsonObject.area(): WorkspaceStorageArea =
    when (string("area")?.lowercase()) {
        null, "", "files" -> WorkspaceStorageArea.FILES
        "linux", "rootfs" -> WorkspaceStorageArea.LINUX
        else -> error("area must be one of: files, linux")
    }

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) "Path relative to the workspace root"
            else "Optional path relative to the workspace root. Defaults to root."
        )
    })
}

private fun JsonObjectBuilder.putAreaProperty() {
    put("area", buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray {
            add("files")
            add("linux")
        })
        put("description", "Storage area to access. Defaults to files.")
    })
}

private fun me.rerere.workspace.WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
