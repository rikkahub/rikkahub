package me.rerere.rikkahub.data.skills

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.termux.TermuxCommandManager
import me.rerere.rikkahub.data.ai.tools.termux.TermuxRunCommandRequest
import me.rerere.rikkahub.data.ai.tools.termux.isSuccessful
import me.rerere.rikkahub.data.datastore.SettingsStore

private const val TAG = "SkillsRepository"
private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
private const val SKILL_COMMAND_TIMEOUT_MS = 30_000L
private const val SKILL_WRITE_TIMEOUT_MS = 60_000L
private const val SKILL_SINGLE_WRITE_BYTES_LIMIT = 192 * 1024
private const val SKILL_CHUNK_WRITE_BYTES = 48 * 1024
private const val DEFAULT_IMPORTED_SKILL_DIRECTORY = "skill-import"
private const val DEFAULT_CREATED_SKILL_DIRECTORY = "new-skill"
private const val SKILL_PACKAGE_FILE_NAME = "SKILL.md"
private const val BUNDLED_SKILLS_ASSET_ROOT = "builtin_skills"
private const val SKILL_LIST_PREVIEW_BYTES_LIMIT = 8 * 1024
private const val WORKDIR_TEXT_EDIT_MAX_BYTES = 512 * 1024L

data class SkillCatalogEntry(
    val directoryName: String,
    val path: String,
    val name: String,
    val description: String,
    val isBundled: Boolean = false,
)

data class SkillCreationResult(
    val directoryName: String,
    val path: String,
)

data class SkillEditorDocument(
    val originalDirectoryName: String,
    val directoryName: String,
    val name: String,
    val description: String,
    val body: String,
)

data class SkillImportResult(
    val directories: List<String>,
    val importedFiles: Int,
)

data class SkillInvalidEntry(
    val directoryName: String,
    val path: String,
    val reason: SkillInvalidReason,
)

data class WorkdirBrowserEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAtEpochSeconds: Long,
)

data class WorkdirBrowserListing(
    val rootPath: String,
    val currentRelativePath: String,
    val currentPath: String,
    val entries: List<WorkdirBrowserEntry>,
)

data class WorkdirTextFileDocument(
    val name: String,
    val relativePath: String,
    val currentPath: String,
    val sizeBytes: Long,
    val content: String,
)

sealed interface SkillInvalidReason {
    data object MissingSkillFile : SkillInvalidReason
    data object MissingYamlFrontmatter : SkillInvalidReason
    data object FrontmatterMustStart : SkillInvalidReason
    data object FrontmatterNotClosed : SkillInvalidReason
    data object MissingName : SkillInvalidReason
    data object MissingDescription : SkillInvalidReason
    data class FailedToRead(val detail: String) : SkillInvalidReason
    data class Other(val message: String) : SkillInvalidReason
}

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
    data class Error(val reason: SkillInvalidReason) : SkillFrontmatterParseResult
}

internal data class SkillDirectoryDescriptor(
    val directoryName: String,
    val path: String,
    val hasSkillFile: Boolean,
    val skillMarkdownPreview: String? = null,
)

internal data class SkillCatalogDiscoveryResult(
    val entries: List<SkillCatalogEntry>,
    val invalidEntries: List<SkillInvalidEntry>,
)

internal sealed interface SkillDirectoryInspectionResult {
    data class Valid(val entry: SkillCatalogEntry) : SkillDirectoryInspectionResult
    data class Invalid(val entry: SkillInvalidEntry) : SkillDirectoryInspectionResult
}

internal data class SkillArchiveFile(
    val path: String,
    val bytes: ByteArray,
)

internal data class ParsedSkillArchive(
    val directories: Set<String>,
    val files: List<SkillArchiveFile>,
)

internal data class SkillImportPlan(
    val topLevelDirectories: List<String>,
    val directories: Set<String>,
    val files: List<SkillArchiveFile>,
)

internal data class BundledSkill(
    val directoryName: String,
    val assetPath: String,
)

internal data class SkillMarkdownDocument(
    val frontmatter: SkillFrontmatter,
    val body: String,
)

