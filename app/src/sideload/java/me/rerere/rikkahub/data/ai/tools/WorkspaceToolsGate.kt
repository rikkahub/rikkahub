package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.ai.shellrun.ShellRunResult
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.common.text.generateUnifiedDiff
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import kotlin.uuid.Uuid

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L

// Bytes of a background run's output the tail tool returns by default; bounded so a runaway file
// cannot blow out the model context.
private const val SHELL_TAIL_MAX_BYTES = 32 * 1024

// Sideload (non-Play) flavor: the 5 gated factory functions are wired here (behind I-ENABLE +
// I-APPROVE + I-SURFACE). The signature stays identical to the play copy (the stable seam contract);
// the play copy returns emptyList() so these verbs are unreachable in the Play APK.
//
// I-FLAVOR (design note security-model-design:197 §3, §4.1 Option A): the write-capable / shell
// factory BODIES below live in this sideload source set only, so they are PHYSICALLY ABSENT from the
// Play APK — not merely runtime-suppressed. The shared param helpers (string/area/putPathProperty/
// putAreaProperty/countNonOverlappingOccurrences) stay in app/src/main/.../WorkspaceTools.kt as
// `internal`, reused by the read-only verbs; only the dangerous sinks are flavor-gated.
//
// I-ENABLE is enforced one layer down at WorkspaceRepository.executeCommand (shellEnabled &&
// shellStatus==READY); I-APPROVE is the needsApproval predicate (write/edit/delete/move/shell all
// default true and fail closed). I-SURFACE applies to the FILE-MANAGEMENT verbs
// (write/edit/delete/move): they are pinned to the workspace `files` area and expose no
// model-facing `area=linux` option, so the model cannot redirect a file write/delete at the
// installed rootfs (only the read-only workspace_list_files exposes `area`). workspace_shell is
// deliberately exempt: a shell's purpose is to run inside the PRoot linux rootfs, which is the
// sandbox's own ephemeral, reinstallable filesystem (mutating it is expected and contained, not a
// surface escape) — the real workspace data lives in `files`, bind-mounted at /workspace.
internal fun sideloadWorkspaceTools(
    workspaceId: String,
    conversationId: Uuid,
    workspaceRepository: WorkspaceRepository,
    needsApproval: (String) -> Boolean,
): List<Tool> = listOf(
    createWriteFileTool(workspaceId, needsApproval, workspaceRepository),
    createEditFileTool(workspaceId, needsApproval, workspaceRepository),
    createDeleteFileTool(workspaceId, needsApproval, workspaceRepository),
    createMoveFileTool(workspaceId, needsApproval, workspaceRepository),
    createShellTool(workspaceId, conversationId, needsApproval, workspaceRepository),
    createShellTailTool(workspaceId, conversationId, needsApproval, workspaceRepository),
)

// Gated (issue #197 design-gate, section C): wired into [sideloadWorkspaceTools] behind the
// sideload/security flavor; physically absent from the Play APK (I-FLAVOR).
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

// Gated (issue #197 design-gate, section C): wired into [sideloadWorkspaceTools] behind the
// sideload/security flavor; physically absent from the Play APK (I-FLAVOR).
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

