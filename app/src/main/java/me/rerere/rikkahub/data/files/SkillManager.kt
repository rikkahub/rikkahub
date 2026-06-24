package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
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

    /** True iff a skill with [skillName] already exists on disk (its SKILL.md is present). Used by
     *  create_skill to refuse clobbering an existing skill — create is for NEW skills only, and the
     *  atomic save would otherwise replace the whole directory, silently dropping its helper files. */
    fun skillExists(skillName: String): Boolean =
        resolveSkillDir(skillName)?.resolve("SKILL.md")?.exists() == true

    fun saveSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        target.parentFile?.mkdirs()
        target.writeText(content)
        return true
    }

    fun saveSkillFilesAtomically(skillName: String, files: Map<String, String>): Boolean {
        return saveSkillFileBytesAtomically(
            skillName = skillName,
            files = files.mapValues { it.value.toByteArray() },
        )
    }

    fun saveSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean {
        val skillsDir = getSkillsDir()
        val targetDir = resolveSkillDir(skillName) ?: return false
        // Re-key by canonical in-skill path FIRST, so the keys we validate are exactly the keys we write
        // (an alias like "./SKILL.md" can't slip a second, unvalidated write onto the same canonical file)
        // and an ambiguous bundle (two raw keys → one path) is refused rather than written order-dependently.
        val canonicalFiles = canonicalizeBundle(targetDir, files) ?: return false
        if (!canonicalFiles.containsKey("SKILL.md")) return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in canonicalFiles) {
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

    /**
     * Every file in the skill as `relativePath -> text` (SKILL.md first), or null if the skill does not
     * exist. This is the snapshot [updateSkillBundle] re-saves: [saveSkillFilesAtomically] replaces the
     * WHOLE directory with exactly the map it is handed, so a partial save would silently drop the
     * files it omits — update must always write the full, merged set.
     */
    fun listSkillFiles(skillName: String): Map<String, String>? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        if (!skillDir.resolve("SKILL.md").exists()) return null
        val out = linkedMapOf<String, String>()
        skillDir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(skillDir).invariantSeparatorsPath != "SKILL.md" }
            .forEach { file ->
                out[file.relativeTo(skillDir).invariantSeparatorsPath] = file.readText()
            }
        return out
    }

    /**
     * Compare-and-set-free bundle update: merge [changes] (write/overwrite) and [deletes] onto the
     * CURRENT file set, then re-save the full result atomically — so files the caller didn't mention are
     * preserved. Fails closed (false) when the skill is missing, any change/delete path escapes
     * containment, or the merge would remove SKILL.md (a skill without SKILL.md is not a skill).
     */
    fun updateSkillBundle(
        skillName: String,
        changes: Map<String, String>,
        deletes: Set<String>,
    ): Boolean {
        val current = listSkillFiles(skillName) ?: return false
        val skillDir = resolveSkillDir(skillName) ?: return false
        // Re-key changes/deletes to the SAME canonical key space as [current] (listSkillFiles' canonical
        // keys) and the writer, so the merge and validation see exactly what will be written — fail closed
        // on a path that escapes the skill dir or an ambiguous alias-collision in changes.
        val canonicalChanges = canonicalizeBundle(skillDir, changes) ?: return false
        val canonicalDeletes = canonicalizePaths(skillDir, deletes) ?: return false
        val merged = mergeSkillBundle(current, canonicalChanges, canonicalDeletes) ?: return false
        // The merged SKILL.md must still parse to a VALID, same-named skill: discovery keys off the
        // frontmatter `name`/`description`, and the on-disk directory is [skillName], so a blanked field
        // or a rename via frontmatter would orphan the skill (resolveSkillDir(newName) finds nothing).
        if (!mergedSkillIsValid(merged, skillName)) return false
        return saveSkillFileBytesAtomically(skillName, merged.mapValues { it.value.toByteArray() })
    }

    fun deleteSkillFile(skillName: String, relativePath: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        return target.delete()
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
                skillDir = skillDir,
            )
        }.getOrElse {
            Log.w(TAG, "parseSkillFile: Failed to parse ${skillFile.absolutePath}", it)
            null
        }
    }
}

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val allowedTools: List<String> = emptyList(),
    val skillDir: File,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}

