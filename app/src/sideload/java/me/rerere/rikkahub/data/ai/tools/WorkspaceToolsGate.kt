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
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceManager

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L

// Sideload (non-Play) flavor: HP-2 wires the 5 gated factory functions here (behind I-ENABLE +
// I-APPROVE + I-SURFACE). HP-1 ships the seam EMPTY so both source sets compile and the per-flavor
// resolution is proven with ZERO behavior change — the tool surface is read-only in BOTH flavors
// this slice. The signature must stay identical to the play copy (the stable seam contract).
//
// I-FLAVOR (design note security-model-design:197 §3, §4.1 Option A): the write-capable / shell
// factory BODIES below live in this sideload source set only, so they are PHYSICALLY ABSENT from the
// Play APK — not merely runtime-suppressed. HP-2 fills this body by calling the co-located factories;
// it does not re-port them. The shared param helpers (string/area/putPathProperty/putAreaProperty/
// countNonOverlappingOccurrences) stay in app/src/main/.../WorkspaceTools.kt as `internal`, reused by
// the read-only verbs; only the dangerous sinks are flavor-gated.
internal fun sideloadWorkspaceTools(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    needsApproval: (String) -> Boolean,
): List<Tool> = emptyList()

// Gated (issue #197 design-gate, section C): wired into [sideloadWorkspaceTools] by HP-2 behind the
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

// Gated (issue #197 design-gate, section C): wired into [sideloadWorkspaceTools] by HP-2 behind the
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

// Gated (issue #197 design-gate, section C): wired into [sideloadWorkspaceTools] by HP-2 behind the
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

// Gated (issue #197 design-gate, section C): wired into [sideloadWorkspaceTools] by HP-2 behind the
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
// Re-enabled ONLY by HP-2, by filling the sideload sideloadWorkspaceTools seam. The
// shell-enablement invariant (require workspace.shellEnabled && shellStatus == READY) is ALREADY
// enforced at the WorkspaceRepository.executeCommand sink (I-ENABLE, isShellRunnable) as of HP-1, so
// HP-2 only wires the tool. Kept built-but-unwired so the gated pass need not re-port it.
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

private fun me.rerere.workspace.WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
