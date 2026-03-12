package me.rerere.rikkahub.data.skills

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.termux.TermuxCommandManager
import me.rerere.rikkahub.data.ai.tools.termux.TermuxRunCommandRequest
import me.rerere.rikkahub.data.ai.tools.termux.isSuccessful
import me.rerere.rikkahub.data.datastore.SettingsStore

private const val TAG = "SkillsRepository"
private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"

data class SkillCatalogEntry(
    val directoryName: String,
    val path: String,
    val name: String,
    val description: String,
)

data class SkillInvalidEntry(
    val directoryName: String,
    val path: String,
    val reason: String,
)

data class SkillsCatalogState(
    val workdir: String = "",
    val rootPath: String = "",
    val entries: List<SkillCatalogEntry> = emptyList(),
    val invalidEntries: List<SkillInvalidEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val refreshedAt: Long = 0L,
) {
    val entryNames: Set<String> = entries.mapTo(linkedSetOf()) { it.directoryName }
}

internal data class SkillFrontmatter(
    val name: String,
    val description: String,
)

internal sealed interface SkillFrontmatterParseResult {
    data class Success(val frontmatter: SkillFrontmatter) : SkillFrontmatterParseResult
    data class Error(val reason: String) : SkillFrontmatterParseResult
}

internal data class SkillDirectoryDescriptor(
    val directoryName: String,
    val path: String,
    val hasSkillFile: Boolean,
)

internal data class SkillCatalogDiscoveryResult(
    val entries: List<SkillCatalogEntry>,
    val invalidEntries: List<SkillInvalidEntry>,
)

internal sealed interface SkillDirectoryInspectionResult {
    data class Valid(val entry: SkillCatalogEntry) : SkillDirectoryInspectionResult
    data class Invalid(val entry: SkillInvalidEntry) : SkillDirectoryInspectionResult
}

