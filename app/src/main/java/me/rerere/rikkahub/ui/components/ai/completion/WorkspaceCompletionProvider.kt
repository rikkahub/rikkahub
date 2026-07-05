package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import kotlin.math.max

class WorkspaceCompletionProvider(
    private val workspaceId: String?,
    private val repository: WorkspaceRepository,
    private val currentCwd: String? = null,
) : ChatCompletionProvider {
    override val id: String = "workspace_files"
    private val relativeCwd = currentCwd.toWorkspaceRelativePath()

    private var cachedAt: Long = 0L
    private var cachedEntries: List<WorkspaceFileEntry> = emptyList()

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        if (workspaceId.isNullOrBlank() || context.hasSelection) return null
        val mention = findWorkspaceMention(context.text, context.cursor) ?: return null
        val query = mention.query.normalizeWorkspaceQuery()
        val absoluteQuery = mention.query.isWorkspaceAbsoluteQuery()
        val entries = loadEntries()

        val items = entries
            .asSequence()
            .mapNotNull { entry ->
                val score = entry.matchScore(query, absoluteQuery) ?: return@mapNotNull null
                val path = entry.workspacePath()
                ChatCompletionItem(
                    label = path,
                    insertText = if (entry.isDirectory) "@$path/" else "@$path ",
                    icon = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                    sortScore = score,
                )
            }
            .sortedWith(
                compareByDescending<ChatCompletionItem> { it.sortScore }
                    .thenBy { it.label.length }
                    .thenBy { it.label.lowercase() }
            )
            .take(MAX_COMPLETION_ITEMS)
            .toList()

        if (items.isEmpty()) return null
        return ChatCompletionList(
            providerId = id,
            replacementRange = mention.range,
            items = items,
        )
    }

    private suspend fun loadEntries(): List<WorkspaceFileEntry> {
        val now = System.currentTimeMillis()
        if (cachedAt > 0L && now - cachedAt < CACHE_TTL_MILLIS) {
            return cachedEntries
        }

        val result = mutableListOf<WorkspaceFileEntry>()
        val queue = ArrayDeque<String>()
        val seenDirs = mutableSetOf<String>()
        val seenEntries = mutableSetOf<String>()
        val matcherCache = mutableMapOf<String, WorkspaceIgnoreMatcher>()
        if (relativeCwd.isNotBlank()) {
            queue.add(relativeCwd)
            seenDirs += relativeCwd
        }
        queue.add("")
        seenDirs += ""
        var visitedDirs = 0

        while (queue.isNotEmpty() && result.size < MAX_INDEXED_ENTRIES && visitedDirs < MAX_INDEXED_DIRS) {
            val path = queue.removeFirst()
            visitedDirs++
            val ignoreMatcher = matcherForDirectory(path, matcherCache)
            val entries = try {
                repository.listFiles(
                    id = workspaceId ?: return emptyList(),
                    area = WorkspaceStorageArea.FILES,
                    path = path,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            entries.forEach { entry ->
                if (result.size >= MAX_INDEXED_ENTRIES) return@forEach
                if (ignoreMatcher.isIgnored(entry.path, entry.isDirectory)) return@forEach
                if (seenEntries.add(entry.path)) {
                    result += entry
                }
                if (entry.isDirectory && queue.size < MAX_INDEXED_DIRS && seenDirs.add(entry.path)) {
                    queue.add(entry.path)
                }
            }
        }

        cachedEntries = result
        cachedAt = now
        return result
    }

    private suspend fun matcherForDirectory(
        directory: String,
        cache: MutableMap<String, WorkspaceIgnoreMatcher>,
    ): WorkspaceIgnoreMatcher {
        val normalized = directory.toWorkspaceRelativePath()
        cache[normalized]?.let { return it }

        var matcher = WorkspaceIgnoreMatcher()
        directoryAncestors(normalized).forEach { path ->
            cache[path]?.let {
                matcher = it
                return@forEach
            }
            val gitignore = readGitignore(path)
            if (gitignore != null) {
                matcher = matcher.withGitignore(path, gitignore)
            }
            cache[path] = matcher
        }
        return matcher
    }

    private suspend fun readGitignore(directory: String): String? {
        val id = workspaceId ?: return null
        val path = if (directory.isBlank()) ".gitignore" else "$directory/.gitignore"
        return try {
            repository.readText(id, path)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun directoryAncestors(path: String): List<String> {
        if (path.isBlank()) return listOf("")
        val parts = path.split('/').filter { it.isNotBlank() }
        val result = mutableListOf("")
        parts.fold("") { current, part ->
            val next = if (current.isBlank()) part else "$current/$part"
            result += next
            next
        }
        return result
    }

    private fun WorkspaceFileEntry.workspacePath(): String = "/workspace/$path"

    private fun WorkspaceFileEntry.matchScore(query: String, absoluteQuery: Boolean): Int? {
        val normalizedPath = path.lowercase()
        val normalizedName = name.lowercase()
        val normalizedQuery = query.lowercase()
        val globalScore = if (normalizedQuery.isBlank()) {
            1
        } else {
            max(
                normalizedPath.fuzzyScore(normalizedQuery) ?: -1,
                normalizedName.fuzzyScore(normalizedQuery) ?: -1,
            ).takeIf { it >= 0 } ?: return null
        }

        val cwdScore = if (!absoluteQuery) matchCwdScore(normalizedQuery) else null
        val score = max(globalScore, cwdScore ?: -1)
        return score + if (isDirectory) DIRECTORY_SCORE_BONUS else 0
    }

    private fun WorkspaceFileEntry.matchCwdScore(query: String): Int? {
        val cwdPath = path.relativeToCwdOrNull()?.lowercase() ?: return null
        val score = if (query.isBlank()) {
            1
        } else {
            max(
                cwdPath.fuzzyScore(query) ?: -1,
                name.lowercase().fuzzyScore(query) ?: -1,
            ).takeIf { it >= 0 } ?: return null
        }
        return score + CWD_SCORE_BONUS
    }

    private fun String.relativeToCwdOrNull(): String? {
        if (relativeCwd.isBlank()) return this
        if (this == relativeCwd) return ""
        val prefix = "$relativeCwd/"
        return if (startsWith(prefix)) removePrefix(prefix) else null
    }

    private fun String.fuzzyScore(query: String): Int? {
        if (query.isBlank()) return 1
        if (this == query) return 1000
        if (startsWith(query)) return 900 - length.coerceAtMost(200)
        val containsIndex = indexOf(query)
        if (containsIndex >= 0) return 800 - containsIndex.coerceAtMost(200)

        var queryIndex = 0
        var firstMatch = -1
        var lastMatch = -1
        forEachIndexed { index, char ->
            if (queryIndex < query.length && char == query[queryIndex]) {
                if (firstMatch < 0) firstMatch = index
                lastMatch = index
                queryIndex++
            }
        }
        if (queryIndex != query.length) return null

        val span = (lastMatch - firstMatch + 1).coerceAtLeast(query.length)
        return 500 - span.coerceAtMost(300) - firstMatch.coerceAtLeast(0).coerceAtMost(100)
    }

    private fun String.normalizeWorkspaceQuery(): String {
        val normalized = replace('\\', '/').trimStart()
        return normalized.removeWorkspacePrefix().trimStart('/')
    }

    private fun String?.toWorkspaceRelativePath(): String {
        if (isNullOrBlank()) return ""
        return replace('\\', '/')
            .trim()
            .removeWorkspacePrefix()
            .trim('/')
    }

    private fun String.isWorkspaceAbsoluteQuery(): Boolean {
        val normalized = replace('\\', '/').trimStart()
        return normalized.startsWith("/") || normalized == "workspace" || normalized.startsWith("workspace/")
    }

    private fun String.removeWorkspacePrefix(): String {
        return when {
            this == "/workspace" || this == "workspace" -> ""
            startsWith("/workspace/") -> removePrefix("/workspace/")
            startsWith("workspace/") -> removePrefix("workspace/")
            else -> this
        }
    }

    private data class WorkspaceMention(
        val query: String,
        val range: TextRange,
    )

    private fun findWorkspaceMention(text: String, cursor: Int): WorkspaceMention? {
        if (cursor < 0 || cursor > text.length) return null
        val prefix = text.substring(0, cursor)
        val start = prefix.lastIndexOf('@')
        if (start < 0) return null
        if (start > 0 && !text[start - 1].isMentionBoundary()) return null

        val query = prefix.substring(start + 1)
        if (query.any { it.isWhitespace() }) return null
        return WorkspaceMention(
            query = query,
            range = TextRange(start, cursor),
        )
    }

    private fun Char.isMentionBoundary(): Boolean =
        isWhitespace() || this in "([{<\"'"

    companion object {
        private const val MAX_COMPLETION_ITEMS = 8
        private const val MAX_INDEXED_ENTRIES = 500
        private const val MAX_INDEXED_DIRS = 80
        private const val CACHE_TTL_MILLIS = 5_000L
        private const val DIRECTORY_SCORE_BONUS = 25
        private const val CWD_SCORE_BONUS = 1_500
    }
}