// Gated (issue #197 design-gate, section C): wired into [sideloadWorkspaceTools] behind the
// sideload/security flavor; physically absent from the Play APK (I-FLAVOR).
private fun createDeleteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_delete_file",
    description = """
        Delete a file or directory in the assistant's bound workspace files area. Use recursive=true for directories.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                // I-SURFACE: no `area` parameter — a write-capable verb is pinned to the workspace
                // `files` area and must never target the installed `linux` rootfs, so the model
                // cannot widen its own surface. Only the read-only workspace_list_files exposes area.
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
        val recursive = params["recursive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val deleted = workspaceRepository.deleteFile(workspaceId, WorkspaceStorageArea.FILES, path, recursive)
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

// Gated (issue #197 design-gate, section C): wired into [sideloadWorkspaceTools] behind the
// sideload/security flavor; physically absent from the Play APK (I-FLAVOR).
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
// Re-enabled ONLY by HP-2, by filling the sideload sideloadWorkspaceTools seam. The
// shell-enablement invariant (require workspace.shellEnabled && shellStatus == READY) is enforced at
// the WorkspaceRepository.executeCommand sink (I-ENABLE, isShellRunnable); this factory only wires
// the tool. Physically absent from the Play APK (I-FLAVOR).
private fun createShellTool(
    workspaceId: String,
    conversationId: Uuid,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_shell",
    description = """
        Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace.
        Use cwd for a path relative to the workspace files root. Requires Rootfs to be installed and ready.
        Set detachAfterSeconds to auto-background a long-running command after that many seconds: the tool then
        returns {taskId, status: running, outputRef, tail} immediately, keeps running, and notifies you when it
        completes; read its output later with workspace_shell_tail. Omit detachAfterSeconds for the default blocking run.
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
                put("detachAfterSeconds", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Optional. Auto-background the command after this many seconds if it has not finished. " +
                            "Omit (the default) to run blocking and return the full result inline."
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
        // NULLABLE (no .orEmpty()): an ABSENT `cwd` arg (key missing) stays null so the repository's
        // central policy resolves it to working_dir/default, while an explicit "" / "." maps to the
        // files root. Collapsing absent->"" (the old `.orEmpty()`) destroyed that distinction (#282).
        val cwd = params.string("cwd")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        // detachAfterSeconds is OPT-IN and DEFAULT OFF: absent / non-positive => the byte-identical
        // blocking path. `timeout` is NEVER reinterpreted as detach (design proposal migration note).
        val detachAfterSeconds = params.string("detachAfterSeconds")?.toIntOrNull()
            ?.takeIf { secs -> secs > 0 }
            ?.coerceAtMost(SHELL_TIMEOUT_MAX_SECONDS.toInt())
        if (detachAfterSeconds == null) {
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
        } else {
            val outcome = workspaceRepository.startBackgroundCommand(
                id = workspaceId,
                conversationId = conversationId,
                command = command,
                cwd = cwd,
                detachAfterSeconds = detachAfterSeconds,
                hardTimeoutMillis = timeoutMillis,
            )
            listOf(
                UIMessagePart.Text(
                    when (outcome) {
                        // Exited before detach: byte-compatible with the blocking shape.
                        is ShellRunResult.Inline -> buildJsonObject {
                            put("exitCode", outcome.result.exitCode)
                            put("stdout", outcome.result.stdout)
                            put("stderr", outcome.result.stderr)
                            put("timedOut", outcome.result.timedOut)
                            if (outcome.result.truncated) put("truncated", true)
                        }.toString()
                        // Backgrounded: hand back the handle. Completion arrives as a later message.
                        is ShellRunResult.Detached -> buildJsonObject {
                            put("taskId", outcome.taskId.toString())
                            put("status", "running")
                            put("outputRef", outcome.outputRef)
                            put("tail", outcome.tail)
                        }.toString()
                    }
                )
            )
        }
    },
)

// Read tool for a backgrounded shell run's app-private output (issue #291). The output file lives
// OUTSIDE the workspace root, so workspace_read_file cannot reach it; this is the tail seam. The
// factory name is createShellTailTool (NOT createShellTool) so the P-FLAVOR javap guard regex
// create(Write|Edit|Delete|Move|Shell)Tool does not match it — it still lives in sideload only, so
// the play seam stays emptyList() and the read never enters the Play classpath. It is a READ, so it
// defaults to no-approval (WorkspaceToolDefaultApprovals["workspace_shell_tail"] = false).
private fun createShellTailTool(
    workspaceId: String,
    conversationId: Uuid,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_shell_tail",
    description = """
        Read the trailing output of a backgrounded workspace_shell run by its taskId. Use this after a
        workspace_shell call returned status: running, or after a completion notification, to inspect the output.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("taskId", buildJsonObject {
                    put("type", "string")
                    put("description", "The taskId returned by a backgrounded workspace_shell run")
                })
                put("maxBytes", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional. Trailing bytes to read. Defaults to $SHELL_TAIL_MAX_BYTES.")
                })
            },
            required = listOf("taskId"),
        )
    },
    needsApproval = needsApproval("workspace_shell_tail"),
    execute = {
        val params = it.jsonObject
        val rawTaskId = params.string("taskId") ?: error("taskId is required")
        // Reject any taskId that is not a canonical UUID before it reaches the file path.
        val taskId = runCatching { Uuid.parse(rawTaskId).toString() }
            .getOrElse { error("invalid taskId: must be a UUID") }
        val maxBytes = params.string("maxBytes")?.toIntOrNull()?.coerceIn(1, MAX_SHELL_TAIL_BYTES)
            ?: SHELL_TAIL_MAX_BYTES
        val tail = workspaceRepository.tailShellRun(workspaceId, conversationId, taskId, maxBytes)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("taskId", taskId)
                    put("tail", tail)
                }.toString()
            )
        )
    },
)

// Hard ceiling on a single tail read so a huge maxBytes request cannot blow out the model context.
private const val MAX_SHELL_TAIL_BYTES = 256 * 1024

private fun me.rerere.workspace.WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
