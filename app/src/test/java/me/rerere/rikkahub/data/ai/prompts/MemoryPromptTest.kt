package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.data.model.MemoryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptTest {
    private val profile = MemoryFile(MemoryFile.PROFILE_PATH, "- [stated] Name is Lin\n", updatedAt = 0)
    private val food = MemoryFile(
        path = "/topics/food.md",
        content = """
            ---
            name: food
            description: What the user eats and avoids
            aliases: [diet, 饮食]
            ---

            - [stated] Allergic to peanuts
        """.trimIndent(),
        updatedAt = 0,
    )

    private fun String.block(tag: String) = substringAfter("<$tag").substringAfter(">").substringBefore("</$tag>").trim()

    @Test
    fun `profile is injected in full with its version and other files only appear in the listing`() {
        val prompt = buildMemoryPrompt(listOf(profile, food))

        assertTrue(prompt.contains("<profile version=\"${profile.version}\">"))
        assertEquals("- [stated] Name is Lin", prompt.block("profile"))

        val listing = prompt.block("memory_listing")
        assertTrue(listing.startsWith("/topics/food.md | What the user eats and avoids | aliases: diet, 饮食 | updated: "))
        assertFalse(listing.contains(MemoryFile.PROFILE_PATH))
        assertFalse(prompt.contains("Allergic to peanuts"))
    }

    @Test
    fun `missing files are shown as empty and creatable`() {
        val prompt = buildMemoryPrompt(emptyList())

        assertTrue(prompt.contains("<preferences version=\"new\">"))
        assertEquals("(empty)", prompt.block("preferences"))
        assertEquals("(no files yet)", prompt.block("memory_listing"))
    }

    @Test
    fun `consolidation input carries the memory state and each conversation's new turns`() {
        val input = buildMemoryConsolidationInput(
            files = listOf(profile, food),
            conversations = listOf(
                MemoryConsolidationConversation(
                    id = "c1",
                    title = "Moving <to> \"Hangzhou\"",
                    recentContext = "",
                    newTurns = "[USER #4]: I moved to Hangzhou",
                ),
            ),
        )

        assertEquals("- [stated] Name is Lin", input.block("profile"))
        assertTrue(input.block("memory_listing").startsWith("/topics/food.md"))
        // 标题里的引号和尖括号不能破坏标签
        assertTrue(input.contains("<conversation id=\"c1\" title=\"Moving  to   Hangzhou\">"))
        assertEquals("(none)", input.block("recent_context"))
        assertEquals("[USER #4]: I moved to Hangzhou", input.block("new_turns"))
    }

    @Test
    fun `consolidation input says so when there is nothing new`() {
        val input = buildMemoryConsolidationInput(files = listOf(profile), conversations = emptyList())

        assertTrue(input.endsWith("(no new conversation turns)"))
        assertFalse(input.contains("<conversation"))
    }

    @Test
    fun `whole-store tidying is only asked for in a full review`() {
        assertFalse(buildMemoryConsolidationPrompt(fullReview = false).contains("## Full review"))
        assertTrue(buildMemoryConsolidationPrompt(fullReview = true).contains("## Full review"))
    }
}