/**
 * Pure bundle merge for [SkillManager.updateSkillBundle]: apply [changes] (write/overwrite) and
 * [deletes] onto [current], returning the full resulting file set — or null if the result would have no
 * SKILL.md (a skill without SKILL.md is invalid, so such an update is refused rather than corrupting the
 * skill). The LOAD-BEARING invariant is that files NOT named in changes/deletes are preserved, because
 * the atomic save replaces the whole directory with exactly this map. Containment is checked by the
 * caller (it needs the on-disk skill dir); this stays pure so it is JVM-unit-testable.
 */
internal fun mergeSkillBundle(
    current: Map<String, String>,
    changes: Map<String, String>,
    deletes: Set<String>,
): Map<String, String>? {
    val merged = LinkedHashMap(current)
    deletes.forEach { merged.remove(it) }
    merged.putAll(changes)
    return merged.takeIf { it.containsKey("SKILL.md") }
}

/**
 * After an update merge, the resulting SKILL.md must still describe the SAME skill we are writing back:
 * a non-blank `name` equal to [expectedName] (the on-disk directory id — renaming via frontmatter would
 * orphan the skill) and a non-blank `description` (without it [SkillManager.parseSkillFile] returns null
 * and the skill silently disappears from discovery). Pure so update_skill's validity gate is unit-tested.
 */
internal fun mergedSkillIsValid(merged: Map<String, String>, expectedName: String): Boolean {
    val skillMd = merged["SKILL.md"] ?: return false
    val frontmatter = SkillFrontmatterParser.parse(skillMd)
    if (frontmatter["name"]?.takeIf { it.isNotBlank() } != expectedName) return false
    return frontmatter["description"]?.isNotBlank() == true
}

// The canonical in-skill relative path [SkillPaths.resolveSkillFile] would write [rawKey] to (`.`/`..`
// resolved), or null if it escapes the skill dir or resolves to the dir itself.
private fun canonicalRelativePath(skillDir: File, canonicalSkillDir: File, rawKey: String): String? {
    val resolved = SkillPaths.resolveSkillFile(skillDir, rawKey) ?: return null
    val rel = resolved.relativeTo(canonicalSkillDir).invariantSeparatorsPath
    return rel.takeIf { it.isNotBlank() && it != "." }
}

/**
 * Re-key a model-supplied bundle by the CANONICAL path the atomic writer will actually write each entry
 * to, so validation and the write share ONE key space. Returns null (fail closed) if any path escapes
 * the skill dir / resolves to the dir itself, or if two distinct raw keys collide on the same canonical
 * path — an ambiguous bundle whose on-disk result would otherwise be iteration-order dependent and could
 * smuggle invalid content past a check that only inspected the literal key. Top-level (like
 * [mergeSkillBundle]) so the collision/escape policy is JVM-unit-testable with a real temp skill dir.
 */
internal fun <T> canonicalizeBundle(skillDir: File, bundle: Map<String, T>): Map<String, T>? {
    val canonicalSkillDir = try { skillDir.canonicalFile } catch (_: IOException) { return null }
    val out = LinkedHashMap<String, T>()
    for ((rawKey, value) in bundle) {
        val canonicalRel = canonicalRelativePath(skillDir, canonicalSkillDir, rawKey) ?: return null
        if (out.put(canonicalRel, value) != null) return null // two raw keys → same canonical path
    }
    return out
}

/** Canonical-path form of a delete set (dedup is harmless for deletes); null if any path escapes. */
internal fun canonicalizePaths(skillDir: File, paths: Set<String>): Set<String>? {
    val canonicalSkillDir = try { skillDir.canonicalFile } catch (_: IOException) { return null }
    val out = LinkedHashSet<String>()
    for (raw in paths) {
        out.add(canonicalRelativePath(skillDir, canonicalSkillDir, raw) ?: return null)
    }
    return out
}

object SkillFrontmatterParser {
    private val frontmatterEndRegex = Regex("""\r?\n---(?:\r?\n|$)""")

    fun parse(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (!content.startsWith("---")) return result
        val endRange = findFrontmatterEndRange(content) ?: return result
        val yaml = content.substring(3, endRange.first).trim()
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
        if (!content.startsWith("---")) return content
        val endRange = findFrontmatterEndRange(content) ?: return content
        return content.substring(endRange.last + 1).trimStart('\r', '\n')
    }

    private fun findFrontmatterEndRange(content: String): IntRange? {
        if (!content.startsWith("---")) return null
        return frontmatterEndRegex.find(content, startIndex = 3)?.range
    }
}
