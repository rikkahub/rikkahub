package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Assert.assertTrue

class SystemPromptBuilderTest {
    private val builder = SystemPromptBuilder()

    @Test
    fun `build orders stable sections (assistant, tools) before volatile (memory, chats, addendum)`() {
        val prompt = builder.build(
            assistantPrompt = "Assistant prompt",
            memoryPrompt = "**Memories**\nMemory block",
            recentChatsPrompt = "**Recent Chats**\nRecent block",
            toolPrompts = listOf("Tool A", "Tool B"),
            systemAddendum = "Surface addendum",
        )

        assertTrue(prompt.startsWith("Assistant prompt"))
        assertTrue(prompt.contains("Tool cost guidance: prefer low-cost text tools"))
        assertTrue(prompt.endsWith("Surface addendum"))

        // Stable-first: assistant + tools precede the volatile memory/recent-chats sections.
        assertTrue(prompt.indexOf("Assistant prompt") < prompt.indexOf("Tool cost guidance"))
        assertTrue(prompt.indexOf("Tool A") < prompt.indexOf("Tool B"))
        assertTrue(prompt.indexOf("Tool B") < prompt.indexOf("**Memories**"))
        assertTrue(prompt.indexOf("**Memories**") < prompt.indexOf("**Recent Chats**"))
        assertTrue(prompt.indexOf("**Recent Chats**") < prompt.indexOf("Surface addendum"))
    }

    @Test
    fun `buildSections puts assistant+tools in stable and memory+chats+addendum in volatile`() {
        val (stable, volatile) = builder.buildSections(
            assistantPrompt = "You are helpful.",
            memoryPrompt = "**Memories** m1",
            recentChatsPrompt = "**Recent Chats** c1",
            toolPrompts = listOf("tool_a docs"),
            systemAddendum = "telegram chat_id 5",
        )
        assertTrue(stable.contains("You are helpful."))
        assertTrue(stable.contains("tool_a docs"))
        assertTrue(stable.contains("Tool cost guidance"))
        assertFalse(stable.contains("Memories"))
        assertTrue(volatile.contains("**Memories** m1"))
        assertTrue(volatile.contains("**Recent Chats** c1"))
        assertTrue(volatile.contains("telegram chat_id 5"))
    }

    @Test
    fun `stable prefix is byte-identical when only volatile inputs change`() {
        fun stableFor(mem: String, chats: String) = builder.buildSections(
            assistantPrompt = "You are helpful.",
            memoryPrompt = mem,
            recentChatsPrompt = chats,
            toolPrompts = listOf("tool_a docs"),
            systemAddendum = null,
        ).first
        // Turn 1 vs turn 2: memory + recent chats differ; the cached stable prefix must not.
        assertEquals(stableFor("mem v1", "chats v1"), stableFor("mem v2 changed", "chats v2 changed"))
    }

    @Test
    fun `build equals stable joined with volatile`() {
        val (stable, volatile) = builder.buildSections(assistantPrompt = "A", memoryPrompt = "M")
        assertEquals(
            listOf(stable, volatile).filter { it.isNotBlank() }.joinToString("\n"),
            builder.build(assistantPrompt = "A", memoryPrompt = "M"),
        )
    }
}
