package me.rerere.rikkahub.data.ai.slash

import me.rerere.rikkahub.data.ai.tools.SKILL_AUTHORING_SUPPORTED
import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM unit tests for the unified "/" slash registry (#364, Part B slice 1): the PURE filter that drives
 * the picker and the PURE resolver that drives the send-time dispatch. The flavor-gated skill-authoring
 * commands (`/create_skill`, `/update_skill`) are present only where the write surface is
 * ([SKILL_AUTHORING_SUPPORTED] — sideload yes, play no), so the filter expectations are built off that
 * flag (correct under BOTH testSideloadDebugUnitTest and testPlayDebugUnitTest), while the resolver's
 * gating is asserted against explicit booleans so those cases are flavor-independent.
 */
class SlashCommandRegistryTest {

    private fun skill(name: String, description: String = "") =
        SkillMetadata(name = name, description = description, skillDir = File(name))

    private val reserved = reservedSlashCommands(SKILL_AUTHORING_SUPPORTED)

    private val expectedBuiltins: List<String> =
        if (SKILL_AUTHORING_SUPPORTED) listOf("goal", "loop", "create_skill", "update_skill")
        else listOf("goal", "loop")

    // ---- filterSlashCommands (the picker) ----

    @Test
    fun `built-in commands always lead, even with no skills`() {
        val items = filterSlashCommands(query = "", reserved = reserved, skills = emptyList())
        assertEquals(expectedBuiltins, items.filterIsInstance<SlashCommand.Reserved>().map { it.name })
        assertTrue(items.first() is SlashCommand.Reserved)
    }

    @Test
    fun `empty query shows built-ins then all skills in order`() {
        val items = filterSlashCommands("", reserved, listOf(skill("tts"), skill("web-search")))
        assertEquals(expectedBuiltins + listOf("tts", "web-search"), items.map { it.name })
    }

    @Test
    fun `a blank-ish query is treated as empty`() {
        val items = filterSlashCommands("   ", reserved, listOf(skill("tts")))
        assertEquals(expectedBuiltins + listOf("tts"), items.map { it.name })
    }

    @Test
    fun `goal and loop are discoverable by prefix`() {
        assertEquals(listOf("goal"), filterSlashCommands("go", reserved, emptyList()).map { it.name })
        assertEquals(listOf("loop"), filterSlashCommands("lo", reserved, emptyList()).map { it.name })
    }

    @Test
    fun `query matches a skill by name without matching the built-ins`() {
        val items = filterSlashCommands("tts", reserved, listOf(skill("tts"), skill("web-search")))
        assertEquals(listOf("tts"), items.map { it.name })
        assertTrue(items.single() is SlashCommand.Skill)
    }

    @Test
    fun `query matches on description substring too`() {
        val items = filterSlashCommands("schedule", reserved, listOf(skill("tts", "speak text")))
        // "loop" matches via its description ("...durable schedule..."); the skill does not.
        assertEquals(listOf("loop"), items.map { it.name })
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(listOf("goal"), filterSlashCommands("GOAL", reserved, emptyList()).map { it.name })
    }

    @Test
    fun `keys are type-prefixed so a same-named skill never collides with a built-in`() {
        val items = filterSlashCommands("", reserved, listOf(skill("goal")))
        val keys = items.filter { it.name == "goal" }.map { it.key }
        assertEquals(listOf("builtin:goal", "skill:goal"), keys)
    }

    // ---- resolveSlashCommand (the send-time dispatch) ----

    private val sideloadReserved = reservedSlashCommands(skillAuthoringSupported = true)
    private val playReserved = reservedSlashCommands(skillAuthoringSupported = false)

    @Test
    fun `goal resolves to a reserved invocation carrying its arg`() {
        assertEquals(
            SlashInvocation.Reserved(ReservedCommand.Goal, "ship the feature"),
            resolveSlashCommand("/goal ship the feature", sideloadReserved, emptyList()),
        )
        assertEquals(
            SlashInvocation.Reserved(ReservedCommand.Goal, ""),
            resolveSlashCommand("/goal", sideloadReserved, emptyList()),
        )
        assertEquals(
            SlashInvocation.Reserved(ReservedCommand.Goal, "clear"),
            resolveSlashCommand("/goal clear", sideloadReserved, emptyList()),
        )
    }

    @Test
    fun `reserved matching trims surrounding whitespace`() {
        assertEquals(
            SlashInvocation.Reserved(ReservedCommand.Loop, "5m build"),
            resolveSlashCommand("  /loop 5m build  ", sideloadReserved, emptyList()),
        )
    }

    @Test
    fun `a reserved command wins over a same-named enabled skill`() {
        // A reserved command performs a guaranteed side effect, so it must be resolved before any skill.
        assertEquals(
            SlashInvocation.Reserved(ReservedCommand.Goal, "x"),
            resolveSlashCommand("/goal x", sideloadReserved, enabledSkills = listOf("goal")),
        )
    }

    @Test
    fun `a skill resolves by longest enabled-name prefix (names with spaces)`() {
        assertEquals(
            SlashInvocation.Skill("web search", "find cats"),
            resolveSlashCommand("/web search find cats", sideloadReserved, listOf("web", "web search")),
        )
    }

    @Test
    fun `a leading slash that matches no enabled skill resolves to null (sent verbatim)`() {
        assertNull(resolveSlashCommand("/unknown thing", sideloadReserved, emptyList()))
    }

    @Test
    fun `plain text is not a slash command`() {
        assertNull(resolveSlashCommand("hello world", sideloadReserved, listOf("web")))
    }

    @Test
    fun `skill-authoring is gated by the reserved set (play does not intercept it)`() {
        assertEquals(
            SlashInvocation.Reserved(ReservedCommand.CreateSkill, "a notes skill"),
            resolveSlashCommand("/create_skill a notes skill", sideloadReserved, emptyList()),
        )
        // In play the reserved set omits it, so a manually-typed /create_skill is NOT a reserved command;
        // with no enabled skill behind it, it falls through to a verbatim send (null).
        assertNull(resolveSlashCommand("/create_skill a notes skill", playReserved, emptyList()))
    }

    @Test
    fun `buildUseSkillDirective formats with and without a param`() {
        assertEquals("Use the \"web\" skill.\n\nfind cats", buildUseSkillDirective("web", "find cats"))
        assertEquals("Use the \"web\" skill.", buildUseSkillDirective("web", ""))
    }
}
