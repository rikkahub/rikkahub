package me.rerere.rikkahub.skills

import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillPaths
import java.io.File
import java.nio.file.Files

/**
 * JVM-test [SkillSaver] backed by an OS temp directory. Used in place of the production
 * SkillManager (which needs a Context for `filesDir`) so importer tests can round-trip
 * actual file writes without Robolectric or instrumented testing.
 */
internal class TempDirSkillSaver : SkillSaver {

    val tmpDir: File = Files.createTempDirectory("skill-saver-test").toFile().also {
        it.deleteOnExit()
    }

    override fun saveSkill(name: String, content: String): SkillMetadata? {
        val skillDir = SkillPaths.resolveSkillDir(tmpDir, name) ?: return null
        skillDir.mkdirs()
        val skillFile = skillDir.resolve("SKILL.md")
        skillFile.writeText(content)
        val frontmatter = SkillFrontmatterParser.parse(content)
        val parsedName = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
        val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
        return SkillMetadata(
            name = parsedName,
            description = description,
            compatibility = frontmatter["compatibility"],
            skillDir = skillDir,
        )
    }
}
