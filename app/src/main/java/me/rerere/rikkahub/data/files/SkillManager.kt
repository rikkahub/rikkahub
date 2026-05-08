package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SkillManager"
    }

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listSkills(): List<SkillMetadata> {
        val skillsDir = getSkillsDir()
        return skillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                parseSkillFile(skillFile, dir)
            }
            ?: emptyList()
    }

    fun readSkillBody(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return SkillFrontmatterParser.extractBody(skillFile.readText())
    }

    fun readSkillContent(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return skillFile.readText()
    }

    fun saveSkill(name: String, content: String): SkillMetadata? {
        val skillDir = resolveSkillDir(name) ?: return null
        skillDir.mkdirs()
        val skillFile = skillDir.resolve("SKILL.md")
        skillFile.writeText(content)
        return parseSkillFile(skillFile, skillDir)
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        val deleted = skillDir.deleteRecursively()
        if (deleted) {
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

    fun getSkillDir(skillName: String): File? = resolveSkillDir(skillName)

    fun saveSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        target.parentFile?.mkdirs()
        target.writeText(content)
        return true
    }

    fun saveSkillFilesAtomically(skillName: String, files: Map<String, String>): Boolean {
        val skillsDir = getSkillsDir()
        val targetDir = resolveSkillDir(skillName) ?: return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeText(content)
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

    /**
     * Copy any default skills bundled in `assets/default-skills/<name>/` into the user's
     * filesDir on first launch, but only if they have not already been installed before.
     * "Before" is tracked with a sentinel marker file inside each seeded skill so subsequent
     * launches skip the copy without checking individual file mtimes — and so the user can
     * delete a default skill and we will not silently re-install it.
     */
    fun seedDefaultSkillsIfNeeded() {
        val assetRoot = "default-skills"
        val assetMgr = context.assets
        val skillNames = try {
            assetMgr.list(assetRoot).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "seedDefaultSkillsIfNeeded: cannot list assets", e)
            return
        }
        for (skillName in skillNames) {
            val targetDir = SkillPaths.resolveSkillDir(getSkillsDir(), skillName) ?: continue

            // Read the bundled SKILL.md once to decide what to do.
            val bundledSkillMd = runCatching {
                assetMgr.open("$assetRoot/$skillName/SKILL.md").bufferedReader().use { it.readText() }
            }.getOrNull()
            val isCoreSkill = bundledSkillMd?.let { content ->
                SkillFrontmatterParser.parse(content)["auto_load"]?.equals("true", ignoreCase = true) == true
            } == true

            val sentinel = targetDir.resolve(".seeded")
            val coreVersionFile = targetDir.resolve(".core-bundled-hash")

            if (isCoreSkill) {
                // Core skills (auto_load=true) re-seed whenever the bundled content changes
                // — typically across an APK upgrade. This keeps SOUL/HEARTBEAT/TOOLS in
                // sync with the app version while still allowing the user to edit between
                // upgrades (their edits stick until we ship a new bundled version).
                val bundledHash = computeBundledSkillHash(assetRoot, skillName)
                val currentHash = if (coreVersionFile.exists()) coreVersionFile.readText().trim() else ""
                if (bundledHash == currentHash) continue
                try {
                    if (targetDir.exists()) targetDir.deleteRecursively()
                    copyAssetSkill(assetRoot, skillName, targetDir)
                    sentinel.writeText(System.currentTimeMillis().toString())
                    coreVersionFile.writeText(bundledHash)
                    Log.i(TAG, "seedDefaultSkillsIfNeeded: re-seeded core skill $skillName (hash=$bundledHash)")
                } catch (e: Exception) {
                    Log.w(TAG, "seedDefaultSkillsIfNeeded: failed to re-seed core skill $skillName", e)
                }
                continue
            }

            // Non-core (lazy) skills: original behavior — seed once, then leave alone.
            // The user may have manually installed and then deleted the skill. Detect that
            // case by checking whether the directory exists at all — if it does and there is
            // no sentinel, the user owns it; do not overwrite. If the directory does not
            // exist, this is a fresh install and we can seed.
            if (sentinel.exists()) continue
            if (targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true) continue
            try {
                copyAssetSkill(assetRoot, skillName, targetDir)
                sentinel.writeText(System.currentTimeMillis().toString())
                Log.i(TAG, "seedDefaultSkillsIfNeeded: seeded $skillName")
            } catch (e: Exception) {
                Log.w(TAG, "seedDefaultSkillsIfNeeded: failed to seed $skillName", e)
            }
        }
    }

    /**
     * Compute a stable hash over every file in the bundled skill (recursively, in sorted
     * order so the result is deterministic across runs). Used as the "version" of the
     * bundled core skill so we know when to re-seed the user's local copy.
     *
     * Asset-read failures are mixed into the digest as a stable marker rather than
     * silently skipped so a transient read failure can't change the hash on a later
     * successful read (which would trigger a spurious re-seed and clobber user edits).
     */
    private fun computeBundledSkillHash(assetRoot: String, skillName: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val readFailMarker = "<<read-failed>>".toByteArray()
        fun walk(path: String) {
            val children = context.assets.list(path).orEmpty().toList().sorted()
            for (child in children) {
                val childPath = "$path/$child"
                if (isAssetDirectory(childPath)) {
                    walk(childPath)
                } else {
                    md.update(child.toByteArray())  // include name so renames bump the hash
                    val ok = runCatching {
                        context.assets.open(childPath).use { input ->
                            val buf = ByteArray(8 * 1024)
                            while (true) {
                                val n = input.read(buf); if (n <= 0) break
                                md.update(buf, 0, n)
                            }
                        }
                    }.isSuccess
                    if (!ok) {
                        Log.w(TAG, "computeBundledSkillHash: read failed for $childPath; marker mixed into digest")
                        md.update(readFailMarker)
                    }
                }
            }
        }
        walk("$assetRoot/$skillName")
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyAssetSkill(assetRoot: String, skillName: String, targetDir: File) {
        val assetMgr = context.assets
        targetDir.mkdirs()
        val children = assetMgr.list("$assetRoot/$skillName").orEmpty()
        for (child in children) {
            val source = "$assetRoot/$skillName/$child"
            if (isAssetDirectory(source)) {
                // Recurse into directories — including the genuinely-empty case where
                // listing returns []. The recursive call mkdirs the empty target
                // and exits cleanly without copying anything.
                copyAssetSkill("$assetRoot/$skillName", child, targetDir.resolve(child))
                continue
            }
            val outFile = targetDir.resolve(child)
            assetMgr.open(source).use { input ->
                outFile.outputStream().use { out -> input.copyTo(out) }
            }
        }
    }

    /**
     * Reliably distinguish an asset directory from an asset file. `AssetManager.list`
     * returns an empty array for both files and empty directories, which the previous
     * heuristic confused — any bundled skill shipping an empty placeholder subdir would
     * crash the seed when we later tried to `assetMgr.open` it as a file.
     *
     * The trick: try to open it as a file. Files succeed; directories throw. This is
     * the same approach AOSP's sample code recommends.
     */
    private fun isAssetDirectory(path: String): Boolean {
        return runCatching { context.assets.open(path).close() }.isFailure
    }

    fun resolveSkillFile(skillName: String, relativePath: String): File? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        return SkillPaths.resolveSkillFile(skillDir, relativePath)
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

    private fun parseSkillFile(skillFile: File, skillDir: File): SkillMetadata? {
        return runCatching {
            val content = skillFile.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
            SkillMetadata(
                name = name,
                description = description,
                compatibility = frontmatter["compatibility"],
                allowedTools = frontmatter["allowed-tools"]?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                autoLoad = frontmatter["auto_load"]?.equals("true", ignoreCase = true) == true,
                autoLoadPath = frontmatter["auto_load_path"]?.takeIf { it.isNotBlank() },
                skillDir = skillDir,
            )
        }.getOrElse {
            Log.w(TAG, "parseSkillFile: Failed to parse ${skillFile.absolutePath}", it)
            null
        }
    }
}

/**
 * @property autoLoad If true, the skill's body (or [autoLoadPath] file if set) is injected
 * directly into the system prompt every turn instead of being lazy-loaded via the `use_skill`
 * tool. Use this for "core persona" skills like agent-core where the model needs the content
 * unconditionally — see SkillsTools.kt for where the injection happens. Frontmatter:
 * `auto_load: true`.
 * @property autoLoadPath Relative path inside the skill directory of the file to auto-load
 * (e.g. "SOUL.md"). Defaults to SKILL.md if not set. Frontmatter: `auto_load_path: SOUL.md`.
 */
data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val allowedTools: List<String> = emptyList(),
    val autoLoad: Boolean = false,
    val autoLoadPath: String? = null,
    val skillDir: File,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}