class SkillsRepository(
    private val context: Context,
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
                .filter { !it.init }
                .map { it.termuxWorkdir }
                .distinctUntilChanged()
                .collect { workdir ->
                    refresh(workdir)
                }
        }
    }

    fun requestRefresh() {
        requestRefresh(force = false)
    }

    fun requestRefresh(force: Boolean) {
        val settings = settingsStore.settingsFlow.value
        if (settings.init) return
        if (!force && _state.value.isLoading && _state.value.workdir == settings.termuxWorkdir) return
        appScope.launch {
            refresh(settings.termuxWorkdir)
        }
    }

    suspend fun refresh(workdir: String = settingsStore.settingsFlow.value.termuxWorkdir) {
        withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                refreshLocked(workdir)
            }
        }
    }

    suspend fun createSkill(
        directoryName: String,
        name: String,
        description: String,
        body: String,
    ): SkillCreationResult {
        require(name.isNotBlank()) { "Skill name cannot be empty" }
        require(description.isNotBlank()) { "Skill description cannot be empty" }

        return runCatalogMutation { workdir, rootPath ->
            ensureSkillsRootDirectory(rootPath = rootPath, workdir = workdir)
            val existingDirectoryNames = listSkillDirectories(rootPath, workdir)
                .mapTo(linkedSetOf()) { it.directoryName }
            val desiredDirectoryName = sanitizeSkillDirectoryName(
                input = directoryName.ifBlank { name },
                fallback = DEFAULT_CREATED_SKILL_DIRECTORY,
            )
            val finalDirectoryName = resolveUniqueDirectoryNames(
                desired = listOf(desiredDirectoryName),
                existing = existingDirectoryNames,
            ).getValue(desiredDirectoryName)

            val markdown = buildSkillMarkdown(
                name = name.trim(),
                description = description.trim(),
                body = body,
            )
            val script = buildCreateSkillScript(
                rootPath = rootPath,
                directoryName = finalDirectoryName,
            )
            runSkillScript(
                script = script,
                workdir = workdir,
                label = "RikkaHub create local skill",
                stdin = markdown,
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )

            SkillCreationResult(
                directoryName = finalDirectoryName,
                path = "$rootPath/$finalDirectoryName",
            )
        }
    }

    suspend fun loadSkillDocument(entry: SkillCatalogEntry): SkillEditorDocument {
        return withContext(Dispatchers.IO) {
            val settings = settingsStore.settingsFlow.value
            require(!settings.init) { "Settings are not ready" }

            val markdown = readSkillFile(
                directoryPath = entry.path,
                workdir = settings.termuxWorkdir,
            )
            val document = parseSkillMarkdownDocument(markdown)

            SkillEditorDocument(
                originalDirectoryName = entry.directoryName,
                directoryName = entry.directoryName,
                name = document.frontmatter.name,
                description = document.frontmatter.description,
                body = document.body,
            )
        }
    }

    suspend fun updateSkill(
        originalDirectoryName: String,
        directoryName: String,
        name: String,
        description: String,
        body: String,
    ): SkillCreationResult {
        require(originalDirectoryName.isNotBlank()) { "Original skill directory cannot be empty" }
        require(name.isNotBlank()) { "Skill name cannot be empty" }
        require(description.isNotBlank()) { "Skill description cannot be empty" }

        return runCatalogMutation { workdir, rootPath ->
            ensureSkillsRootDirectory(rootPath = rootPath, workdir = workdir)
            val existingDirectoryNames = listSkillDirectories(rootPath, workdir)
                .mapTo(linkedSetOf()) { it.directoryName }

            val finalDirectoryName = sanitizeSkillDirectoryName(
                input = directoryName.ifBlank { name },
                fallback = originalDirectoryName,
            )
            val conflictingDirectories = existingDirectoryNames - originalDirectoryName
            require(finalDirectoryName !in conflictingDirectories) {
                "Skill directory already exists: $finalDirectoryName"
            }

            if (finalDirectoryName != originalDirectoryName) {
                runSkillScript(
                    script = buildMoveSkillDirectoryScript(
                        rootPath = rootPath,
                        fromDirectoryName = originalDirectoryName,
                        toDirectoryName = finalDirectoryName,
                    ),
                    workdir = workdir,
                    label = "RikkaHub rename local skill",
                    timeoutMs = SKILL_WRITE_TIMEOUT_MS,
                )
            }

            val markdown = buildSkillMarkdown(
                name = name.trim(),
                description = description.trim(),
                body = body,
            )
            runSkillScript(
                script = buildCreateSkillScript(
                    rootPath = rootPath,
                    directoryName = finalDirectoryName,
                ),
                workdir = workdir,
                label = "RikkaHub update local skill",
                stdin = markdown,
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )

            SkillCreationResult(
                directoryName = finalDirectoryName,
                path = "$rootPath/$finalDirectoryName",
            )
        }
    }

    suspend fun importSkillZip(
        inputStream: InputStream,
        archiveName: String? = null,
    ): SkillImportResult {
        return runCatalogMutation { workdir, rootPath ->
            ensureSkillsRootDirectory(rootPath = rootPath, workdir = workdir)
            val existingDirectoryNames = listSkillDirectories(rootPath, workdir)
                .mapTo(linkedSetOf()) { it.directoryName }
            val archive = parseSkillArchive(inputStream)
            val importPlan = buildSkillImportPlan(
                archive = archive,
                suggestedDirectoryName = archiveName
                    ?.substringBeforeLast('.', archiveName)
                    ?.let { sanitizeSkillDirectoryName(it, DEFAULT_IMPORTED_SKILL_DIRECTORY) },
                existingDirectoryNames = existingDirectoryNames,
            )

            importPlan.directories
                .sortedWith(compareBy<String> { it.count { char -> char == '/' } }.thenBy { it })
                .forEach { relativeDirectory ->
                    createSkillDirectory(
                        rootPath = rootPath,
                        workdir = workdir,
                        relativeDirectory = relativeDirectory,
                    )
                }

            importPlan.files.forEach { file ->
                writeSkillFile(
                    rootPath = rootPath,
                    workdir = workdir,
                    relativePath = file.path,
                    bytes = file.bytes,
                )
            }

            SkillImportResult(
                directories = importPlan.topLevelDirectories,
                importedFiles = importPlan.files.size,
            )
        }
    }

    suspend fun deleteSkill(directoryName: String) {
        require(directoryName.isNotBlank()) { "Skill directory cannot be empty" }
        require(!isBundledSkillDirectoryName(directoryName)) {
            "Built-in skills cannot be deleted: $directoryName"
        }

        runCatalogMutation { workdir, rootPath ->
            runSkillScript(
                script = buildDeleteSkillScript(
                    rootPath = rootPath,
                    directoryName = directoryName,
                ),
                workdir = workdir,
                label = "RikkaHub delete local skill",
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )
        }
    }

    suspend fun browseWorkdir(relativePath: String = ""): WorkdirBrowserListing {
        return withContext(Dispatchers.IO) {
            val settings = settingsStore.settingsFlow.value
            require(!settings.init) { "Settings are not ready" }

            val workdir = normalizeWorkdirPath(settings.termuxWorkdir)
            val normalizedRelativePath = normalizeWorkdirRelativePath(relativePath)
            val currentPath = if (normalizedRelativePath.isBlank()) {
                workdir
            } else {
                "$workdir/$normalizedRelativePath"
            }

            val result = runSkillScript(
                script = buildBrowseWorkdirScript(
                    rootPath = workdir,
                    relativePath = normalizedRelativePath,
                ),
                workdir = workdir,
                label = "RikkaHub browse Termux workdir",
            )

            val entries = result.stdout
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split('\t', limit = 5)
                    if (parts.size < 5) return@mapNotNull null
                    WorkdirBrowserEntry(
                        name = decodeBase64Utf8(parts[1]),
                        relativePath = decodeBase64Utf8(parts[2]),
                        isDirectory = parts[0] == "d",
                        sizeBytes = parts[3].trim().toLongOrNull() ?: 0L,
                        modifiedAtEpochSeconds = parts[4].trim().toLongOrNull() ?: 0L,
                    )
                }
                .sortedWith(
                    compareBy<WorkdirBrowserEntry> { !it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
                .toList()

            WorkdirBrowserListing(
                rootPath = workdir,
                currentRelativePath = normalizedRelativePath,
                currentPath = currentPath,
                entries = entries,
            )
        }
    }

    suspend fun readWorkdirTextFile(relativePath: String): WorkdirTextFileDocument {
        return withContext(Dispatchers.IO) {
            val settings = settingsStore.settingsFlow.value
            require(!settings.init) { "Settings are not ready" }

            val workdir = normalizeWorkdirPath(settings.termuxWorkdir)
            val normalizedRelativePath = normalizeWorkdirRelativePath(relativePath)
            require(normalizedRelativePath.isNotBlank()) { "File path cannot be empty" }

            val result = runSkillScript(
                script = buildReadWorkdirTextFileScript(
                    rootPath = workdir,
                    relativePath = normalizedRelativePath,
                ),
                workdir = workdir,
                label = "RikkaHub read workdir text file",
            )

            val parts = result.stdout.split('\t', limit = 3)
            require(parts.size == 3) { "Failed to parse file content" }
            val sizeBytes = parts[0].trim().toLongOrNull() ?: 0L
            val name = decodeBase64Utf8(parts[1])
            val content = decodeBase64Utf8(parts[2])

            WorkdirTextFileDocument(
                name = name,
                relativePath = normalizedRelativePath,
                currentPath = "$workdir/$normalizedRelativePath",
                sizeBytes = sizeBytes,
                content = content,
            )
        }
    }

    suspend fun writeWorkdirTextFile(
        relativePath: String,
        content: String,
    ) {
        runWorkdirMutation { workdir ->
            val normalizedRelativePath = normalizeWorkdirRelativePath(relativePath)
            require(normalizedRelativePath.isNotBlank()) { "File path cannot be empty" }
            writeSkillFile(
                rootPath = workdir,
                workdir = workdir,
                relativePath = normalizedRelativePath,
                bytes = content.toByteArray(Charsets.UTF_8),
            )
        }
    }

    suspend fun createWorkdirDirectory(
        parentRelativePath: String,
        name: String,
    ): String {
        val childName = validateWorkdirEntryName(name)
        return runWorkdirMutation { workdir ->
            val parentPath = normalizeWorkdirRelativePath(parentRelativePath)
            val relativePath = appendWorkdirChild(parentPath, childName)
            runSkillScript(
                script = buildCreateWorkdirEntryScript(
                    rootPath = workdir,
                    relativePath = relativePath,
                    isDirectory = true,
                ),
                workdir = workdir,
                label = "RikkaHub create workdir directory",
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )
            relativePath
        }
    }

    suspend fun createWorkdirTextFile(
        parentRelativePath: String,
        name: String,
        content: String,
    ): String {
        val childName = validateWorkdirEntryName(name)
        return runWorkdirMutation { workdir ->
            val parentPath = normalizeWorkdirRelativePath(parentRelativePath)
            val relativePath = appendWorkdirChild(parentPath, childName)
            runSkillScript(
                script = buildCreateWorkdirEntryScript(
                    rootPath = workdir,
                    relativePath = relativePath,
                    isDirectory = false,
                ),
                workdir = workdir,
                label = "RikkaHub create workdir file",
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )
            writeSkillFile(
                rootPath = workdir,
                workdir = workdir,
                relativePath = relativePath,
                bytes = content.toByteArray(Charsets.UTF_8),
            )
            relativePath
        }
    }

    suspend fun renameWorkdirEntry(
        relativePath: String,
        newName: String,
    ): String {
        val normalizedRelativePath = normalizeWorkdirRelativePath(relativePath)
        require(normalizedRelativePath.isNotBlank()) { "Cannot rename workdir root" }
        val validatedName = validateWorkdirEntryName(newName)
        val renamedRelativePath = replaceWorkdirLeafName(normalizedRelativePath, validatedName)

        return runWorkdirMutation { workdir ->
            runSkillScript(
                script = buildRenameWorkdirEntryScript(
                    rootPath = workdir,
                    fromRelativePath = normalizedRelativePath,
                    toRelativePath = renamedRelativePath,
                ),
                workdir = workdir,
                label = "RikkaHub rename workdir entry",
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )
            renamedRelativePath
        }
    }

    suspend fun deleteWorkdirEntry(relativePath: String) {
        val normalizedRelativePath = normalizeWorkdirRelativePath(relativePath)
        require(normalizedRelativePath.isNotBlank()) { "Cannot delete workdir root" }

        runWorkdirMutation { workdir ->
            runSkillScript(
                script = buildDeleteWorkdirEntryScript(
                    rootPath = workdir,
                    relativePath = normalizedRelativePath,
                ),
                workdir = workdir,
                label = "RikkaHub delete workdir entry",
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )
        }
    }

    private suspend fun refreshLocked(workdir: String) {
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

    private suspend fun <T> runCatalogMutation(
        mutation: suspend (workdir: String, rootPath: String) -> T,
    ): T {
        return withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                val settings = settingsStore.settingsFlow.value
                require(!settings.init) { "Settings are not ready" }
                val workdir = settings.termuxWorkdir
                val rootPath = buildSkillsRootPath(workdir)
                _state.value = _state.value.toMutatingCatalogState(
                    workdir = workdir,
                    rootPath = rootPath,
                )

                runCatching {
                    ensureSkillsRootDirectory(rootPath = rootPath, workdir = workdir)
                    val existingDirectoryNames = listSkillDirectories(rootPath, workdir)
                        .mapTo(linkedSetOf()) { it.directoryName }
                    ensureBundledSkillsInstalled(
                        rootPath = rootPath,
                        workdir = workdir,
                        existingDirectoryNames = existingDirectoryNames,
                    )
                    val result = mutation(workdir, rootPath)
                    _state.value = discover(workdir = workdir, rootPath = rootPath)
                    result
                }.getOrElse { error ->
                    Log.w(TAG, "skills mutation failed for $rootPath", error)
                    _state.value = _state.value.copy(
                        workdir = workdir,
                        rootPath = rootPath,
                        isLoading = false,
                        error = error.message ?: error.javaClass.name,
                        refreshedAt = System.currentTimeMillis(),
                    )
                    throw error
                }
            }
        }
    }

    private suspend fun <T> runWorkdirMutation(
        mutation: suspend (workdir: String) -> T,
    ): T {
        return withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                val settings = settingsStore.settingsFlow.value
                require(!settings.init) { "Settings are not ready" }
                val workdir = normalizeWorkdirPath(settings.termuxWorkdir)
                val result = mutation(workdir)
                runCatching {
                    refreshLocked(workdir)
                }.onFailure { error ->
                    Log.w(TAG, "workdir mutation refresh failed for $workdir", error)
                }
                result
            }
        }
    }

    private suspend fun discover(
        workdir: String,
        rootPath: String,
    ): SkillsCatalogState {
        var listed = snapshotSkillDirectories(rootPath, workdir)
        val existingDirectoryNames = listed
            .mapTo(linkedSetOf()) { it.directoryName }
        if (
            ensureBundledSkillsInstalled(
                rootPath = rootPath,
                workdir = workdir,
                existingDirectoryNames = existingDirectoryNames,
            )
        ) {
            listed = snapshotSkillDirectories(rootPath, workdir)
        }
        val discovery = discoverCatalogEntries(
            directories = listed,
            readSkillFile = { directoryPath -> readSkillFile(directoryPath, workdir) },
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

    private suspend fun snapshotSkillDirectories(
        rootPath: String,
        workdir: String,
    ): List<SkillDirectoryDescriptor> {
        val result = runSkillScript(
            script = buildDiscoverScript(rootPath),
            workdir = workdir,
            label = "RikkaHub scan local skills",
        )
        return result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size < 3) return@mapNotNull null
                SkillDirectoryDescriptor(
                    directoryName = parts[0],
                    path = parts[1],
                    hasSkillFile = parts[2] == "1",
                    skillMarkdownPreview = parts.getOrNull(3)
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::decodeSkillMarkdownPreview),
                )
            }
            .toList()
    }

    private suspend fun listSkillDirectories(
        rootPath: String,
        workdir: String,
    ): List<SkillDirectoryDescriptor> {
        val script = buildListScript(rootPath)
        val result = runSkillScript(
            script = script,
            workdir = workdir,
            label = "RikkaHub list local skills",
        )
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

    private suspend fun readSkillFile(
        directoryPath: String,
        workdir: String,
    ): String {
        val script = buildReadSkillScript(directoryPath)
        val result = runSkillScript(
            script = script,
            workdir = workdir,
            label = "RikkaHub read local skill",
        )
        return result.stdout
    }

    private suspend fun ensureSkillsRootDirectory(
        rootPath: String,
        workdir: String,
    ) {
        runSkillScript(
            script = buildCreateDirectoryScript(rootPath = rootPath, relativePath = ""),
            workdir = workdir,
            label = "RikkaHub ensure skills root",
        )
    }

    private suspend fun ensureBundledSkillsInstalled(
        rootPath: String,
        workdir: String,
        existingDirectoryNames: MutableSet<String>,
    ): Boolean {
        var installedAny = false
        BUNDLED_SKILLS.forEach { bundledSkill ->
            if (bundledSkill.directoryName in existingDirectoryNames) return@forEach
            runCatching {
                installBundledSkill(
                    rootPath = rootPath,
                    workdir = workdir,
                    bundledSkill = bundledSkill,
                )
                existingDirectoryNames += bundledSkill.directoryName
                installedAny = true
            }.onFailure { error ->
                Log.w(TAG, "Failed to install bundled skill ${bundledSkill.directoryName}", error)
            }
        }
        return installedAny
    }

    private suspend fun installBundledSkill(
        rootPath: String,
        workdir: String,
        bundledSkill: BundledSkill,
    ) {
        val files = readBundledSkillFiles(
            context = context,
            assetPath = bundledSkill.assetPath,
        )
        require(files.any { it.path == SKILL_PACKAGE_FILE_NAME }) {
            "Bundled skill ${bundledSkill.directoryName} is missing $SKILL_PACKAGE_FILE_NAME"
        }
        files.sortedBy { it.path }.forEach { assetFile ->
            writeSkillFile(
                rootPath = rootPath,
                workdir = workdir,
                relativePath = "${bundledSkill.directoryName}/${assetFile.path}",
                bytes = assetFile.bytes,
            )
        }
    }

    private suspend fun createSkillDirectory(
        rootPath: String,
        workdir: String,
        relativeDirectory: String,
    ) {
        runSkillScript(
            script = buildCreateDirectoryScript(rootPath = rootPath, relativePath = relativeDirectory),
            workdir = workdir,
            label = "RikkaHub create local skill directory",
        )
    }

    private suspend fun writeSkillFile(
        rootPath: String,
        workdir: String,
        relativePath: String,
        bytes: ByteArray,
    ) {
        if (bytes.size <= SKILL_SINGLE_WRITE_BYTES_LIMIT) {
            runSkillScript(
                script = buildSingleFileWriteScript(rootPath = rootPath, relativePath = relativePath),
                workdir = workdir,
                label = "RikkaHub write local skill file",
                stdin = Base64.getEncoder().encodeToString(bytes),
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )
            return
        }

        val tempToken = UUID.randomUUID().toString()
        runSkillScript(
            script = buildBeginChunkedFileWriteScript(
                rootPath = rootPath,
                relativePath = relativePath,
                tempToken = tempToken,
            ),
            workdir = workdir,
            label = "RikkaHub begin local skill file upload",
            timeoutMs = SKILL_WRITE_TIMEOUT_MS,
        )

        bytes.asList()
            .chunked(SKILL_CHUNK_WRITE_BYTES)
            .forEach { chunk ->
                val chunkBytes = ByteArray(chunk.size)
                chunk.forEachIndexed { index, value ->
                    chunkBytes[index] = value
                }
                runSkillScript(
                    script = buildAppendChunkedFileWriteScript(
                        rootPath = rootPath,
                        relativePath = relativePath,
                        tempToken = tempToken,
                    ),
                    workdir = workdir,
                    label = "RikkaHub append local skill file upload",
                    stdin = Base64.getEncoder().encodeToString(chunkBytes),
                    timeoutMs = SKILL_WRITE_TIMEOUT_MS,
                )
            }

        runSkillScript(
            script = buildCommitChunkedFileWriteScript(
                rootPath = rootPath,
                relativePath = relativePath,
                tempToken = tempToken,
            ),
            workdir = workdir,
            label = "RikkaHub commit local skill file upload",
            timeoutMs = SKILL_WRITE_TIMEOUT_MS,
        )
    }

    private suspend fun runSkillScript(
        script: String,
        workdir: String,
        label: String,
        stdin: String? = null,
        timeoutMs: Long = SKILL_COMMAND_TIMEOUT_MS,
    ) = termuxCommandManager.run(
        buildSkillCommandRequest(
            script = script,
            workdir = workdir,
            label = label,
            stdin = stdin,
            timeoutMs = timeoutMs,
        )
    ).also { result ->
        if (!result.isSuccessful()) {
            error(result.errMsg.orEmpty().ifBlank {
                result.stderr.orEmpty().ifBlank { "Failed to run skill command" }
            })
        }
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
              if [ -f "${'$'}dir/$SKILL_PACKAGE_FILE_NAME" ]; then
                printf '%s\t%s\t1\n' "${'$'}name" "${'$'}dir"
              else
                printf '%s\t%s\t0\n' "${'$'}name" "${'$'}dir"
              fi
            done
        """.trimIndent()
    }

    private fun buildDiscoverScript(rootPath: String): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            PREVIEW_BYTES=$SKILL_LIST_PREVIEW_BYTES_LIMIT
            mkdir -p "${'$'}ROOT"
            find "${'$'}ROOT" -mindepth 1 -maxdepth 1 -type d | sort | while IFS= read -r dir; do
              [ -n "${'$'}dir" ] || continue
              name="${'$'}(basename "${'$'}dir")"
              skill_file="${'$'}dir/$SKILL_PACKAGE_FILE_NAME"
              if [ -f "${'$'}skill_file" ]; then
                printf '%s\t%s\t1\t' "${'$'}name" "${'$'}dir"
                head -c "${'$'}PREVIEW_BYTES" "${'$'}skill_file" | base64 | tr -d '\n'
                printf '\n'
              else
                printf '%s\t%s\t0\t\n' "${'$'}name" "${'$'}dir"
              fi
            done
        """.trimIndent()
    }

    private fun buildReadSkillScript(directoryPath: String): String {
        val safeDirectory = directoryPath.escapeForSingleQuotedShell()
        return """
            set -eu
            DIR='$safeDirectory'
            cat "${'$'}DIR/$SKILL_PACKAGE_FILE_NAME"
        """.trimIndent()
    }

    private fun buildBrowseWorkdirScript(
        rootPath: String,
        relativePath: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeRelativePath = relativePath.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            REL='$safeRelativePath'
            if [ ! -d "${'$'}ROOT" ]; then
              echo "Workdir does not exist: ${'$'}ROOT" >&2
              exit 1
            fi
            ROOT_REAL="${'$'}(cd "${'$'}ROOT" && pwd -P)"
            TARGET="${'$'}ROOT_REAL"
            if [ -n "${'$'}REL" ]; then
              TARGET="${'$'}ROOT_REAL/${'$'}REL"
            fi
            if [ ! -d "${'$'}TARGET" ]; then
              echo "Directory does not exist: ${'$'}TARGET" >&2
              exit 1
            fi
            TARGET_REAL="${'$'}(cd "${'$'}TARGET" && pwd -P)"
            case "${'$'}TARGET_REAL" in
              "${'$'}ROOT_REAL"|"${'$'}ROOT_REAL"/*) ;;
              *)
                echo "Requested path escapes workdir" >&2
                exit 1
                ;;
            esac
            find "${'$'}TARGET_REAL" -mindepth 1 -maxdepth 1 -print0 | while IFS= read -r -d '' child; do
              [ -n "${'$'}child" ] || continue
              name="${'$'}(basename "${'$'}child")"
              rel="${'$'}{child#${'$'}ROOT_REAL/}"
              modified="${'$'}(stat -c %Y "${'$'}child" 2>/dev/null || echo 0)"
              if [ -d "${'$'}child" ]; then
                kind="d"
                size="0"
              else
                kind="f"
                size="${'$'}(stat -c %s "${'$'}child" 2>/dev/null || wc -c < "${'$'}child")"
              fi
              printf '%s\t%s\t%s\t%s\t%s\n' \
                "${'$'}kind" \
                "${'$'}(printf '%s' "${'$'}name" | base64 | tr -d '\n')" \
                "${'$'}(printf '%s' "${'$'}rel" | base64 | tr -d '\n')" \
                "${'$'}size" \
                "${'$'}modified"
            done
        """.trimIndent()
    }

    private fun buildReadWorkdirTextFileScript(
        rootPath: String,
        relativePath: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeRelativePath = relativePath.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            REL='$safeRelativePath'
            FILE="${'$'}ROOT/${'$'}REL"
            [ -f "${'$'}FILE" ] || {
              echo "File does not exist: ${'$'}FILE" >&2
              exit 1
            }
            ROOT_REAL="${'$'}(cd "${'$'}ROOT" && pwd -P)"
            FILE_REAL="${'$'}(cd "$(dirname "${'$'}FILE")" && pwd -P)/$(basename "${'$'}FILE")"
            case "${'$'}FILE_REAL" in
              "${'$'}ROOT_REAL"/*) ;;
              *)
                echo "Requested file escapes workdir" >&2
                exit 1
                ;;
            esac
            SIZE="${'$'}(stat -c %s "${'$'}FILE_REAL" 2>/dev/null || wc -c < "${'$'}FILE_REAL")"
            if [ "${'$'}SIZE" -gt $WORKDIR_TEXT_EDIT_MAX_BYTES ]; then
              echo "File is too large to edit in-app (${WORKDIR_TEXT_EDIT_MAX_BYTES} bytes max)" >&2
              exit 1
            fi
            if [ -s "${'$'}FILE_REAL" ] && ! grep -Iq . "${'$'}FILE_REAL"; then
              echo "Binary files are not supported for in-app editing" >&2
              exit 1
            fi
            printf '%s\t%s\t%s' \
              "${'$'}SIZE" \
              "${'$'}(basename "${'$'}FILE_REAL" | base64 | tr -d '\n')" \
              "${'$'}(base64 < "${'$'}FILE_REAL" | tr -d '\n')"
        """.trimIndent()
    }

    private fun buildCreateWorkdirEntryScript(
        rootPath: String,
        relativePath: String,
        isDirectory: Boolean,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeRelativePath = relativePath.escapeForSingleQuotedShell()
        val kind = if (isDirectory) "dir" else "file"
        return """
            set -eu
            ROOT='$safeRoot'
            REL='$safeRelativePath'
            KIND='$kind'
            [ -n "${'$'}REL" ] || {
              echo "Entry path cannot be empty" >&2
              exit 1
            }
            mkdir -p "${'$'}ROOT"
            ROOT_REAL="${'$'}(cd "${'$'}ROOT" && pwd -P)"
            TARGET="${'$'}ROOT_REAL/${'$'}REL"
            [ ! -e "${'$'}TARGET" ] || {
              echo "Entry already exists: ${'$'}REL" >&2
              exit 1
            }
            if [ "${'$'}KIND" = "dir" ]; then
              mkdir -p "${'$'}TARGET"
            else
              mkdir -p "$(dirname "${'$'}TARGET")"
              : > "${'$'}TARGET"
            fi
        """.trimIndent()
    }

    private fun buildRenameWorkdirEntryScript(
        rootPath: String,
        fromRelativePath: String,
        toRelativePath: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeFrom = fromRelativePath.escapeForSingleQuotedShell()
        val safeTo = toRelativePath.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            FROM_REL='$safeFrom'
            TO_REL='$safeTo'
            [ -n "${'$'}FROM_REL" ] || {
              echo "Source path cannot be empty" >&2
              exit 1
            }
            [ -n "${'$'}TO_REL" ] || {
              echo "Target path cannot be empty" >&2
              exit 1
            }
            ROOT_REAL="${'$'}(cd "${'$'}ROOT" && pwd -P)"
            FROM_PATH="${'$'}ROOT_REAL/${'$'}FROM_REL"
            TO_PATH="${'$'}ROOT_REAL/${'$'}TO_REL"
            [ -e "${'$'}FROM_PATH" ] || {
              echo "Entry does not exist: ${'$'}FROM_REL" >&2
              exit 1
            }
            [ ! -e "${'$'}TO_PATH" ] || {
              echo "Entry already exists: ${'$'}TO_REL" >&2
              exit 1
            }
            mkdir -p "$(dirname "${'$'}TO_PATH")"
            mv "${'$'}FROM_PATH" "${'$'}TO_PATH"
        """.trimIndent()
    }

    private fun buildDeleteWorkdirEntryScript(
        rootPath: String,
        relativePath: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeRelativePath = relativePath.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            REL='$safeRelativePath'
            [ -n "${'$'}REL" ] || {
              echo "Entry path cannot be empty" >&2
              exit 1
            }
            ROOT_REAL="${'$'}(cd "${'$'}ROOT" && pwd -P)"
            TARGET="${'$'}ROOT_REAL/${'$'}REL"
            if [ ! -e "${'$'}TARGET" ]; then
              exit 0
            fi
            rm -rf "${'$'}TARGET"
        """.trimIndent()
    }

    private fun buildCreateSkillScript(
        rootPath: String,
        directoryName: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeDirectoryName = directoryName.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            DIR_NAME='$safeDirectoryName'
            DIR="${'$'}ROOT/${'$'}DIR_NAME"
            mkdir -p "${'$'}DIR" "${'$'}DIR/scripts" "${'$'}DIR/assets" "${'$'}DIR/references"
            cat > "${'$'}DIR/$SKILL_PACKAGE_FILE_NAME"
        """.trimIndent()
    }

    private fun buildCreateDirectoryScript(
        rootPath: String,
        relativePath: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeRelativePath = relativePath.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            REL='$safeRelativePath'
            if [ -n "${'$'}REL" ]; then
              mkdir -p "${'$'}ROOT/${'$'}REL"
            else
              mkdir -p "${'$'}ROOT"
            fi
        """.trimIndent()
    }

    private fun buildMoveSkillDirectoryScript(
        rootPath: String,
        fromDirectoryName: String,
        toDirectoryName: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeFrom = fromDirectoryName.escapeForSingleQuotedShell()
        val safeTo = toDirectoryName.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            FROM='$safeFrom'
            TO='$safeTo'
            [ -d "${'$'}ROOT/${'$'}FROM" ] || exit 1
            [ ! -e "${'$'}ROOT/${'$'}TO" ] || exit 1
            mv "${'$'}ROOT/${'$'}FROM" "${'$'}ROOT/${'$'}TO"
        """.trimIndent()
    }

    private fun buildDeleteSkillScript(
        rootPath: String,
        directoryName: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeDirectoryName = directoryName.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            DIR_NAME='$safeDirectoryName'
            TARGET="${'$'}ROOT/${'$'}DIR_NAME"
            [ -d "${'$'}TARGET" ] || exit 0
            rm -rf "${'$'}TARGET"
        """.trimIndent()
    }

    private fun buildSingleFileWriteScript(
        rootPath: String,
        relativePath: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeRelativePath = relativePath.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            REL='$safeRelativePath'
            TMP_DIR="${'$'}ROOT/.rikkahub_tmp"
            TARGET="${'$'}ROOT/${'$'}REL"
            mkdir -p "${'$'}TMP_DIR" "$(dirname "${'$'}TARGET")"
            TMP_FILE="${'$'}TMP_DIR/$(basename "${'$'}REL").${'$'}$.tmp"
            base64 -d > "${'$'}TMP_FILE"
            mv -f "${'$'}TMP_FILE" "${'$'}TARGET"
        """.trimIndent()
    }

    private fun buildBeginChunkedFileWriteScript(
        rootPath: String,
        relativePath: String,
        tempToken: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeRelativePath = relativePath.escapeForSingleQuotedShell()
        val safeTempToken = tempToken.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            REL='$safeRelativePath'
            TEMP_TOKEN='$safeTempToken'
            TMP_DIR="${'$'}ROOT/.rikkahub_tmp"
            TARGET="${'$'}ROOT/${'$'}REL"
            TMP_FILE="${'$'}TMP_DIR/${'$'}TEMP_TOKEN"
            mkdir -p "${'$'}TMP_DIR" "$(dirname "${'$'}TARGET")"
            : > "${'$'}TMP_FILE"
        """.trimIndent()
    }

    private fun buildAppendChunkedFileWriteScript(
        rootPath: String,
        relativePath: String,
        tempToken: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeRelativePath = relativePath.escapeForSingleQuotedShell()
        val safeTempToken = tempToken.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            REL='$safeRelativePath'
            TEMP_TOKEN='$safeTempToken'
            TMP_FILE="${'$'}ROOT/.rikkahub_tmp/${'$'}TEMP_TOKEN"
            [ -f "${'$'}TMP_FILE" ] || exit 1
            base64 -d >> "${'$'}TMP_FILE"
        """.trimIndent()
    }

    private fun buildCommitChunkedFileWriteScript(
        rootPath: String,
        relativePath: String,
        tempToken: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeRelativePath = relativePath.escapeForSingleQuotedShell()
        val safeTempToken = tempToken.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            REL='$safeRelativePath'
            TEMP_TOKEN='$safeTempToken'
            TARGET="${'$'}ROOT/${'$'}REL"
            TMP_FILE="${'$'}ROOT/.rikkahub_tmp/${'$'}TEMP_TOKEN"
            mkdir -p "$(dirname "${'$'}TARGET")"
            mv -f "${'$'}TMP_FILE" "${'$'}TARGET"
        """.trimIndent()
    }

    companion object {
        fun normalizeWorkdirPath(workdir: String): String {
            return workdir.trimEnd('/').ifBlank { "/data/data/com.termux/files/home" }
        }

        fun buildSkillsRootPath(workdir: String): String {
            val normalized = normalizeWorkdirPath(workdir)
            return "$normalized/skills"
        }
    }
}

private fun decodeBase64Utf8(encodedValue: String): String {
    return String(Base64.getDecoder().decode(encodedValue), Charsets.UTF_8)
}

private fun decodeSkillMarkdownPreview(encodedPreview: String): String {
    return decodeBase64Utf8(encodedPreview)
}

private val BUNDLED_SKILLS = listOf(
    BundledSkill(
        directoryName = "skill-creator",
        assetPath = "$BUNDLED_SKILLS_ASSET_ROOT/skill-creator",
    )
)

private val BUNDLED_SKILL_DIRECTORY_NAMES = BUNDLED_SKILLS
    .mapTo(linkedSetOf()) { it.directoryName }

internal fun isBundledSkillDirectoryName(directoryName: String): Boolean {
    return directoryName in BUNDLED_SKILL_DIRECTORY_NAMES
}

internal fun parseSkillFrontmatter(markdown: String): SkillFrontmatterParseResult {
    val normalized = markdown.trimStart()
    if (!normalized.startsWith("---")) {
        return SkillFrontmatterParseResult.Error(SkillInvalidReason.MissingYamlFrontmatter)
    }

    val lines = normalized.lineSequence().toList()
    if (lines.isEmpty() || lines.first().trim() != "---") {
        return SkillFrontmatterParseResult.Error(SkillInvalidReason.FrontmatterMustStart)
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
        return SkillFrontmatterParseResult.Error(SkillInvalidReason.FrontmatterNotClosed)
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
        ?: return SkillFrontmatterParseResult.Error(SkillInvalidReason.MissingName)
    val description = values["description"]?.takeIf { it.isNotBlank() }
        ?: return SkillFrontmatterParseResult.Error(SkillInvalidReason.MissingDescription)

    return SkillFrontmatterParseResult.Success(
        SkillFrontmatter(
            name = name,
            description = description,
        )
    )
}

internal fun sanitizeSkillDirectoryName(
    input: String,
    fallback: String = DEFAULT_CREATED_SKILL_DIRECTORY,
): String {
    val normalized = input
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .replace(Regex("-{2,}"), "-")
        .trim('-', '.', '_')
    return normalized.ifBlank { fallback }
}

internal fun normalizeWorkdirRelativePath(relativePath: String): String {
    val parts = relativePath
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() && it != "." }

    val normalized = mutableListOf<String>()
    parts.forEach { part ->
        if (part == "..") {
            if (normalized.isEmpty()) {
                error("Requested path escapes workdir")
            }
            normalized.removeAt(normalized.lastIndex)
        } else {
            normalized += part
        }
    }

    return normalized.joinToString("/")
}

internal fun validateWorkdirEntryName(name: String): String {
    val normalized = name.trim()
    require(normalized.isNotBlank()) { "Name cannot be empty" }
    require('/' !in normalized && '\\' !in normalized) { "Name cannot contain path separators" }
    require(normalized != "." && normalized != "..") { "Invalid file or folder name" }
    return normalized
}

internal fun appendWorkdirChild(
    parentRelativePath: String,
    childName: String,
): String {
    val validatedName = validateWorkdirEntryName(childName)
    val parent = normalizeWorkdirRelativePath(parentRelativePath)
    return if (parent.isBlank()) validatedName else "$parent/$validatedName"
}

internal fun replaceWorkdirLeafName(
    relativePath: String,
    newName: String,
): String {
    val normalizedRelativePath = normalizeWorkdirRelativePath(relativePath)
    require(normalizedRelativePath.isNotBlank()) { "Path cannot be empty" }
    val validatedName = validateWorkdirEntryName(newName)
    val parent = normalizedRelativePath.substringBeforeLast('/', "")
    return if (parent.isBlank()) validatedName else "$parent/$validatedName"
}

private fun String.escapeForSingleQuotedShell(): String = replace("'", "'\"'\"'")

private fun String.trimMatchingQuotes(): String {
    if (length >= 2 && first() == last()) {
        return when (first()) {
            '"' -> substring(1, lastIndex).unescapeDoubleQuotedYaml()
            '\'' -> substring(1, lastIndex).replace("''", "'")
            else -> this
        }
    }
    return this
}

private fun String.escapeForDoubleQuotedYaml(): String {
    return buildString(length + 8) {
        for (char in this@escapeForDoubleQuotedYaml) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

private fun String.unescapeDoubleQuotedYaml(): String {
    val result = StringBuilder(length)
    var index = 0
    while (index < length) {
        val current = this[index]
        if (current == '\\' && index + 1 < length) {
            when (val next = this[index + 1]) {
                '\\' -> result.append('\\')
                '"' -> result.append('"')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                else -> result.append(next)
            }
            index += 2
        } else {
            result.append(current)
            index += 1
        }
    }
    return result.toString()
}

internal fun SkillsCatalogState.toRefreshingCatalogState(
    workdir: String,
    rootPath: String,
): SkillsCatalogState {
    val shouldKeepCachedEntries = this.workdir == workdir && this.rootPath == rootPath
    return copy(
        workdir = workdir,
        rootPath = rootPath,
        entries = if (shouldKeepCachedEntries) entries else emptyList(),
        invalidEntries = if (shouldKeepCachedEntries) invalidEntries else emptyList(),
        isLoading = true,
        error = null,
    )
}

internal fun SkillsCatalogState.toMutatingCatalogState(
    workdir: String,
    rootPath: String,
): SkillsCatalogState {
    return copy(
        workdir = workdir,
        rootPath = rootPath,
        isLoading = true,
        error = null,
    )
}

internal fun buildSkillCommandRequest(
    script: String,
    workdir: String,
    label: String,
    stdin: String? = null,
    timeoutMs: Long = SKILL_COMMAND_TIMEOUT_MS,
): TermuxRunCommandRequest {
    return TermuxRunCommandRequest(
        commandPath = TERMUX_BASH_PATH,
        arguments = listOf("-lc", script),
        workdir = workdir,
        stdin = stdin,
        background = true,
        timeoutMs = timeoutMs,
        label = label,
    )
}

internal suspend fun discoverCatalogEntries(
    directories: List<SkillDirectoryDescriptor>,
    readSkillFile: suspend (String) -> String,
): SkillCatalogDiscoveryResult {
    val validEntries = arrayListOf<SkillCatalogEntry>()
    val invalidEntries = arrayListOf<SkillInvalidEntry>()

    directories.forEach { directory ->
        when (val result = inspectSkillDirectory(directory = directory, readSkillFile = readSkillFile)) {
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
    directory: SkillDirectoryDescriptor,
    readSkillFile: suspend (String) -> String,
): SkillDirectoryInspectionResult {
    if (!directory.hasSkillFile) {
        return SkillDirectoryInspectionResult.Invalid(
            SkillInvalidEntry(
                directoryName = directory.directoryName,
                path = directory.path,
                reason = SkillInvalidReason.MissingSkillFile,
            )
        )
    }

    val markdown = directory.skillMarkdownPreview ?: runCatching {
        readSkillFile(directory.path)
    }.getOrElse { error ->
        return SkillDirectoryInspectionResult.Invalid(
            SkillInvalidEntry(
                directoryName = directory.directoryName,
                path = directory.path,
                reason = buildSkillReadFailureReason(error),
            )
        )
    }

    return when (val parsed = parseSkillFrontmatter(markdown)) {
        is SkillFrontmatterParseResult.Success -> {
            SkillDirectoryInspectionResult.Valid(
                SkillCatalogEntry(
                    directoryName = directory.directoryName,
                    path = directory.path,
                    name = parsed.frontmatter.name,
                    description = parsed.frontmatter.description,
                    isBundled = isBundledSkillDirectoryName(directory.directoryName),
                )
            )
        }

        is SkillFrontmatterParseResult.Error -> {
            SkillDirectoryInspectionResult.Invalid(
                SkillInvalidEntry(
                    directoryName = directory.directoryName,
                    path = directory.path,
                    reason = parsed.reason,
                )
            )
        }
    }
}

internal fun buildSkillReadFailureReason(error: Throwable): SkillInvalidReason {
    val detail = error.message ?: error.javaClass.name
    return SkillInvalidReason.FailedToRead(detail)
}

internal fun parseSkillMarkdownDocument(markdown: String): SkillMarkdownDocument {
    val normalized = markdown.trimStart()
    val parsedFrontmatter = parseSkillFrontmatter(normalized)
    val frontmatter = when (parsedFrontmatter) {
        is SkillFrontmatterParseResult.Success -> parsedFrontmatter.frontmatter
        is SkillFrontmatterParseResult.Error -> error(localizedSkillParseError(parsedFrontmatter.reason))
    }

    val lines = normalized.lineSequence().toList()
    val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
    require(endIndex >= 0) { "SKILL.md frontmatter is not closed" }
    val body = lines.drop(endIndex + 2).joinToString("\n").trim()

    return SkillMarkdownDocument(
        frontmatter = frontmatter,
        body = body,
    )
}

internal fun buildSkillMarkdown(
    name: String,
    description: String,
    body: String,
): String {
    val resolvedBody = body.trim().ifBlank {
        """
        # Instructions

        Describe when this skill should be used, which files to inspect, and what steps to follow.
        """.trimIndent()
    }
    return buildString {
        appendLine("---")
        appendLine("name: \"${name.escapeForDoubleQuotedYaml()}\"")
        appendLine("description: \"${description.escapeForDoubleQuotedYaml()}\"")
        appendLine("---")
        appendLine()
        appendLine(resolvedBody)
        appendLine()
    }
}

private fun localizedSkillParseError(reason: SkillInvalidReason): String {
    return when (reason) {
        SkillInvalidReason.MissingSkillFile -> "Missing $SKILL_PACKAGE_FILE_NAME"
        SkillInvalidReason.MissingYamlFrontmatter -> "$SKILL_PACKAGE_FILE_NAME is missing YAML frontmatter"
        SkillInvalidReason.FrontmatterMustStart -> "$SKILL_PACKAGE_FILE_NAME frontmatter must start with ---"
        SkillInvalidReason.FrontmatterNotClosed -> "$SKILL_PACKAGE_FILE_NAME frontmatter is not closed"
        SkillInvalidReason.MissingName -> "$SKILL_PACKAGE_FILE_NAME frontmatter is missing name"
        SkillInvalidReason.MissingDescription -> "$SKILL_PACKAGE_FILE_NAME frontmatter is missing description"
        is SkillInvalidReason.FailedToRead -> reason.detail
        is SkillInvalidReason.Other -> reason.message
    }
}

internal fun parseSkillArchive(inputStream: InputStream): ParsedSkillArchive {
    val directories = linkedSetOf<String>()
    val files = arrayListOf<SkillArchiveFile>()

    ZipInputStream(BufferedInputStream(inputStream)).use { zipInputStream ->
        while (true) {
            val entry = zipInputStream.nextEntry ?: break
            val normalizedPath = normalizeSkillArchiveEntryPath(entry.name)
            val shouldIgnore = normalizedPath == null || isIgnoredSkillArchiveEntry(normalizedPath)
            if (shouldIgnore) {
                zipInputStream.closeEntry()
                continue
            }

            if (entry.isDirectory) {
                directories += normalizedPath
            } else {
                val bytes = zipInputStream.readBytes()
                files += SkillArchiveFile(
                    path = normalizedPath,
                    bytes = bytes,
                )
                collectParentDirectories(normalizedPath).forEach { directories += it }
            }
            zipInputStream.closeEntry()
        }
    }

    if (files.isEmpty() && directories.isEmpty()) {
        error("Zip archive is empty")
    }

    return collapseSkillArchiveContainerLayers(
        ParsedSkillArchive(
            directories = directories,
            files = files,
        )
    )
}

internal fun buildSkillImportPlan(
    archive: ParsedSkillArchive,
    suggestedDirectoryName: String?,
    existingDirectoryNames: Set<String> = emptySet(),
): SkillImportPlan {
    if (archive.files.isEmpty() && archive.directories.isEmpty()) {
        error("Zip archive is empty")
    }

    val hasRootLevelContent = archive.files.any { !it.path.contains('/') }
    val remappedFiles: List<SkillArchiveFile>
    val remappedDirectories: Set<String>
    val topLevelDirectories: List<String>

    if (hasRootLevelContent) {
        val desiredRootDirectory = deriveRootImportDirectoryName(
            archive = archive,
            suggestedDirectoryName = suggestedDirectoryName,
        )
        val resolvedRootDirectory = resolveUniqueDirectoryNames(
            desired = listOf(desiredRootDirectory),
            existing = existingDirectoryNames,
        ).getValue(desiredRootDirectory)
        remappedFiles = archive.files.map { file ->
            file.copy(path = "$resolvedRootDirectory/${file.path}")
        }
        remappedDirectories = buildSet {
            add(resolvedRootDirectory)
            archive.directories.forEach { directory ->
                add("$resolvedRootDirectory/$directory")
            }
        }
        topLevelDirectories = listOf(resolvedRootDirectory)
    } else {
        val desiredTopLevelDirectories = archive.topLevelDirectories()
        val mapping = resolveUniqueDirectoryNames(
            desired = desiredTopLevelDirectories,
            existing = existingDirectoryNames,
        )
        remappedFiles = archive.files.map { file ->
            file.copy(path = replaceTopLevelDirectory(file.path, mapping))
        }
        remappedDirectories = archive.directories.mapTo(linkedSetOf()) { directory ->
            replaceTopLevelDirectory(directory, mapping)
        }
        topLevelDirectories = desiredTopLevelDirectories.map { mapping.getValue(it) }
    }

    val allDirectories = linkedSetOf<String>()
    allDirectories += topLevelDirectories
    allDirectories += remappedDirectories
    remappedFiles.forEach { file ->
        collectParentDirectories(file.path).forEach { allDirectories += it }
    }

    val hasSkillFile = remappedFiles.any { file ->
        file.path.endsWith("/$SKILL_PACKAGE_FILE_NAME") && file.path.count { it == '/' } == 1
    }
    if (!hasSkillFile) {
        error("Zip package must contain $SKILL_PACKAGE_FILE_NAME at the root of a skill directory")
    }

    return SkillImportPlan(
        topLevelDirectories = topLevelDirectories.distinct(),
        directories = allDirectories,
        files = remappedFiles.sortedBy { it.path },
    )
}

internal fun resolveUniqueDirectoryNames(
    desired: List<String>,
    existing: Set<String>,
): Map<String, String> {
    val reserved = existing.toMutableSet()
    val resolved = linkedMapOf<String, String>()

    desired.distinct().forEach { original ->
        val baseName = original.ifBlank { DEFAULT_IMPORTED_SKILL_DIRECTORY }
        var candidate = baseName
        var suffix = 2
        while (candidate in reserved) {
            candidate = "$baseName-$suffix"
            suffix += 1
        }
        reserved += candidate
        resolved[original] = candidate
    }

    return resolved
}

internal fun collapseSkillArchiveContainerLayers(archive: ParsedSkillArchive): ParsedSkillArchive {
    var currentArchive = archive
    while (true) {
        if (currentArchive.files.isEmpty()) break
        if (currentArchive.files.any { !it.path.contains('/') }) break

        val topLevelDirectories = currentArchive.topLevelDirectories()
        if (topLevelDirectories.size != 1) break

        val container = topLevelDirectories.single()
        val containsTopLevelSkillFile = currentArchive.files.any { it.path == "$container/$SKILL_PACKAGE_FILE_NAME" }
        if (containsTopLevelSkillFile) break

        currentArchive = ParsedSkillArchive(
            directories = currentArchive.directories.mapNotNullTo(linkedSetOf()) { stripLeadingDirectory(it) },
            files = currentArchive.files.map { file ->
                file.copy(path = stripLeadingDirectory(file.path) ?: file.path)
            },
        )
    }
    return currentArchive
}

internal fun normalizeSkillArchiveEntryPath(path: String): String? {
    val slashNormalized = path.replace('\\', '/').trim()
    if (slashNormalized.startsWith('/')) error("Zip entry path must be relative: $path")
    val trimmed = slashNormalized
        .trim('/')
        .removePrefix("./")
    if (trimmed.isBlank()) return null
    if (Regex("^[A-Za-z]:").containsMatchIn(trimmed)) error("Zip entry path must be relative: $path")

    val segments = trimmed.split('/')
    if (segments.any { segment ->
            segment.isBlank() || segment == "." || segment == ".." || '\u0000' in segment
        }
    ) {
        error("Zip entry contains an invalid path: $path")
    }
    return segments.joinToString("/")
}

internal fun isIgnoredSkillArchiveEntry(path: String): Boolean {
    val segments = path.split('/')
    val fileName = segments.lastOrNull().orEmpty()
    return segments.any { it == "__MACOSX" } ||
        fileName == ".DS_Store" ||
        fileName == "Thumbs.db" ||
        fileName.startsWith("._")
}

internal fun collectParentDirectories(path: String): List<String> {
    val segments = path.split('/')
    if (segments.size <= 1) return emptyList()
    return buildList {
        for (index in 1 until segments.lastIndex) {
            add(segments.take(index).joinToString("/"))
        }
        add(segments.dropLast(1).joinToString("/"))
    }.distinct()
}

private fun ParsedSkillArchive.topLevelDirectories(): List<String> {
    return buildSet {
        files.forEach { add(it.path.substringBefore('/')) }
        directories.forEach { add(it.substringBefore('/')) }
    }.sorted()
}

private fun replaceTopLevelDirectory(
    path: String,
    mapping: Map<String, String>,
): String {
    val firstSegment = path.substringBefore('/')
    val remainder = path.substringAfter('/', missingDelimiterValue = "")
    val replaced = mapping[firstSegment] ?: firstSegment
    return if (remainder.isBlank()) replaced else "$replaced/$remainder"
}

private fun stripLeadingDirectory(path: String): String? {
    val slashIndex = path.indexOf('/')
    return if (slashIndex < 0) null else path.substring(slashIndex + 1)
}

private fun deriveRootImportDirectoryName(
    archive: ParsedSkillArchive,
    suggestedDirectoryName: String?,
): String {
    suggestedDirectoryName?.takeIf { it.isNotBlank() }?.let { return it }

    val rootSkillFile = archive.files.firstOrNull { it.path == SKILL_PACKAGE_FILE_NAME }
    if (rootSkillFile != null) {
        val parsed = parseSkillFrontmatter(rootSkillFile.bytes.toString(Charsets.UTF_8))
        if (parsed is SkillFrontmatterParseResult.Success) {
            return sanitizeSkillDirectoryName(
                input = parsed.frontmatter.name,
                fallback = DEFAULT_IMPORTED_SKILL_DIRECTORY,
            )
        }
    }

    return DEFAULT_IMPORTED_SKILL_DIRECTORY
}

internal fun readBundledSkillFiles(
    context: Context,
    assetPath: String,
): List<SkillArchiveFile> {
    val result = arrayListOf<SkillArchiveFile>()

    fun walk(currentAssetPath: String, relativePrefix: String = "") {
        val children = context.assets.list(currentAssetPath)
        if (children.isNullOrEmpty()) {
            context.assets.open(currentAssetPath).use { inputStream ->
                result += SkillArchiveFile(
                    path = relativePrefix,
                    bytes = inputStream.readBytes(),
                )
            }
            return
        }

        children.sorted().forEach { child ->
            val childAssetPath = "$currentAssetPath/$child"
            val childRelativePath = if (relativePrefix.isBlank()) child else "$relativePrefix/$child"
            walk(
                currentAssetPath = childAssetPath,
                relativePrefix = childRelativePath,
            )
        }
    }

    walk(assetPath)
    return result.sortedBy { it.path }
}
