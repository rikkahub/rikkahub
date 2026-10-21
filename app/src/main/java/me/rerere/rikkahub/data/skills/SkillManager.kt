package me.rerere.rikkahub.data.skills

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SkillManager"
    }

    private val builtinLock = Any()

    @Volatile
    private var builtinExtracted = false

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getBuiltinSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.BUILTIN_SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 确保内置技能已从 assets 解压到 [getBuiltinSkillsDir]，每个进程只检查一次。
     */
    fun ensureBuiltinSkillsExtracted() {
        if (builtinExtracted) return
        synchronized(builtinLock) {
            if (builtinExtracted) return
            runCatching {
                BuiltinSkills.extractIfNeeded(context, getBuiltinSkillsDir())
            }.onFailure {
                Log.w(TAG, "ensureBuiltinSkillsExtracted: Failed to extract builtin skills", it)
            }
            builtinExtracted = true
        }
    }

    /**
     * 列出所有可用技能：用户技能 + 内置技能，同名时用户技能覆盖内置技能。
     */
    fun listSkills(): List<SkillMetadata> = mergeWithBuiltinSkills(
        local = listSkillsIn(getSkillsDir(), builtin = false),
        builtin = listBuiltinSkills(),
    )

    fun findSkill(name: String): SkillMetadata? = listSkills().firstOrNull { it.name == name }

    private fun listBuiltinSkills(): List<SkillMetadata> {
        ensureBuiltinSkillsExtracted()
        return listSkillsIn(getBuiltinSkillsDir(), builtin = true)
    }

    private fun listSkillsIn(root: File, builtin: Boolean): List<SkillMetadata> =
        skillDirsIn(root).mapNotNull { parseSkillDir(it, builtin).getOrNull() }

    /**
     * 列出用户技能目录下无法识别为技能的目录，如空目录、缺少 SKILL.md 或 frontmatter 不完整。
     * 这些目录不会出现在 [listSkills] 中，单独列出来让用户清理。
     */
    fun listInvalidSkillDirs(): List<InvalidSkillDir> = skillDirsIn(getSkillsDir())
        .mapNotNull { dir ->
            val reason = when (val error = parseSkillDir(dir, builtin = false).exceptionOrNull()) {
                null -> return@mapNotNull null
                is InvalidSkillException -> error.reason
                else -> InvalidSkillReason.ReadFailed
            }
            InvalidSkillDir(dirName = dir.name, reason = reason)
        }
        .sortedBy { it.dirName }

    suspend fun deleteInvalidSkillDir(dirName: String): Boolean = withContext(Dispatchers.IO) {
        val dir = SkillPaths.resolveSkillDir(getSkillsDir(), dirName) ?: return@withContext false
        if (!dir.isDirectory) return@withContext false
        // 列表展示期间目录可能已在工作区里补全成有效技能，删除前再确认一次
        if (parseSkillDir(dir, builtin = false).isSuccess) return@withContext false
        dir.deleteRecursively()
    }

    // 跳过隐藏目录，如原子写入残留的 .<name>.staging.N.tmp
    private fun skillDirsIn(root: File): List<File> =
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()

    fun saveSkill(name: String, content: String): SkillMetadata? {
        // 通过原子写入(staging + rename)落盘，避免直接 mkdirs 失败时
        // writeText 抛出 FileNotFoundException 导致崩溃
        if (!saveSkillFileBytesAtomically(name, mapOf("SKILL.md" to content.toByteArray()))) {
            return null
        }
        val skillDir = resolveSkillDir(name) ?: return null
        return parseSkillDir(skillDir, builtin = false).getOrNull()
    }

    suspend fun deleteSkill(skill: SkillMetadata): Boolean = withContext(Dispatchers.IO) {
        if (skill.builtin) return@withContext false
        val name = skill.name
        // 按技能实际所在的目录删除：在工作区里直接创建、复制或改过 name 的技能，
        // 目录名可能和 frontmatter 的 name 不一致，按 name 拼路径会找不到目录
        val skillDir = SkillPaths.resolveSkillDir(getSkillsDir(), skill.skillDir.name) ?: return@withContext false
        // 目录不存在时 deleteRecursively 也返回 true，需提前拦截，避免误清理启用状态
        if (!skillDir.exists()) return@withContext false
        val deleted = skillDir.deleteRecursively()
        // 还有同名技能（被覆盖的内置技能或另一个同名用户技能）会继续生效，保留启用状态
        if (deleted && listSkills().none { it.name == name }) {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.enabledSkills.contains(name)) {
                            assistant.copy(enabledSkills = assistant.enabledSkills - name)
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
        deleted
    }

    /**
     * 清理所有助手 enabledSkills 中已不存在于磁盘的技能名。
     *
     * 当用户在 App 外直接删除 /skills/ 目录下的技能时，不会走 [deleteSkill] 的清理逻辑，
     * 导致 enabledSkills 残留"幽灵"技能名，使扩展入口角标计数偏大。
     */
    suspend fun pruneOrphanedEnabledSkills(): List<SkillMetadata> = withContext(Dispatchers.IO) {
        val skills = listSkills()
        val existing = skills.mapTo(HashSet()) { it.name }
        settingsStore.update { settings ->
            var changed = false
            val newAssistants = settings.assistants.map { assistant ->
                val pruned = assistant.enabledSkills.filterTo(LinkedHashSet()) { it in existing }
                if (pruned.size != assistant.enabledSkills.size) {
                    changed = true
                    assistant.copy(enabledSkills = pruned)
                } else {
                    assistant
                }
            }
            if (changed) settings.copy(assistants = newAssistants) else settings
        }
        skills
    }

    fun saveSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        val parent = target.parentFile ?: return false
        // 先写同目录临时文件再 rename 覆盖，避免写到一半失败时损坏原文件；
        // IO 异常（如 mkdirs 失败导致 FileNotFoundException）转为返回 false，不向调用方抛出
        val tempFile = parent.resolve(".${target.name}.tmp")
        return try {
            if (!parent.exists() && !parent.mkdirs()) return false
            tempFile.writeText(content)
            tempFile.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFile: Failed to save $skillName/$relativePath", e)
            false
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    fun saveSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean {
        val skillMd = files["SKILL.md"] ?: return false
        // 目录名必须和 SKILL.md 的 name 一致，否则之后按名字找不到目录
        if (SkillValidator.validate(skillMd.toString(Charsets.UTF_8)).getOrNull() != skillName) {
            Log.w(TAG, "saveSkillFilesAtomically: Invalid SKILL.md for $skillName")
            return false
        }
        val skillsDir = getSkillsDir()
        val targetDir = resolveSkillDir(skillName) ?: return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeBytes(content)
            }

            if (!stagingDir.resolve("SKILL.md").exists()) return false

            if (targetDir.exists()) {
                backupDir = createTempSkillDir(skillsDir, skillName, "backup") ?: return false
                if (!targetDir.renameTo(backupDir)) return false
            }

            if (!stagingDir.renameTo(targetDir)) {
                if (backupDir != null && !targetDir.exists()) {
                    backupDir.renameTo(targetDir)
                }
                return false
            }

            backupDir?.deleteRecursively()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFilesAtomically: Failed to save $skillName", e)
            if (backupDir != null && !targetDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            return false
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            if (backupDir?.exists() == true && targetDir.exists()) {
                backupDir.deleteRecursively()
            }
        }
    }

    fun deleteSkillFile(skillName: String, relativePath: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        return target.delete()
    }

    private fun resolveSkillDir(skillName: String): File? {
        return SkillPaths.resolveSkillDir(getSkillsDir(), skillName)
    }

    private fun createTempSkillDir(skillsRoot: File, skillName: String, suffix: String): File? {
        repeat(100) { attempt ->
            val candidate = skillsRoot.resolve(".$skillName.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate
            }
        }
        return null
    }

    /** 解析技能目录；无法识别时为 [InvalidSkillException]，读文件失败时为 IO 异常 */
    private fun parseSkillDir(skillDir: File, builtin: Boolean): Result<SkillMetadata> = runCatching {
        val skillFile = skillDir.resolve("SKILL.md")
        if (!skillFile.isFile) throw InvalidSkillException(InvalidSkillReason.MissingSkillMd)
        val frontmatter = SkillFrontmatterParser.parse(skillFile.readText())
        val name = frontmatter["name"]?.takeIf { it.isNotBlank() }
            ?: throw InvalidSkillException(InvalidSkillReason.MissingName)
        val description = frontmatter["description"]?.takeIf { it.isNotBlank() }
            ?: throw InvalidSkillException(InvalidSkillReason.MissingDescription)
        SkillMetadata(
            name = name,
            description = description,
            compatibility = frontmatter["compatibility"],
            skillDir = skillDir,
            builtin = builtin,
        )
    }
}

/** 用户技能目录下无法识别为技能的目录 */
data class InvalidSkillDir(
    val dirName: String,
    val reason: InvalidSkillReason,
)

enum class InvalidSkillReason {
    MissingSkillMd,
    MissingName,
    MissingDescription,
    ReadFailed,
}

private class InvalidSkillException(val reason: InvalidSkillReason) : Exception(reason.name)

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val skillDir: File,
    /** 内置技能，来自 assets 解压，只读 */
    val builtin: Boolean = false,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}
