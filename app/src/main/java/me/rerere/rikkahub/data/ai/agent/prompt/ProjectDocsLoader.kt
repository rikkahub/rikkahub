package me.rerere.rikkahub.data.ai.agent.prompt

import android.util.Log
import me.rerere.rikkahub.data.repository.WorkspaceRepository

/**
 * 加载 Workspace 内项目指令文件（学 Codex AGENTS.md / Claude Code CLAUDE.md）。
 *
 * 优先级低于助手 systemPrompt / 用户指令；仅在文件存在时注入。
 */
class ProjectDocsLoader(
    private val workspaceRepository: WorkspaceRepository,
    private val fileNames: List<String> = DEFAULT_FILE_NAMES,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    /**
     * @param workspaceId workspace id
     * @param cwd absolute rootfs path like /workspace or /workspace/foo；可为 null
     */
    suspend fun load(workspaceId: String, cwd: String?, expectedRoot: String? = null): String {
        val candidates = candidatePaths(cwd)
        val sections = mutableListOf<Pair<String, String>>()
        var total = 0

        for (dir in candidates) {
            for (name in fileNames) {
                val relative = joinPath(dir, name)
                val content = readQuietly(workspaceId, relative, expectedRoot) ?: continue
                val remaining = maxChars - total
                if (remaining <= 0) break
                val clipped = if (content.length > remaining) {
                    content.take(remaining) + "\n...[truncated]"
                } else {
                    content
                }
                sections += relative to clipped
                total += clipped.length
            }
            if (total >= maxChars) break
        }

        if (sections.isEmpty()) return ""

        return buildString {
            appendLine("<project_docs>")
            appendLine("The following project instruction files apply to this workspace.")
            appendLine("User/assistant system instructions take precedence over these docs.")
            sections.forEach { (path, body) ->
                appendLine()
                appendLine("### $path")
                appendLine(body.trimEnd())
            }
            append("</project_docs>")
        }.trim()
    }

    private suspend fun readQuietly(workspaceId: String, path: String, expectedRoot: String?): String? = try {
        workspaceRepository.readText(workspaceId, path, expectedRoot).takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.d(TAG, "project doc not found or unreadable: $path (${e.message})")
        null
    }

    companion object {
        private const val TAG = "ProjectDocsLoader"
        const val DEFAULT_MAX_CHARS = 32 * 1024
        val DEFAULT_FILE_NAMES = listOf("AGENTS.md", "CLAUDE.md", "RIKKA.md")

        fun joinPath(dir: String, name: String): String =
            if (dir.isEmpty()) name else "$dir/$name"

        /** FILES 区路径：workspace 根 = ""；cwd /workspace/foo -> foo */
        fun candidatePaths(cwd: String?): List<String> {
            val dirs = linkedSetOf<String>()
            dirs += ""
            val relativeCwd = cwd
                ?.removePrefix("/workspace/")
                ?.removePrefix("/workspace")
                ?.trim('/')
                .orEmpty()
            if (relativeCwd.isNotEmpty()) {
                val parts = relativeCwd.split('/').filter { it.isNotBlank() }
                var acc = ""
                for (part in parts) {
                    acc = if (acc.isEmpty()) part else "$acc/$part"
                    dirs += acc
                }
            }
            return dirs.toList()
        }
    }
}