object SkillFrontmatterParser {
    private val frontmatterEndRegex = Regex("""\r?\n---(?:\r?\n|$)""")

    /**
     * UTF-8 BOM character. Some editors (notably Windows Notepad, VS Code on Windows with
     * certain settings) prepend this to UTF-8 files. Strip it before parsing so that
     * `﻿---` is treated the same as `---`.
     */
    private const val BOM = '﻿'

    fun parse(content: String): Map<String, String> {
        val normalised = if (content.startsWith(BOM)) content.substring(1) else content
        val result = mutableMapOf<String, String>()
        if (!normalised.startsWith("---")) return result
        val endRange = findFrontmatterEndRange(normalised) ?: return result
        val yaml = normalised.substring(3, endRange.first).trim()
        yaml.lines().forEach { line ->
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim().removeSurrounding("\"")
                if (key.isNotBlank() && value.isNotBlank()) {
                    result[key] = value
                }
            }
        }
        return result
    }

    fun extractBody(content: String): String {
        val normalised = if (content.startsWith(BOM)) content.substring(1) else content
        if (!normalised.startsWith("---")) return normalised
        val endRange = findFrontmatterEndRange(normalised) ?: return normalised
        return normalised.substring(endRange.last + 1).trimStart('\r', '\n')
    }

    private fun findFrontmatterEndRange(content: String): IntRange? {
        if (!content.startsWith("---")) return null
        return frontmatterEndRegex.find(content, startIndex = 3)?.range
    }
}
