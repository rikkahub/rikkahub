package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM coverage for the PURE builtin/user skill layering (Part B slice 2): how a skill root is listed,
 * how the two layers merge (user wins), and how a name resolves across them for reads. The
 * Android-coupled extraction ([BundledSkillSource]) and SkillManager wiring are exercised on-device;
 * these helpers carry the precedence + read-fallthrough invariants and are root-parameterized.
 */
class BundledSkillLayeringTest {

    private fun tempRoot(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun writeSkill(root: File, name: String, description: String = "desc for $name") {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: $name\ndescription: $description\n---\nbody of $name")
    }

    @Test
    fun `listSkillsInRoot returns valid skills stamped with the builtin flag`() {
        val root = tempRoot("ws-list")
        writeSkill(root, "deep-research")
        writeSkill(root, "summarize")

        val builtins = listSkillsInRoot(root, builtin = true)

        assertEquals(setOf("deep-research", "summarize"), builtins.map { it.name }.toSet())
        assertTrue("every entry from a builtin root is flagged builtin", builtins.all { it.builtin })
    }

    @Test
    fun `listSkillsInRoot ignores dirs without SKILL_md and non-dir entries`() {
        val root = tempRoot("ws-ignore")
        writeSkill(root, "good")
        File(root, "no-skill-md").mkdirs() // a dir without SKILL.md
        File(root, ".version").writeText("1") // a plain marker file, not a skill dir

        assertEquals(listOf("good"), listSkillsInRoot(root, builtin = true).map { it.name })
    }

    @Test
    fun `listSkillsInRoot is empty for a missing root`() {
        assertTrue(listSkillsInRoot(File(tempRoot("ws-missing"), "does-not-exist"), builtin = true).isEmpty())
    }

    @Test
    fun `mergeSkillLayers keeps user skills first and appends only un-shadowed builtins`() {
        val userRoot = tempRoot("ws-user")
        val builtinRoot = tempRoot("ws-builtin")
        writeSkill(userRoot, "mine")
        writeSkill(userRoot, "shared") // shadows the builtin of the same name
        writeSkill(builtinRoot, "shared")
        writeSkill(builtinRoot, "deep-research")

        val merged = mergeSkillLayers(
            user = listSkillsInRoot(userRoot, builtin = false),
            builtin = listSkillsInRoot(builtinRoot, builtin = true),
        )

        // user skills lead; the builtin "shared" is dropped (shadowed); "deep-research" is appended.
        assertEquals(setOf("mine", "shared"), merged.filter { !it.builtin }.map { it.name }.toSet())
        assertEquals(listOf("deep-research"), merged.filter { it.builtin }.map { it.name })
        // the surviving "shared" is the USER copy, not the builtin.
        assertFalse(merged.single { it.name == "shared" }.builtin)
    }

    // Regression: a malformed user dir (valid-looking dir name, but frontmatter missing description)
    // must NOT hijack a read of a builtin of that name. Listing drops it (so the builtin shows), and the
    // read must AGREE by falling through to the builtin — not silently load the malformed user file.
    @Test
    fun `a malformed user dir does not shadow or hijack a same-named builtin`() {
        val userRoot = tempRoot("ws-baduser")
        val builtinRoot = tempRoot("ws-goodbuiltin")
        // user dir "deep-research" with NO description -> parseSkillMetadata returns null (invalid)
        File(userRoot, "deep-research").apply { mkdirs() }
            .let { File(it, "SKILL.md").writeText("---\nname: deep-research\n---\nbody") }
        writeSkill(builtinRoot, "deep-research")

        // listing: the invalid user dir is dropped; the builtin shows
        val merged = mergeSkillLayers(
            user = listSkillsInRoot(userRoot, builtin = false),
            builtin = listSkillsInRoot(builtinRoot, builtin = true),
        )
        assertEquals(listOf("deep-research"), merged.map { it.name })
        assertTrue("the surviving deep-research is the builtin", merged.single().builtin)

        // read: agrees — resolves to the builtin, not the malformed user dir
        assertEquals(
            File(builtinRoot, "deep-research").canonicalFile,
            resolveReadableSkillDir(userRoot, builtinRoot, "deep-research")?.canonicalFile,
        )
    }

    // The create-clobber guard keys off FILE presence, not validity: it must refuse to overwrite even a
    // malformed existing skill dir (so create_skill can't silently drop a dir the user is repairing).
    @Test
    fun `skillMdExistsInRoot reports presence regardless of validity`() {
        val root = tempRoot("ws-exists")
        writeSkill(root, "valid")
        File(root, "malformed").apply { mkdirs() }
            .let { File(it, "SKILL.md").writeText("---\nname: malformed\n---\nno description") }
        File(root, "empty-dir").mkdirs() // no SKILL.md at all

        assertTrue(skillMdExistsInRoot(root, "valid"))
        assertTrue("a malformed SKILL.md still counts as present", skillMdExistsInRoot(root, "malformed"))
        assertFalse(skillMdExistsInRoot(root, "empty-dir"))
        assertFalse(skillMdExistsInRoot(root, "nonexistent"))
    }

    @Test
    fun `resolveReadableSkillDir prefers the user copy, falls back to the builtin, else null`() {
        val userRoot = tempRoot("ws-ruser")
        val builtinRoot = tempRoot("ws-rbuiltin")
        writeSkill(userRoot, "shared")
        writeSkill(builtinRoot, "shared")
        writeSkill(builtinRoot, "builtin-only")

        // user wins for a shared name
        assertEquals(
            File(userRoot, "shared").canonicalFile,
            resolveReadableSkillDir(userRoot, builtinRoot, "shared")?.canonicalFile,
        )
        // builtin-only resolves to the builtin root
        assertEquals(
            File(builtinRoot, "builtin-only").canonicalFile,
            resolveReadableSkillDir(userRoot, builtinRoot, "builtin-only")?.canonicalFile,
        )
        // neither root has it
        assertNull(resolveReadableSkillDir(userRoot, builtinRoot, "nonexistent"))
    }
}
