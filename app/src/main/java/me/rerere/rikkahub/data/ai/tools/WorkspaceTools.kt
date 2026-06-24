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
import me.rerere.workspace.WorkspaceCwdPolicy
import me.rerere.workspace.WorkspaceStorageArea
import kotlin.uuid.Uuid

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_list_files" to false,
    "workspace_read_file" to false,
    // workspace_shell_tail reads a background run's app-private output by taskId — a read, so it
    // defaults to no-approval like the other read verbs (issue #291).
    "workspace_shell_tail" to false,
    // I-APPROVE (#197 HP-1, design note §4.2): arbitrary write/edit must break the auto-loop like
    // shell/delete/move — an LLM-driven write is a write-capable sink, not a read.
    "workspace_write_file" to true,
    "workspace_edit_file" to true,
    "workspace_delete_file" to true,
    "workspace_move_file" to true,
    "workspace_shell" to true,
)

/**
 * Resolves whether [name] needs user approval. Two fail-CLOSED guards:
 *  - a null [overrides] means the stored policy blob was corrupt/unparseable — require approval
 *    rather than fall back to the relaxed defaults, so a tampered/garbled column can never silently
 *    downgrade a tool to no-approval.
 *  - an unknown tool [name] (no override AND no [WorkspaceToolDefaultApprovals] entry) defaults to
 *    approval-required (issue #356 finding #6). A future `workspace_*` tool whose default-approval
 *    entry is forgotten must require approval, never silently become no-approval. Every current
 *    factory tool has an explicit entry; `WorkspaceToolsTest` pins that, so this fallback only ever
 *    fires for a genuinely unregistered name.
 */
fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>?): Boolean {
    if (overrides == null) return true
    return overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: true
}

suspend fun createWorkspaceTools(
    workspaceId: String?,
    // The conversation this tool pool serves. The background-shell completion (issue #291) is routed
    // back into THIS conversation as a synthetic #290 event; Tool.execute has no toolCallId, so the
    // conversation is threaded through the factory rather than recovered from the args.
    conversationId: Uuid,
    workspaceRepository: WorkspaceRepository,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    // No row for this id -> expose nothing. For a present row, toolApprovalOverrides() returns null
    // when the policy blob is corrupt, and resolveWorkspaceToolApproval then fails closed (approval
    // required for every tool) instead of relaxing to the defaults.
    val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
    val approvalOverrides = workspace.toolApprovalOverrides()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    // The agent's project working directory, in canonical /workspace/... form, surfaced to the model
    // via the anchor tool's systemPrompt below so it KNOWS where relative paths land (without this it
    // only knows a project dir exists, not its value, and falls back to absolute paths / guessing).
    // Computed from the stored, already-normalized seed — pure, no filesystem IO, cannot throw and fail
    // the turn; the actual containment check still runs at each file/shell operation. This note is a
    // per-turn snapshot (the pool is rebuilt each turn); the tools THEMSELVES resolve against the current
    // working_dir live, so a mid-turn project-dir change takes effect in tool behavior immediately and is
    // reflected in this note on the next turn.
    val projectDir = WorkspaceCwdPolicy.toShellPath(WorkspaceCwdPolicy.normalize(workspace.workingDir))

    // SECURITY GATE (issue #197 design-gate §C / design note security-model-design:197 §4.1 Option A,
    // §3 I-FLAVOR): the write-capable and shell verbs (workspace_write_file/edit_file/delete_file/
    // move_file/shell) are an LLM-driven arbitrary-write / code-execution sink, "sideload-flavored
    // until reviewed". Their factory bodies live ONLY in app/src/sideload/.../WorkspaceToolsGate.kt —
    // physically ABSENT from the Play APK, not merely runtime-suppressed. HP-1 establishes the
    // per-flavor seam [sideloadWorkspaceTools]: it returns emptyList() in BOTH the play and sideload
    // source sets, so the surface stays read-only in every flavor this slice. HP-2 fills ONLY the
    // sideload copy (behind I-ENABLE + I-APPROVE + I-SURFACE) by calling the now-co-located factories.
    return listOf(
        createListFilesTool(workspaceId, projectDir, ::needsApproval, workspaceRepository),
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
    ) + sideloadWorkspaceTools(workspaceId, conversationId, workspaceRepository, ::needsApproval)
}

