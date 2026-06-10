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
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceStorageArea

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_list_files" to false,
    "workspace_read_file" to false,
    // I-APPROVE (#197 HP-1, design note §4.2): arbitrary write/edit must break the auto-loop like
    // shell/delete/move — an LLM-driven write is a write-capable sink, not a read.
    "workspace_write_file" to true,
    "workspace_edit_file" to true,
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

    // SECURITY GATE (issue #197 design-gate §C / design note security-model-design:197 §4.1 Option A,
    // §3 I-FLAVOR): the write-capable and shell verbs (workspace_write_file/edit_file/delete_file/
    // move_file/shell) are an LLM-driven arbitrary-write / code-execution sink, "sideload-flavored
    // until reviewed". Their factory bodies live ONLY in app/src/sideload/.../WorkspaceToolsGate.kt —
    // physically ABSENT from the Play APK, not merely runtime-suppressed. HP-1 establishes the
    // per-flavor seam [sideloadWorkspaceTools]: it returns emptyList() in BOTH the play and sideload
    // source sets, so the surface stays read-only in every flavor this slice. HP-2 fills ONLY the
    // sideload copy (behind I-ENABLE + I-APPROVE + I-SURFACE) by calling the now-co-located factories.
    return listOf(
        createListFilesTool(workspaceId, ::needsApproval, workspaceRepository),
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
    ) + sideloadWorkspaceTools(workspaceId, workspaceRepository, ::needsApproval)
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

// internal (not private): the gated write/shell factories live in app/src/sideload/ (I-FLAVOR,
// design note §4.1 Option A) and call these shared param helpers across the flavor source set.
internal fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

internal fun kotlinx.serialization.json.JsonObject.area(): WorkspaceStorageArea =
    when (string("area")?.lowercase()) {
        null, "", "files" -> WorkspaceStorageArea.FILES
        "linux", "rootfs" -> WorkspaceStorageArea.LINUX
        else -> error("area must be one of: files, linux")
    }

internal fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) "Path relative to the workspace root"
            else "Optional path relative to the workspace root. Defaults to root."
        )
    })
}

internal fun JsonObjectBuilder.putAreaProperty() {
    put("area", buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray {
            add("files")
            add("linux")
        })
        put("description", "Storage area to access. Defaults to files.")
    })
}
