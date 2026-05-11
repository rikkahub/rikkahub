package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptBuilderTest {
    @Test
    fun `build preserves section ordering`() {
        val builder = SystemPromptBuilder()

        val prompt = builder.build(
            assistantPrompt = "Assistant prompt",
            memoryPrompt = "**Memories**\nMemory block",
            recentChatsPrompt = "**Recent Chats**\nRecent block",
            toolPrompts = listOf("Tool A", "Tool B"),
            systemAddendum = "Surface addendum",
        )

        assertTrue(prompt.startsWith("Assistant prompt"))
        assertTrue(prompt.contains("**Memories**"))
        assertTrue(prompt.contains("**Recent Chats**"))
        assertTrue(prompt.contains("Tool cost guidance: prefer low-cost text tools"))
        assertTrue(prompt.contains("Tool A"))
        assertTrue(prompt.contains("Tool B"))
        assertTrue(prompt.endsWith("Surface addendum"))

        assertTrue(prompt.indexOf("Assistant prompt") < prompt.indexOf("**Memories**"))
        assertTrue(prompt.indexOf("**Memories**") < prompt.indexOf("**Recent Chats**"))
        assertTrue(prompt.indexOf("**Recent Chats**") < prompt.indexOf("Tool cost guidance: prefer low-cost text tools"))
        assertTrue(prompt.indexOf("Tool A") < prompt.indexOf("Tool B"))
        assertTrue(prompt.indexOf("Tool B") < prompt.indexOf("Surface addendum"))
    }
}
