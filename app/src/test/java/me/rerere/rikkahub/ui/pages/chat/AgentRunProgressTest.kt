package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRunProgressTest {
    @Test
    fun onlyPendingAndWorkingStatesShowActiveProgress() {
        assertEquals(
            setOf(AgentRunVisualState.PENDING, AgentRunVisualState.WORKING),
            AgentRunVisualState.entries.filter(AgentRunVisualState::showsActiveProgress).toSet(),
        )
    }

    @Test
    fun missingOrInvalidStepBudgetsRemainIndeterminate() {
        assertNull(agentRunStepProgress(completedSteps = 2, maxSteps = null))
        assertNull(agentRunStepProgress(completedSteps = 2, maxSteps = 0))
        assertNull(agentRunStepProgress(completedSteps = 2, maxSteps = -4))
    }

    @Test
    fun validStepBudgetProducesNormalizedProgress() {
        assertEquals(0.25f, agentRunStepProgress(completedSteps = 1, maxSteps = 4))
        assertEquals(0.5f, agentRunStepProgress(completedSteps = 2, maxSteps = 4))
    }

    @Test
    fun inconsistentStepCountsAreClampedToTheProgressRange() {
        assertEquals(0f, agentRunStepProgress(completedSteps = -1, maxSteps = 4))
        assertEquals(1f, agentRunStepProgress(completedSteps = 7, maxSteps = 4))
    }
}
