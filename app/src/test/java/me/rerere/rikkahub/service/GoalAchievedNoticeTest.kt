package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.isSyntheticAgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "✓ Goal achieved" notice ([buildGoalAchievedNotice], #364 follow-up) appended when a `/goal` is
 * judged met — the loop previously cleared the goal and stopped silently. Pins the visible-feedback
 * shape so the completion signal cannot regress to silence.
 */
class GoalAchievedNoticeTest {

    @Test
    fun `the notice is a visible assistant message carrying the verbatim goal`() {
        val notice = buildGoalAchievedNotice("ping 1.1.1.1 successfully")
        assertEquals("must render as a visible assistant bubble", MessageRole.ASSISTANT, notice.role)
        val text = (notice.parts.single() as UIMessagePart.Text).text
        assertTrue("must signal achievement", text.contains("Goal achieved"))
        assertTrue("must echo the exact goal condition", text.contains("ping 1.1.1.1 successfully"))
    }

    @Test
    fun `the notice is NOT a synthetic agent-event marker`() {
        // It deliberately does not carry the agent-event synthetic marker (that is event-id-keyed for
        // the #290 drain). So it stays an ordinary, honest transcript message — not a half-marked node.
        assertFalse(buildGoalAchievedNotice("done").isSyntheticAgentEvent())
    }
}
