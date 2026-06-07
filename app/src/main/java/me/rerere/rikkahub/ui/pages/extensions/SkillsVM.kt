package me.rerere.rikkahub.ui.pages.extensions

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillImportLimits
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.utils.launchEmitting
import org.json.JSONArray

private const val TAG = "SkillsVM"

/**
 * Where an import was started. Captured at the call site and carried on the completion event so only
 * the dialog that owns that source dismisses. Without this identity, a file import (no dialog) and a
 * GitHub import (the import dialog) share one ImportDone/ImportFailed, and a file import completing
 * while the GitHub dialog is open would dismiss that unrelated dialog.
 */
enum class SkillImportSource { FILE, GITHUB }

sealed interface SkillsEvent {
    data class ImportDone(val source: SkillImportSource, val name: String) : SkillsEvent
    data class ImportFailed(val source: SkillImportSource, val message: String) : SkillsEvent
    object SaveDone : SkillsEvent
    object SaveFailed : SkillsEvent
}

class SkillsVM(
    private val skillManager: SkillManager,
) : ViewModel() {
    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    private val _events = MutableSharedFlow<SkillsEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    init {
        loadSkills()
    }

    private fun loadSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    fun saveSkill(name: String, content: String) {
        launchEmitting(
            events = _events,
            context = Dispatchers.IO,
            onError = { SkillsEvent.SaveFailed },
        ) {
            val result = skillManager.saveSkill(name, content)
            _skills.value = skillManager.listSkills()
            _events.emit(if (result != null) SkillsEvent.SaveDone else SkillsEvent.SaveFailed)
        }
    }

    fun deleteSkill(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.deleteSkill(name)
            _skills.value = skillManager.listSkills()
        }
    }

    fun getSkillsDir() = skillManager.getSkillsDir()

    fun importSkillFromFile(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        launchEmitting(
            events = _events,
            context = Dispatchers.IO,
            onError = { SkillsEvent.ImportFailed(SkillImportSource.FILE, it.message ?: "未知错误") },
        ) {
            val fileName = FileUtils.getFileNameFromUri(appContext, uri).orEmpty()
            val bytes = appContext.contentResolver.openInputStream(uri)?.use {
                SkillImportLimits.readBytesLimited(
                    it,
                    SkillImportLimits.MAX_INPUT_BYTES,
                    fileName.ifBlank { "文件" },
                )
            }
                ?: run {
                    _events.emit(SkillsEvent.ImportFailed(SkillImportSource.FILE, "无法读取文件"))
                    return@launchEmitting
                }

            val importedNames = if (isZipFile(fileName, bytes)) {
                importSkillsFromZip(bytes)
            } else {
                importSkillMarkdown(bytes)
            }

            _skills.value = skillManager.listSkills()
            _events.emit(SkillsEvent.ImportDone(SkillImportSource.FILE, importedNames.joinToString()))
        }
    }

    fun importSkillFromGitHub(repoUrl: String) {
        launchEmitting(
            events = _events,
            context = Dispatchers.IO,
            onError = {
                Log.e(TAG, "Skill import/save failed", it)
                SkillsEvent.ImportFailed(SkillImportSource.GITHUB, it.message ?: "未知错误")
            },
        ) {
            val info = parseGitHubUrl(repoUrl) ?: run {
                _events.emit(SkillsEvent.ImportFailed(SkillImportSource.GITHUB, "无效的 GitHub 仓库链接"))
                return@launchEmitting
            }

            // Collect all files recursively via GitHub Contents API
            val files = mutableListOf<Pair<String, String>>() // relativePath -> downloadUrl
            val visited = intArrayOf(0)
            val listed = listFilesRecursively(info.owner, info.repo, info.branch, info.path, info.path, files, visited, depth = 0)
            if (!listed) {
                _events.emit(SkillsEvent.ImportFailed(SkillImportSource.GITHUB, "读取 GitHub 目录失败"))
                return@launchEmitting
            }

            val skillMdEntry = files.find { it.first == "SKILL.md" } ?: run {
                _events.emit(SkillsEvent.ImportFailed(SkillImportSource.GITHUB, "目录中未找到 SKILL.md"))
                return@launchEmitting
            }

            val skillMdContent = downloadText(skillMdEntry.second) ?: run {
                _events.emit(SkillsEvent.ImportFailed(SkillImportSource.GITHUB, "下载 SKILL.md 失败，请检查链接或网络"))
                return@launchEmitting
            }

            val frontmatter = SkillFrontmatterParser.parse(skillMdContent)
            val name = frontmatter["name"]
            if (name.isNullOrBlank()) {
                _events.emit(SkillsEvent.ImportFailed(SkillImportSource.GITHUB, "SKILL.md 格式错误：缺少 name 字段"))
                return@launchEmitting
            }

            val fileContents = LinkedHashMap<String, String>()
            var totalDownloaded = 0L
            for ((relativePath, downloadUrl) in files) {
                val content = downloadText(downloadUrl)
                if (content == null) {
                    _events.emit(SkillsEvent.ImportFailed(SkillImportSource.GITHUB, "下载文件失败：$relativePath"))
                    return@launchEmitting
                }
                totalDownloaded += content.toByteArray(Charsets.UTF_8).size
                SkillImportLimits.checkTotalAndCount(totalDownloaded, fileContents.size + 1)
                fileContents[relativePath] = content
            }

            val saved = skillManager.saveSkillFilesAtomically(name, fileContents)
            if (!saved) {
                _events.emit(SkillsEvent.ImportFailed(SkillImportSource.GITHUB, "保存失败"))
                return@launchEmitting
            }

            _skills.value = skillManager.listSkills()
            _events.emit(SkillsEvent.ImportDone(SkillImportSource.GITHUB, name))
        }
    }

    private fun importSkillMarkdown(bytes: ByteArray): List<String> {
        val content = bytes.toString(Charsets.UTF_8)
        val frontmatter = SkillFrontmatterParser.parse(content)
        val name = frontmatter["name"]?.trim()
        if (name.isNullOrBlank()) {
            error("SKILL.md 格式错误：缺少 name 字段")
        }
        if (frontmatter["description"].isNullOrBlank()) {
            error("SKILL.md 格式错误：缺少 description 字段")
        }
        val saved = skillManager.saveSkill(name, content) ?: error("保存失败，请检查技能格式")
        return listOf(saved.name)
    }

    private fun importSkillsFromZip(bytes: ByteArray): List<String> {
        val files = ZipInputStream(ByteArrayInputStream(bytes)).use { zipInput ->
            SkillImportLimits.scanZipEntries(zipInput, ::normalizeZipEntryPath)
        }

        val skillMdPaths = files.keys
            .filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
            .sorted()
        if (skillMdPaths.isEmpty()) {
            error("压缩包中未找到 SKILL.md")
        }
        val skillBasePaths = skillMdPaths.map {
            it.substringBeforeLast('/', missingDelimiterValue = "")
        }

        val importedNames = mutableListOf<String>()
        for (skillMdPath in skillMdPaths) {
            val skillContent = files[skillMdPath]?.toString(Charsets.UTF_8)
                ?: error("读取失败：$skillMdPath")
            val frontmatter = SkillFrontmatterParser.parse(skillContent)
            val name = frontmatter["name"]?.trim()
            if (name.isNullOrBlank()) {
                error("$skillMdPath 格式错误：缺少 name 字段")
            }
            if (frontmatter["description"].isNullOrBlank()) {
                error("$skillMdPath 格式错误：缺少 description 字段")
            }

            val basePath = skillMdPath.substringBeforeLast('/', missingDelimiterValue = "")
            val skillFiles = LinkedHashMap<String, ByteArray>()
            for ((path, content) in files) {
                if (isInsideNestedSkill(path, basePath, skillBasePaths)) continue
                val relativePath = relativeToSkillBase(path, basePath) ?: continue
                val targetPath = if (relativePath.equals("SKILL.md", ignoreCase = true)) {
                    "SKILL.md"
                } else {
                    relativePath
                }
                skillFiles[targetPath] = content
            }

            val saved = skillManager.saveSkillFileBytesAtomically(name, skillFiles)
            if (!saved) {
                error("保存失败：$name")
            }
            importedNames += name
        }
        return importedNames.distinct()
    }

    private fun isInsideNestedSkill(path: String, basePath: String, skillBasePaths: List<String>): Boolean {
        return skillBasePaths.any { otherBasePath ->
            otherBasePath != basePath &&
                isPathInsideBase(path, otherBasePath) &&
                (basePath.isBlank() || isPathInsideBase(otherBasePath, basePath))
        }
    }

    private fun isPathInsideBase(path: String, basePath: String): Boolean {
        return basePath.isBlank() || path == basePath || path.startsWith("$basePath/")
    }

    private fun relativeToSkillBase(path: String, basePath: String): String? {
        if (basePath.isBlank()) return path
        if (path == basePath) return null
        return path.removePrefix("$basePath/").takeIf { it != path }
    }

    private fun normalizeZipEntryPath(path: String): String? {
        val parts = path.replace('\\', '/')
            .trimStart('/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun isZipFile(fileName: String, bytes: ByteArray): Boolean {
        return fileName.endsWith(".zip", ignoreCase = true) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x03, 0x04) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x05, 0x06) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x07, 0x08)
    }

    private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
        if (size < values.size) return false
        return values.indices.all { index -> (this[index].toInt() and 0xFF) == values[index] }
    }

    private fun listFilesRecursively(
        owner: String,
        repo: String,
        branch: String,
        dirPath: String,
        basePath: String,
        result: MutableList<Pair<String, String>>,
        visited: IntArray,
        depth: Int,
    ): Boolean = SkillImportLimits.traverseGitHubTree(
        dirPath = dirPath,
        basePath = basePath,
        result = result,
        visited = visited,
        depth = depth,
    ) { currentDir -> fetchGitHubDir(owner, repo, branch, currentDir) }

    private fun fetchGitHubDir(
        owner: String,
        repo: String,
        branch: String,
        dirPath: String,
    ): List<SkillImportLimits.GitHubEntry>? {
        val apiUrl = "https://api.github.com/repos/$owner/$repo/contents/$dirPath?ref=$branch"
        val json = downloadText(apiUrl, SkillImportLimits.MAX_ENTRY_BYTES) ?: return null
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            SkillImportLimits.GitHubEntry(
                path = item.getString("path"),
                type = item.getString("type"),
                downloadUrl = item.optString("download_url"),
            )
        }
    }

    private data class GitHubRepoInfo(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String,
    )

    private fun parseGitHubUrl(url: String): GitHubRepoInfo? {
        val trimmed = url.trim().trimEnd('/')
        // https://github.com/owner/repo
        // https://github.com/owner/repo/tree/branch
        // https://github.com/owner/repo/tree/branch/sub/path
        val regex = Regex("""https://github\.com/([^/]+)/([^/]+)(?:/tree/([^/]+)(/.*)?)?""")
        val match = regex.matchEntire(trimmed) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val branch = match.groupValues[3].ifBlank { "HEAD" }
        val subPath = match.groupValues[4].trimStart('/')
        return GitHubRepoInfo(owner, repo, branch, subPath)
    }

    private fun downloadText(url: String, maxBytes: Long = SkillImportLimits.MAX_ENTRY_BYTES): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (connection.responseCode == 200) {
                val bytes = connection.inputStream.use {
                    SkillImportLimits.readBytesLimited(it, maxBytes, url)
                }
                bytes.toString(Charsets.UTF_8)
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }
}
