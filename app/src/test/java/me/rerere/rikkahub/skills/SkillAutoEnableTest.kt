package me.rerere.rikkahub.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 16 audit fix — covers [decideAutoEnable], the pure decision behind the auto-enable
 * side effect that rides along with a successful `skill_install_from_*` call. The DataStore
 * write itself ([applyAutoEnable]) is exercised on-device; this nails down the branching.
 */
class SkillAutoEnableTest {

    @Test
    fun `new skill - auto-enabled and appended to the enabled set`() {
        val outcome = decideAutoEnable(
            enabledSkills = setOf("agent-core"),
            skillName = "zip-and-send",
            existedBefore = false,
        )

        assertTrue(outcome.autoEnabled)
        assertEquals(setOf("agent-core", "zip-and-send"), outcome.updatedEnabledSkills)
        assertTrue(outcome.detail.contains("now enabled"))
    }

    @Test
    fun `re-install of an already-enabled skill - stays enabled, no write needed`() {
        val outcome = decideAutoEnable(
            enabledSkills = setOf("agent-core", "zip-and-send"),
            skillName = "zip-and-send",
            existedBefore = true,
        )

        assertTrue(outcome.autoEnabled)
        assertNull(outcome.updatedEnabledSkills)
        assertTrue(outcome.detail.contains("already enabled"))
    }

    @Test
    fun `re-install of a previously-disabled skill - stays disabled`() {
        // Skill exists on disk but is NOT in the enabled set: the user explicitly
        // disabled it. A content update must not silently flip it back on.
        val outcome = decideAutoEnable(
            enabledSkills = setOf("agent-core"),
            skillName = "zip-and-send",
            existedBefore = true,
        )

        assertFalse(outcome.autoEnabled)
        assertNull(outcome.updatedEnabledSkills)
        assertTrue(outcome.detail.contains("previously disabled"))
    }

    @Test
    fun `new skill into an empty enabled set`() {
        val outcome = decideAutoEnable(
            enabledSkills = emptySet(),
            skillName = "morning-briefing",
            existedBefore = false,
        )

        assertTrue(outcome.autoEnabled)
        assertEquals(setOf("morning-briefing"), outcome.updatedEnabledSkills)
    }

    @Test
    fun `already-enabled skill that was somehow not on disk before - still a no-op`() {
        // Defensive: enabled-set membership wins over the existedBefore flag.
        val outcome = decideAutoEnable(
            enabledSkills = setOf("zip-and-send"),
            skillName = "zip-and-send",
            existedBefore = false,
        )

        assertTrue(outcome.autoEnabled)
        assertNull(outcome.updatedEnabledSkills)
    }
}
