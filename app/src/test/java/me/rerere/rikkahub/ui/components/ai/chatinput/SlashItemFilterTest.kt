package me.rerere.rikkahub.ui.components.ai.chatinput

import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM unit tests for [filterSlashItems] (#364 slice 4): the PURE filter that drives the "/" picker. The
 * invariants pinned: the reserved native commands (`/goal`, `/loop`) are ALWAYS offered (even with zero
 * skills) and lead the list, the query substring-matches name OR description, and skills follow.
 */
class SlashItemFilterTest {

    private fun skill(name: String, description: String = "") =
        SkillMetadata(name = name, description = description, skillDir = File(name))

    @Test
    fun `built-in goal and loop are always present and lead, even with no skills`() {
        val items = filterSlashItems(query = "", skills = emptyList())
        assertEquals(
            listOf("goal", "loop"),
            items.filterIsInstance<SlashItem.Builtin>().map { it.name },
        )
        // Built-ins come first.
        assertTrue(items.first() is SlashItem.Builtin)
    }

    @Test
    fun `empty query shows built-ins then all skills in order`() {
        val skills = listOf(skill("tts"), skill("web-search"))
        val items = filterSlashItems(query = "", skills = skills)
        assertEquals(listOf("goal", "loop", "tts", "web-search"), items.map { it.name })
    }

    @Test
    fun `a blank-ish query is treated as empty`() {
        val items = filterSlashItems(query = "   ", skills = listOf(skill("tts")))
        assertEquals(listOf("goal", "loop", "tts"), items.map { it.name })
    }

    @Test
    fun `goal is discoverable by prefix`() {
        val names = filterSlashItems(query = "go", skills = emptyList()).map { it.name }
        assertEquals(listOf("goal"), names)
    }

    @Test
    fun `loop is discoverable by prefix`() {
        val names = filterSlashItems(query = "lo", skills = emptyList()).map { it.name }
        assertEquals(listOf("loop"), names)
    }

    @Test
    fun `query matches a skill by name without matching the built-ins`() {
        val items = filterSlashItems(query = "tts", skills = listOf(skill("tts"), skill("web-search")))
        assertEquals(listOf("tts"), items.map { it.name })
        assertTrue(items.single() is SlashItem.Skill)
    }

    @Test
    fun `query matches on description substring too`() {
        val items = filterSlashItems(
            query = "schedule",
            skills = listOf(skill("tts", description = "speak text")),
        )
        // "loop" matches via its description ("...durable schedule..."); the skill does not.
        assertEquals(listOf("loop"), items.map { it.name })
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(listOf("goal"), filterSlashItems(query = "GOAL", skills = emptyList()).map { it.name })
    }

    @Test
    fun `keys are type-prefixed so a same-named skill never collides with a built-in`() {
        val items = filterSlashItems(query = "", skills = listOf(skill("goal")))
        val keys = items.filter { it.name == "goal" }.map { it.key }
        assertEquals(listOf("builtin:goal", "skill:goal"), keys)
        assertEquals("two distinct rows named goal", 2, keys.size)
    }
}