/**
 * The standing system-prompt note that tells the agent WHERE its workspace is — the resolved project
 * working directory in canonical `/workspace/...` form. Without it the agent only knows a project dir
 * exists (the tool descriptions say "relative to the project working directory") but not its VALUE, so
 * it falls back to absolute `/workspace` paths or trial-and-error. Emitted once per turn via the anchor
 * [createListFilesTool], which is present in every flavor whenever a workspace is bound.
 */
internal fun workspaceContextPrompt(projectDir: String): String {
    // SECURITY: projectDir is interpolated into the system prompt. A directory name is attacker-
    // influenceable (the agent can create one via the shell, the user may then select it as the
    // project dir) and `normalize` only rejects NUL, not newlines/other control chars — so strip every
    // control char here, at the prompt boundary, before it can break the prompt framing or smuggle in
    // instructions. A real path never legitimately contains control characters.
    val safe = projectDir.filterNot { it.isISOControl() }
    return "A workspace is bound to this assistant: its files area is mounted at /workspace, and your " +
        "project working directory is $safe. Paths you pass to the workspace tools (and a workspace " +
        "shell's default working directory) are relative to this project directory; begin a path with " +
        "/workspace to address the files root instead."
}

private fun createListFilesTool(
    workspaceId: String,
    projectDir: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_list_files",
    description = """
        List files in the assistant's bound workspace. Use area "files" for the project working directory and "linux" for the installed Rootfs.
        For the files area, path is relative to the project working directory (use an absolute /workspace/... path for the files root) and each entries[].path is returned as an absolute /workspace/... path.
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
    // The anchor tool carries the workspace context note (project dir) so the agent knows where
    // relative paths resolve; list_files is present in every flavor a workspace is bound in.
    systemPrompt = { _, _ -> workspaceContextPrompt(projectDir) },
    needsApproval = needsApproval("workspace_list_files"),
    execute = {
        val params = it.jsonObject
        val area = params.area()
        val isFiles = area == WorkspaceStorageArea.FILES
        val path = params.string("path").orEmpty()
        // FILES paths are project-relative on input and canonical /workspace/... on output (the unified
        // base both file tools and the shell share); the read-only linux rootfs listing keeps its own
        // rootfs-relative addressing, so it is passed through and reported unchanged.
        val resolved = if (isFiles) workspaceRepository.resolveFilesPath(workspaceId, path) else path
        val entries = workspaceRepository.listFiles(workspaceId, area, resolved)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("entries", buildJsonArray {
                        entries.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("path", if (isFiles) WorkspaceCwdPolicy.toShellPath(entry.path) else entry.path)
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
        Read a UTF-8 text file from the assistant's bound workspace files area. Paths are relative to the project working directory; use an absolute /workspace/... path to address the files root. The file is returned as a line window, not always in full: pass offset (1-based start line) and limit (max lines, default $DEFAULT_READ_FILE_LINE_LIMIT) to page through a large file. The result reports totalLines and, when lines remain past the window, hasMore=true.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional 1-based line number to start reading from. Defaults to 1 (start of file).")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Optional maximum number of lines to return. Defaults to $DEFAULT_READ_FILE_LINE_LIMIT; a larger file is windowed to this many lines (read totalLines/hasMore and page with offset)."
                    )
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = needsApproval("workspace_read_file"),
    execute = {
        val params = it.jsonObject
        val path = params.string("path") ?: error("path is required")
        // Number args may arrive as a JSON number or a string; .string()?.toIntOrNull() accepts both
        // (the same lenient parse the shell tool uses for `timeout`).
        val offset = params.string("offset")?.toIntOrNull()
        val limit = params.string("limit")?.toIntOrNull()
        val resolved = workspaceRepository.resolveFilesPath(workspaceId, path)
        val window = windowTextByLines(workspaceRepository.readText(workspaceId, resolved), offset, limit)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", WorkspaceCwdPolicy.toShellPath(resolved))
                    put("text", window.text)
                    put("totalLines", window.totalLines)
                    put("startLine", window.startLine)
                    put("endLine", window.endLine)
                    if (window.hasMore) put("hasMore", true)
                }.toString()
            )
        )
    },
)

/**
 * Default maximum number of lines [createReadFileTool] returns when the model does not pass an explicit
 * `limit`. A larger file is windowed to this many lines (the result carries totalLines + hasMore so the
 * model can page through with `offset`) instead of dumping the whole file into the tool output — the
 * whole-file read is what made the tool expensive and lagged the UI on 1000+ line files.
 */
internal const val DEFAULT_READ_FILE_LINE_LIMIT = 2000

/** One line window of a file: the sliced [text] plus the metadata the model needs to page through. */
internal data class ReadFileWindow(
    val text: String,
    val totalLines: Int,
    /** 1-based line number of the first returned line, or 0 when the window is empty. */
    val startLine: Int,
    /** 1-based line number of the last returned line, or 0 when the window is empty. */
    val endLine: Int,
    /** True when lines remain past this window (i.e. [endLine] < [totalLines]). */
    val hasMore: Boolean,
)

/**
 * Window [text] to at most [limit] lines starting at the 1-based [offset] (both optional; null =>
 * offset 1 / limit [DEFAULT_READ_FILE_LINE_LIMIT]). An out-of-range offset yields an empty window
 * rather than throwing. The returned [ReadFileWindow.text] is RAW — no line-number prefixes — so
 * workspace_edit_file's exact-string matching still works against a read result.
 */
internal fun windowTextByLines(text: String, offset: Int?, limit: Int?): ReadFileWindow {
    // An empty file has zero lines (like `cat -n`), not one empty line — "".split("\n") would otherwise
    // report a phantom single line. Short-circuit so the metadata stays consistent (0 lines, empty range).
    if (text.isEmpty()) return ReadFileWindow(text = "", totalLines = 0, startLine = 0, endLine = 0, hasMore = false)
    // A trailing newline terminates the final line; it must NOT count as an extra empty line, or a
    // file saved with a final newline would report one line too many (and a full 2000-line file would
    // wrongly come back as truncated with hasMore=true). This matches conventional `cat -n` counting.
    val endsWithNewline = text.endsWith("\n")
    val lines = (if (endsWithNewline) text.dropLast(1) else text).split("\n")
    val totalLines = lines.size
    val start = (offset ?: 1).coerceAtLeast(1)
    val count = (limit ?: DEFAULT_READ_FILE_LINE_LIMIT).coerceAtLeast(1)
    val startIdx = (start - 1).coerceIn(0, totalLines)
    // Widen to Long for the end index: a limit near Int.MAX_VALUE would overflow startIdx + count as
    // Int to a negative value and produce an invalid subList range. totalLines bounds the result.
    val endIdx = minOf(startIdx.toLong() + count.toLong(), totalLines.toLong()).toInt()
    val returned = endIdx - startIdx
    val sliced = if (returned <= 0) "" else lines.subList(startIdx, endIdx).joinToString("\n")
    // Re-append the file's terminating newline when the window reaches EOF, so the returned text stays a
    // byte-exact substring of the file: workspace_edit_file matches against raw file content, so an edit
    // touching the final line/newline must be derivable from the read output.
    val windowText = if (returned > 0 && endIdx == totalLines && endsWithNewline) sliced + "\n" else sliced
    return ReadFileWindow(
        text = windowText,
        totalLines = totalLines,
        startLine = if (returned <= 0) 0 else startIdx + 1,
        endLine = if (returned <= 0) 0 else endIdx,
        hasMore = endIdx < totalLines,
    )
}

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

/**
 * Resolve a MODEL-supplied [modelPath] to the files-root-relative path the repository/manager expect,
 * honoring the workspace project dir (the unified base): a relative path is project-relative (resolved
 * against the row's `working_dir`, blank => the files root), a `/workspace/...` path is root-absolute.
 * Fetches `working_dir` at CALL time so a project-dir change in the terminal/sheet takes effect without
 * recreating the tool pool. FILES area only — the read-only `linux` rootfs listing keeps its own
 * rootfs-relative addressing. The reported output path is the canonical `/workspace/...` form of the
 * resolved value (via [WorkspaceCwdPolicy.toShellPath]), so a list -> read round-trip never double-joins
 * a project-relative path onto the project dir twice.
 */
internal suspend fun WorkspaceRepository.resolveFilesPath(workspaceId: String, modelPath: String): String =
    WorkspaceCwdPolicy.resolveModelPath(workingDirOf(workspaceId), modelPath)

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
            if (required) "Path relative to the project working directory. Use an absolute /workspace/... path to address the files root."
            else "Optional path relative to the project working directory (use an absolute /workspace/... path for the files root). Defaults to the project working directory."
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
