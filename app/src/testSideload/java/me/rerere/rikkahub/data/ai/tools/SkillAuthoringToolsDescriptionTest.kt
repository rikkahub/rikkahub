package me.rerere.rikkahub.data.ai.tools

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
}
