package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sideload-only regression coverage for the model-facing skill authoring tool descriptions. The Play
 * flavor has no create_skill/update_skill write surface, so these assertions live in testSideload.
 */
class SkillAuthoringToolsDescriptionTest {

    @Test
    fun `create_skill description clarifies bundle shape and overwrite boundary`() {
        assertTrue(CREATE_SKILL_TOOL_DESCRIPTION.contains("complete text file bundle"))
        assertTrue(CREATE_SKILL_TOOL_DESCRIPTION.contains("skill-root-relative paths"))
        assertTrue(CREATE_SKILL_TOOL_DESCRIPTION.contains("not a diff/patch format"))
        assertTrue(CREATE_SKILL_TOOL_DESCRIPTION.contains("MUST include \"SKILL.md\""))
        assertTrue(CREATE_SKILL_TOOL_DESCRIPTION.contains("Creation refuses an existing skill name"))
        assertTrue(CREATE_SKILL_TOOL_DESCRIPTION.contains("NOT auto-enabled"))
    }

    @Test
    fun `update_skill description clarifies full replacements and preservation`() {
        assertTrue(UPDATE_SKILL_TOOL_DESCRIPTION.contains("full-file writes/deletes"))
        assertTrue(UPDATE_SKILL_TOOL_DESCRIPTION.contains("complete replacement text"))
        assertTrue(UPDATE_SKILL_TOOL_DESCRIPTION.contains("not a diff/patch format"))
        assertTrue(UPDATE_SKILL_TOOL_DESCRIPTION.contains("do NOT mention are preserved"))
        assertTrue(UPDATE_SKILL_TOOL_DESCRIPTION.contains("cannot delete SKILL.md or rename"))
        assertTrue(UPDATE_SKILL_TOOL_DESCRIPTION.contains("complete SKILL.md with YAML frontmatter"))
    }

    @Test
    fun `requireSkillMd returns the exact SKILL_md entry when present`() {
        val content = "---\nname: foo\ndescription: bar\n---\nbody"
        val files = mapOf("SKILL.md" to content, "scripts/run.sh" to "echo hi")

        assertEquals(content, requireSkillMd(files))
    }

    // The real failure that motivated the guiding error: the model keyed the frontmatter-bearing file
    // as "Subagent_Guide" and a stub as "SKILL_md", so the exact "SKILL.md" lookup correctly missed.
    // The error must echo the keys it sent AND point at the SKILL.md body so it can re-key + retry.
    @Test
    fun `requireSkillMd points at the SKILL_md body when SKILL_md key is a near miss`() {
        val files = mapOf(
            "Subagent_Guide" to "---\nname: instagram-explorer\ndescription: explore IG\n---\nbody",
            "SKILL_md" to "# Instagram Explorer Skill\nUse this guide",
        )

        val error = assertThrows(IllegalStateException::class.java) { requireSkillMd(files) }
        val message = error.message ?: ""
        assertTrue("echoes the provided keys", message.contains("Subagent_Guide"))
        assertTrue("echoes the provided keys", message.contains("SKILL_md"))
        assertTrue("names the SKILL.md body file", message.contains("\"Subagent_Guide\" file already has SKILL.md frontmatter"))
    }

    // A helper file that legitimately opens with "---" (a horizontal rule, or unrelated YAML without a
    // name+description) must NOT be mistaken for the skill body — the hint must point at the real SKILL.md
    // body, not the decoy, so the model re-keys the correct file.
    @Test
    fun `requireSkillMd ignores a decoy that opens with dashes but lacks name and description`() {
        val files = mapOf(
            "notes.md" to "---\nsome: yaml\n---\njust notes",
            "guide" to "---\nname: foo\ndescription: bar\n---\nthe real body",
        )

        val error = assertThrows(IllegalStateException::class.java) { requireSkillMd(files) }
        val message = error.message ?: ""
        assertTrue("points at the valid SKILL.md body", message.contains("\"guide\" file already has SKILL.md frontmatter"))
        assertFalse("does not point at the decoy", message.contains("\"notes.md\" file already has"))
    }

    @Test
    fun `requireSkillMd echoes keys without a hint when no file is a valid skill body`() {
        val files = mapOf(
            "readme.txt" to "just some text",
            "scripts/run.sh" to "echo hi",
        )

        val error = assertThrows(IllegalStateException::class.java) { requireSkillMd(files) }
        val message = error.message ?: ""
        assertTrue("echoes the provided keys", message.contains("readme.txt"))
        assertTrue("echoes the provided keys", message.contains("scripts/run.sh"))
        assertFalse("offers no re-key hint when nothing is a valid skill body", message.contains("already has SKILL.md frontmatter"))
    }
}