class SkillsRepository(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val termuxCommandManager: TermuxCommandManager,
) {
    private val refreshMutex = Mutex()
    private val _state = MutableStateFlow(SkillsCatalogState())
    val state: StateFlow<SkillsCatalogState> = _state.asStateFlow()

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .map { it.termuxWorkdir }
                .distinctUntilChanged()
                .collect { workdir ->
                    refresh(workdir)
                }
        }
    }

    fun requestRefresh() {
        appScope.launch {
            refresh(settingsStore.settingsFlow.value.termuxWorkdir)
        }
    }

    suspend fun refresh(workdir: String = settingsStore.settingsFlow.value.termuxWorkdir) {
        refreshMutex.withLock {
            val rootPath = buildSkillsRootPath(workdir)
            _state.value = _state.value.toRefreshingCatalogState(
                workdir = workdir,
                rootPath = rootPath,
            )
            _state.value = runCatching {
                discover(workdir = workdir, rootPath = rootPath)
            }.getOrElse { error ->
                Log.w(TAG, "refresh failed for $rootPath", error)
                SkillsCatalogState(
                    workdir = workdir,
                    rootPath = rootPath,
                    entries = emptyList(),
                    invalidEntries = emptyList(),
                    isLoading = false,
                    error = error.message ?: error.javaClass.name,
                    refreshedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun discover(
        workdir: String,
        rootPath: String,
    ): SkillsCatalogState {
        val listed = listSkillDirectories(rootPath)
        val discovery = discoverCatalogEntries(
            directories = listed,
            readSkillFile = ::readSkillFile,
        )

        return SkillsCatalogState(
            workdir = workdir,
            rootPath = rootPath,
            entries = discovery.entries.sortedBy { it.directoryName },
            invalidEntries = discovery.invalidEntries.sortedBy { it.directoryName },
            isLoading = false,
            error = null,
            refreshedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun listSkillDirectories(rootPath: String): List<SkillDirectoryDescriptor> {
        val script = buildListScript(rootPath)
        val result = termuxCommandManager.run(
            TermuxRunCommandRequest(
                commandPath = TERMUX_BASH_PATH,
                arguments = listOf("-lc", script),
                workdir = settingsStore.settingsFlow.value.termuxWorkdir,
                background = false,
                timeoutMs = 30_000L,
                label = "RikkaHub list local skills",
            )
        )
        if (!result.isSuccessful()) {
            error(result.errMsg.orEmpty().ifBlank { result.stderr.orEmpty().ifBlank { "Failed to list skills" } })
        }
        return result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                SkillDirectoryDescriptor(
                    directoryName = parts[0],
                    path = parts[1],
                    hasSkillFile = parts[2] == "1",
                )
            }
            .toList()
    }

    private suspend fun readSkillFile(directoryPath: String): String {
        val script = buildReadSkillScript(directoryPath)
        val result = termuxCommandManager.run(
            TermuxRunCommandRequest(
                commandPath = TERMUX_BASH_PATH,
                arguments = listOf("-lc", script),
                workdir = settingsStore.settingsFlow.value.termuxWorkdir,
                background = false,
                timeoutMs = 30_000L,
                label = "RikkaHub read local skill",
            )
        )
        if (!result.isSuccessful()) {
            error(result.errMsg.orEmpty().ifBlank { result.stderr.orEmpty().ifBlank { "Failed to read SKILL.md" } })
        }
        return result.stdout
    }

    private fun buildListScript(rootPath: String): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            if [ ! -d "${'$'}ROOT" ]; then
              exit 0
            fi
            find "${'$'}ROOT" -mindepth 1 -maxdepth 1 -type d | sort | while IFS= read -r dir; do
              [ -n "${'$'}dir" ] || continue
              name="${'$'}(basename "${'$'}dir")"
              if [ -f "${'$'}dir/SKILL.md" ]; then
                printf '%s\t%s\t1\n' "${'$'}name" "${'$'}dir"
              else
                printf '%s\t%s\t0\n' "${'$'}name" "${'$'}dir"
              fi
            done
        """.trimIndent()
    }

    private fun buildReadSkillScript(directoryPath: String): String {
        val safeDirectory = directoryPath.escapeForSingleQuotedShell()
        return """
            set -eu
            DIR='$safeDirectory'
            cat "${'$'}DIR/SKILL.md"
        """.trimIndent()
    }

    companion object {
        fun buildSkillsRootPath(workdir: String): String {
            val normalized = workdir.trimEnd('/').ifBlank { "/data/data/com.termux/files/home" }
            return "$normalized/skills"
        }
    }
}

internal fun parseSkillFrontmatter(markdown: String): SkillFrontmatterParseResult {
    val normalized = markdown.trimStart()
    if (!normalized.startsWith("---")) {
        return SkillFrontmatterParseResult.Error("SKILL.md is missing YAML frontmatter")
    }

    val lines = normalized.lineSequence().toList()
    if (lines.isEmpty() || lines.first().trim() != "---") {
        return SkillFrontmatterParseResult.Error("SKILL.md frontmatter must start with ---")
    }

    val endIndex = lines.indexOfFirst { indexLine ->
        indexLine.trim() == "---"
    }.let { firstEnd ->
        if (firstEnd <= 0) {
            lines.drop(1).indexOfFirst { it.trim() == "---" }.let { relative ->
                if (relative >= 0) relative + 1 else -1
            }
        } else {
            firstEnd
        }
    }

    if (endIndex <= 0) {
        return SkillFrontmatterParseResult.Error("SKILL.md frontmatter is not closed")
    }

    val values = linkedMapOf<String, String>()
    lines.subList(1, endIndex).forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#")) return@forEach
        val separator = line.indexOf(':')
        if (separator <= 0) return@forEach
        val key = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim().trimMatchingQuotes()
        if (key.isNotBlank()) {
            values[key] = value
        }
    }

    val name = values["name"]?.takeIf { it.isNotBlank() }
        ?: return SkillFrontmatterParseResult.Error("SKILL.md frontmatter is missing name")
    val description = values["description"]?.takeIf { it.isNotBlank() }
        ?: return SkillFrontmatterParseResult.Error("SKILL.md frontmatter is missing description")

    return SkillFrontmatterParseResult.Success(
        SkillFrontmatter(
            name = name,
            description = description,
        )
    )
}

private fun String.escapeForSingleQuotedShell(): String = replace("'", "'\"'\"'")

private fun String.trimMatchingQuotes(): String {
    if (length >= 2 && first() == last() && (first() == '"' || first() == '\'')) {
        return substring(1, lastIndex)
    }
    return this
}

internal fun SkillsCatalogState.toRefreshingCatalogState(
    workdir: String,
    rootPath: String,
): SkillsCatalogState {
    return copy(
        workdir = workdir,
        rootPath = rootPath,
        entries = emptyList(),
        invalidEntries = emptyList(),
        isLoading = true,
        error = null,
    )
}

internal suspend fun discoverCatalogEntries(
    directories: List<SkillDirectoryDescriptor>,
    readSkillFile: suspend (String) -> String,
): SkillCatalogDiscoveryResult {
    val validEntries = arrayListOf<SkillCatalogEntry>()
    val invalidEntries = arrayListOf<SkillInvalidEntry>()

    directories.forEach { directory ->
        when (
            val result = inspectSkillDirectory(
                directoryName = directory.directoryName,
                path = directory.path,
                hasSkillFile = directory.hasSkillFile,
                readSkillFile = readSkillFile,
            )
        ) {
            is SkillDirectoryInspectionResult.Valid -> validEntries += result.entry
            is SkillDirectoryInspectionResult.Invalid -> invalidEntries += result.entry
        }
    }

    return SkillCatalogDiscoveryResult(
        entries = validEntries,
        invalidEntries = invalidEntries,
    )
}

internal suspend fun inspectSkillDirectory(
    directoryName: String,
    path: String,
    hasSkillFile: Boolean,
    readSkillFile: suspend (String) -> String,
): SkillDirectoryInspectionResult {
    if (!hasSkillFile) {
        return SkillDirectoryInspectionResult.Invalid(
            SkillInvalidEntry(
                directoryName = directoryName,
                path = path,
                reason = "Missing SKILL.md",
            )
        )
    }

    val markdown = runCatching { readSkillFile(path) }.getOrElse { error ->
        return SkillDirectoryInspectionResult.Invalid(
            SkillInvalidEntry(
                directoryName = directoryName,
                path = path,
                reason = buildSkillReadFailureReason(error),
            )
        )
    }

    return when (val parsed = parseSkillFrontmatter(markdown)) {
        is SkillFrontmatterParseResult.Success -> {
            SkillDirectoryInspectionResult.Valid(
                SkillCatalogEntry(
                    directoryName = directoryName,
                    path = path,
                    name = parsed.frontmatter.name,
                    description = parsed.frontmatter.description,
                )
            )
        }

        is SkillFrontmatterParseResult.Error -> {
            SkillDirectoryInspectionResult.Invalid(
                SkillInvalidEntry(
                    directoryName = directoryName,
                    path = path,
                    reason = parsed.reason,
                )
            )
        }
    }
}

internal fun buildSkillReadFailureReason(error: Throwable): String {
    return "Failed to read SKILL.md: ${error.message ?: error.javaClass.name}"
}
