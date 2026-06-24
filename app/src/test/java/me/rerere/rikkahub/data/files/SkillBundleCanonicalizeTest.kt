package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins [canonicalizeBundle] / [canonicalizePaths], the guard that makes the skill writer's key space
 * match what validation inspects. Without it an alias like "./SKILL.md" resolves to the same on-disk
 * file as "SKILL.md" but slips past a check that only looked at the literal "SKILL.md" key, letting an
 * approved bundle overwrite the validated file with different content. Uses a real temp skill dir because
 * canonicalization is genuine path resolution (`.`/`..`, escape detection).
 */
class SkillBundleCanonicalizeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun skillDir(): File = tmp.newFolder("demo")

    @Test
    fun `distinct canonical keys pass through unchanged`() {
        val dir = skillDir()
        val out = canonicalizeBundle(dir, mapOf("SKILL.md" to "a", "references/x.md" to "b"))
        assertEquals(mapOf("SKILL.md" to "a", "references/x.md" to "b"), out)
    }

    @Test
    fun `a dot-slash alias is normalized to its canonical key`() {
        val dir = skillDir()
        val out = canonicalizeBundle(dir, mapOf("./SKILL.md" to "a"))
        assertEquals(mapOf("SKILL.md" to "a"), out)
    }

    @Test
    fun `an in-skill dotdot alias is normalized to its canonical key`() {
        val dir = skillDir()
        val out = canonicalizeBundle(dir, mapOf("references/../SKILL.md" to "a"))
        assertEquals(mapOf("SKILL.md" to "a"), out)
    }

    // The load-bearing guard: two raw keys that resolve to ONE canonical path are ambiguous → refused,
    // so a valid "SKILL.md" cannot be co-submitted with an invalid "./SKILL.md" that overwrites it.
    @Test
    fun `two raw keys colliding on one canonical path are refused`() {
        val dir = skillDir()
        assertNull(canonicalizeBundle(dir, mapOf("SKILL.md" to "good", "./SKILL.md" to "evil")))
    }

    @Test
    fun `a path escaping the skill dir is refused`() {
        val dir = skillDir()
        assertNull(canonicalizeBundle(dir, mapOf("../escape.md" to "x")))
        assertNull(canonicalizeBundle(dir, mapOf("SKILL.md" to "ok", "../../evil" to "x")))
    }

    @Test
    fun `a key resolving to the skill dir itself is refused`() {
        val dir = skillDir()
        assertNull(canonicalizeBundle(dir, mapOf("." to "x")))
    }

    @Test
    fun `canonicalizePaths normalizes and dedups, and refuses escapes`() {
        val dir = skillDir()
        assertEquals(setOf("SKILL.md"), canonicalizePaths(dir, setOf("./SKILL.md", "SKILL.md")))
        assertNull(canonicalizePaths(dir, setOf("../escape")))
    }
}
