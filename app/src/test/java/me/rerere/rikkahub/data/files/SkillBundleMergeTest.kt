package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [mergeSkillBundle], the load-bearing core of update_skill. Because the atomic save replaces the
 * whole skill directory with exactly the map it is handed, a partial update would silently delete the
 * files it omitted — so update MUST merge changes/deletes onto the CURRENT full set. These tests fail on
 * the naive "save only the changed files" implementation.
 */
class SkillBundleMergeTest {

    private val current = mapOf(
        "SKILL.md" to "---\nname: demo\ndescription: d\n---\nbody",
        "references/a.md" to "A",
        "scripts/run.sh" to "echo hi",
    )

    // INVARIANT (the data-loss guard): files not mentioned in changes/deletes are preserved verbatim.
    @Test
    fun `adding a file preserves every existing file`() {
        val merged = mergeSkillBundle(current, changes = mapOf("references/b.md" to "B"), deletes = emptySet())!!
        assertEquals("A", merged["references/a.md"])
        assertEquals("echo hi", merged["scripts/run.sh"])
        assertEquals(current["SKILL.md"], merged["SKILL.md"])
        assertEquals("B", merged["references/b.md"])
        assertEquals(current.size + 1, merged.size)
    }

    @Test
    fun `a change overwrites only the named file`() {
        val merged = mergeSkillBundle(current, changes = mapOf("references/a.md" to "A2"), deletes = emptySet())!!
        assertEquals("A2", merged["references/a.md"])
        assertEquals("echo hi", merged["scripts/run.sh"])
        assertEquals(current.size, merged.size)
    }

    @Test
    fun `a delete removes only the named file and keeps the rest`() {
        val merged = mergeSkillBundle(current, changes = emptyMap(), deletes = setOf("scripts/run.sh"))!!
        assertTrue(!merged.containsKey("scripts/run.sh"))
        assertEquals("A", merged["references/a.md"])
        assertTrue(merged.containsKey("SKILL.md"))
    }

    // BOUNDARY: removing SKILL.md is refused (null) — a skill without it is invalid.
    @Test
    fun `deleting SKILL_md is refused`() {
        assertNull(mergeSkillBundle(current, changes = emptyMap(), deletes = setOf("SKILL.md")))
    }

    // delete then re-add SKILL.md in the same call is allowed (net result still has SKILL.md).
    @Test
    fun `replacing SKILL_md via changes is allowed even if also in deletes`() {
        val merged = mergeSkillBundle(
            current,
            changes = mapOf("SKILL.md" to "---\nname: demo\ndescription: d2\n---\nnew"),
            deletes = setOf("SKILL.md"),
        )!!
        assertEquals("---\nname: demo\ndescription: d2\n---\nnew", merged["SKILL.md"])
    }

    // METAMORPHIC: no-op merge (no changes, no deletes) returns the same set.
    @Test
    fun `empty change set is identity`() {
        assertEquals(current, mergeSkillBundle(current, emptyMap(), emptySet()))
    }

    // --- mergedSkillIsValid: the post-merge frontmatter gate for update_skill ---

    @Test
    fun `merged skill is valid when name matches and description is present`() {
        assertTrue(mergedSkillIsValid(current, "demo"))
    }

    // BOUNDARY: a rename via frontmatter would orphan the on-disk directory — must be rejected.
    @Test
    fun `merged skill is invalid when frontmatter renames the skill`() {
        val renamed = current + ("SKILL.md" to "---\nname: other\ndescription: d\n---\nbody")
        assertFalse(mergedSkillIsValid(renamed, "demo"))
    }

    @Test
    fun `merged skill is invalid when description is blanked`() {
        val blanked = current + ("SKILL.md" to "---\nname: demo\ndescription: \n---\nbody")
        assertFalse(mergedSkillIsValid(blanked, "demo"))
    }

    @Test
    fun `merged skill is invalid when name is missing`() {
        val noName = current + ("SKILL.md" to "---\ndescription: d\n---\nbody")
        assertFalse(mergedSkillIsValid(noName, "demo"))
    }

    @Test
    fun `merged skill is invalid when SKILL_md is absent`() {
        assertFalse(mergedSkillIsValid(mapOf("references/a.md" to "A"), "demo"))
    }
}
