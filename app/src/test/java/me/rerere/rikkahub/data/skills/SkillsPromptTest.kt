package me.rerere.rikkahub.data.skills

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsPromptTest {
    @Test
    fun `parseSkillFrontmatter should extract name and description`() {
        val markdown = """
            ---
            name: find-hugeicons
            description: "Use this skill when the user asks for icons: search before coding"
            ---
            
            # Find HugeIcons
        """.trimIndent()

        val result = parseSkillFrontmatter(markdown)

        assertTrue(result is SkillFrontmatterParseResult.Success)
        val frontmatter = (result as SkillFrontmatterParseResult.Success).frontmatter
        assertEquals("find-hugeicons", frontmatter.name)
        assertEquals("Use this skill when the user asks for icons: search before coding", frontmatter.description)
    }

    @Test
    fun `parseSkillFrontmatter should reject missing description`() {
        val markdown = """
            ---
            name: find-hugeicons
            ---
        """.trimIndent()

        val result = parseSkillFrontmatter(markdown)

        assertTrue(result is SkillFrontmatterParseResult.Error)
        assertEquals(
            "SKILL.md frontmatter is missing description",
            (result as SkillFrontmatterParseResult.Error).reason,
        )
    }

    @Test
    fun `buildSkillsCatalogPrompt should include selected valid skills only`() {
        val assistant = Assistant(
            skillsEnabled = true,
            selectedSkills = setOf("find-hugeicons", "missing-skill"),
            localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.TermuxExec),
        )
        val model = Model(abilities = listOf(ModelAbility.TOOL))
        val catalog = SkillsCatalogState(
            workdir = "/data/data/com.termux/files/home",
            rootPath = "/data/data/com.termux/files/home/skills",
            entries = listOf(
                SkillCatalogEntry(
                    directoryName = "find-hugeicons",
                    path = "/data/data/com.termux/files/home/skills/find-hugeicons",
                    name = "find-hugeicons",
                    description = "Search the HugeIcons library before using an icon.",
                ),
                SkillCatalogEntry(
                    directoryName = "locale-tui-localization",
                    path = "/data/data/com.termux/files/home/skills/locale-tui-localization",
                    name = "locale-tui-localization",
                    description = "Use locale-tui for Android localization tasks.",
                ),
            ),
        )

        val prompt = buildSkillsCatalogPrompt(
            assistant = assistant,
            model = model,
            catalog = catalog,
        )

        assertNotNull(prompt)
        assertTrue(prompt!!.contains("Skills root: /data/data/com.termux/files/home/skills"))
        assertTrue(prompt.contains("find-hugeicons"))
        assertTrue(prompt.contains("Search the HugeIcons library before using an icon."))
        assertFalse(prompt.contains("locale-tui-localization"))
        assertFalse(prompt.contains("missing-skill"))
    }

    @Test
    fun `buildSkillsCatalogPrompt should be disabled when model cannot use tools`() {
        val assistant = Assistant(
            skillsEnabled = true,
            selectedSkills = setOf("find-hugeicons"),
            localTools = listOf(LocalToolOption.TermuxExec),
        )
        val model = Model(abilities = emptyList())
        val catalog = SkillsCatalogState(
            rootPath = "/data/data/com.termux/files/home/skills",
            entries = listOf(
                SkillCatalogEntry(
                    directoryName = "find-hugeicons",
                    path = "/data/data/com.termux/files/home/skills/find-hugeicons",
                    name = "find-hugeicons",
                    description = "Search the HugeIcons library before using an icon.",
                )
            ),
        )

        assertNull(buildSkillsCatalogPrompt(assistant = assistant, model = model, catalog = catalog))
        assertFalse(shouldInjectSkillsCatalog(assistant = assistant, model = model))
    }
}
