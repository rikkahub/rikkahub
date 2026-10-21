package me.rerere.rikkahub.ui.pages.extensions.skills

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.skills.InvalidSkillDir
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.data.skills.SkillMetadata
import me.rerere.rikkahub.data.skills.SkillValidationException
import me.rerere.rikkahub.data.skills.SkillValidator
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.zip.ZipInputStream
import kotlin.collections.iterator

class SkillsVM(
    private val context: Application,
    private val skillManager: SkillManager,
) : ViewModel() {
    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    private val _invalidSkillDirs = MutableStateFlow<List<InvalidSkillDir>>(emptyList())
    val invalidSkillDirs = _invalidSkillDirs.asStateFlow()

    init {
        loadSkills()
    }

    private fun loadSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshSkills()
        }
    }

    private fun refreshSkills() {
        _skills.value = skillManager.listSkills()
        _invalidSkillDirs.value = skillManager.listInvalidSkillDirs()
    }

    fun deleteInvalidSkillDir(dirName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.deleteInvalidSkillDir(dirName)
            refreshSkills()
        }
    }

    fun saveSkill(name: String, content: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = skillManager.saveSkill(name, content)
            refreshSkills()
            withContext(Dispatchers.Main) {
                onResult(result != null)
            }
        }
    }

    fun deleteSkill(skill: SkillMetadata) {
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.deleteSkill(skill)
            refreshSkills()
        }
    }

    fun importSkillFromFile(uri: Uri, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = FileUtils.getFileNameFromUri(context, uri).orEmpty()
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: run {
                        withContext(Dispatchers.Main) {
                            onResult(false, context.getString(R.string.skills_page_import_error_read_file))
                        }
                        return@launch
                    }

                val importedNames = if (isZipFile(fileName, bytes)) {
                    importSkillsFromZip(bytes)
                } else {
                    importSkillMarkdown(bytes)
                }

                refreshSkills()
                withContext(Dispatchers.Main) {
                    onResult(true, importedNames.joinToString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(
                        false,
                        e.message ?: context.getString(R.string.skills_page_unknown_error)
                    )
                }
            }
        }
    }

    fun importSkillFromGitHub(repoUrl: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = parseGitHubUrl(repoUrl) ?: run {
                    withContext(
                        Dispatchers.Main
                    ) { onResult(false, context.getString(R.string.skills_page_import_error_invalid_github_url)) }
                    return@launch
                }

                // Collect all files recursively via GitHub Contents API
                val files = mutableListOf<Pair<String, String>>() // relativePath -> downloadUrl
                val listed = listFilesRecursively(info.owner, info.repo, info.branch, info.path, info.path, files)
                if (!listed) {
                    withContext(
                        Dispatchers.Main
                    ) { onResult(false, context.getString(R.string.skills_page_import_error_github_list_failed)) }
                    return@launch
                }

                val skillMdEntry = files.find { it.first == "SKILL.md" } ?: run {
                    withContext(
                        Dispatchers.Main
                    ) { onResult(false, context.getString(R.string.skills_page_import_error_no_skill_md)) }
                    return@launch
                }

                val skillMdBytes = downloadBytes(skillMdEntry.second) ?: run {
                    withContext(
                        Dispatchers.Main
                    ) { onResult(false, context.getString(R.string.skills_page_import_error_download_skill_md)) }
                    return@launch
                }

                val name = SkillValidator.validate(skillMdBytes.toString(Charsets.UTF_8)).getOrElse {
                    withContext(Dispatchers.Main) { onResult(false, invalidFormatMessage("SKILL.md", it)) }
                    return@launch
                }

                // 按字节下载保存，避免图片等二进制附属文件被当作 UTF-8 文本解码而损坏
                val fileContents = LinkedHashMap<String, ByteArray>()
                for ((relativePath, downloadUrl) in files) {
                    val content = if (relativePath == "SKILL.md") skillMdBytes else downloadBytes(downloadUrl)
                    if (content == null) {
                        withContext(
                            Dispatchers.Main
                        ) {
                            onResult(
                                false,
                                context.getString(R.string.skills_page_import_error_download_file, relativePath)
                            )
                        }
                        return@launch
                    }
                    fileContents[relativePath] = content
                }

                val saved = skillManager.saveSkillFileBytesAtomically(name, fileContents)
                if (!saved) {
                    withContext(
                        Dispatchers.Main
                    ) { onResult(false, context.getString(R.string.skills_page_import_error_save_failed, name)) }
                    return@launch
                }

                refreshSkills()
                withContext(Dispatchers.Main) { onResult(true, name) }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onResult(
                        false,
                        e.message ?: context.getString(R.string.skills_page_unknown_error)
                    )
                }
            }
        }
    }

    private fun invalidFormatMessage(path: String, error: Throwable): String {
        val reason = (error as? SkillValidationException)?.error
            ?.let { context.getString(it.messageRes, *it.formatArgs) }
            ?: error.message.orEmpty()
        return context.getString(R.string.skills_page_import_error_invalid_format, path, reason)
    }

    private fun importSkillMarkdown(bytes: ByteArray): List<String> {
        val content = bytes.toString(Charsets.UTF_8)
        val name = SkillValidator.validate(content).getOrElse { error(invalidFormatMessage("SKILL.md", it)) }
        val saved = skillManager.saveSkill(name, content) ?: error(context.getString(R.string.skills_page_save_failed))
        return listOf(saved.name)
    }

    private fun importSkillsFromZip(bytes: ByteArray): List<String> {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                try {
                    if (!entry.isDirectory) {
                        val path = normalizeZipEntryPath(entry.name)
                        if (path != null) {
                            files[path] = zipInput.readBytes()
                        }
                    }
                } finally {
                    zipInput.closeEntry()
                }
            }
        }

        val skillMdPaths = files.keys
            .filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
            .sorted()
        if (skillMdPaths.isEmpty()) {
            error(context.getString(R.string.skills_page_import_error_zip_no_skill_md))
        }
        val skillBasePaths = skillMdPaths.map {
            it.substringBeforeLast('/', missingDelimiterValue = "")
        }

        val importedNames = mutableListOf<String>()
        for (skillMdPath in skillMdPaths) {
            val skillContent = files[skillMdPath]?.toString(Charsets.UTF_8)
                ?: error(context.getString(R.string.skills_page_import_error_read_entry, skillMdPath))
            val name = SkillValidator.validate(skillContent).getOrElse { error(invalidFormatMessage(skillMdPath, it)) }

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
                error(context.getString(R.string.skills_page_import_error_save_failed, name))
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
    ): Boolean {
        val apiUrl = "https://api.github.com/repos/$owner/$repo/contents/$dirPath?ref=$branch"
        val json = downloadBytes(apiUrl)?.toString(Charsets.UTF_8) ?: return false
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val type = item.getString("type")
            val itemPath = item.getString("path")
            val relativePath = itemPath.removePrefix("$basePath/").removePrefix(basePath)
            when (type) {
                "file" -> {
                    val downloadUrl = item.optString("download_url").takeIf { it.isNotBlank() }
                        ?: return false
                    result.add(relativePath to downloadUrl)
                }

                "dir" -> {
                    val ok = listFilesRecursively(owner, repo, branch, itemPath, basePath, result)
                    if (!ok) return false
                }
            }
        }
        return true
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

    private fun downloadBytes(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            val code = connection.responseCode
            // 未登录的 GitHub API 每小时仅 60 次，超限时给出明确提示而不是笼统的"读取失败"
            if ((code == 403 || code == 429) && connection.getHeaderField("X-RateLimit-Remaining") == "0") {
                error(context.getString(R.string.skills_page_import_error_github_rate_limit))
            }
            if (code == 200) connection.inputStream.use { it.readBytes() } else null
        } finally {
            connection.disconnect()
        }
    }
}
