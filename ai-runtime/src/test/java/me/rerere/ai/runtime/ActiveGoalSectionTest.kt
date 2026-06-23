package me.rerere.ai.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The active-goal system-prompt section (the app's `/goal`). Mirrors [UntrustedToolContentClauseTest]:
 * the section is a pure function so its rendering + presence gating are unit-testable without driving a
 * full turn. The placement (an ADDITIVE section, NOT the either/or conversation/assistant slot) is what
 * makes it independent of `allowConversationSystemPrompt`; that placement lives in
 * `buildInternalSystemPrompt` and is verified by code, while the content is pinned here so the directive
 * cannot be silently softened and the absence case cannot regress.
 */
class ActiveGoalSectionTest {

    @Test
    fun `no section without an armed goal`() {
        assertEquals("a null goal renders nothing", "", activeGoalSection(null))
        assertEquals("a blank goal renders nothing", "", activeGoalSection("   "))
        assertEquals("an empty goal renders nothing", "", activeGoalSection(""))
    }

    @Test
    fun `an armed goal renders a section carrying the verbatim condition`() {
        val section = activeGoalSection("make the build green")
        assertTrue("must be the goal section", section.contains("# Active goal"))
        assertTrue("must embed the exact condition", section.contains("\"make the build green\""))
    }

    @Test
    fun `the section instructs the model to pursue and to report the goal`() {
        val section = activeGoalSection("ship the release")
        assertTrue("must tell the model to keep working toward it", section.contains("Keep working toward it"))
        // The fix for the "what is your goal?" non-answer: the model must be told to report THIS goal.
        assertTrue("must tell the model to report the goal when asked", section.contains("report THIS goal"))
    }
}
