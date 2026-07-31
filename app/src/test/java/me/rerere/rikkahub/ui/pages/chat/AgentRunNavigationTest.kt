package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.SnackbarDuration
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.model.AgentRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunNavigationTest {
    @Test
    fun childNavigationIsReversibleAndRootNavigationReplacesHistory() {
        val root = AgentRunNavigation().openRoot("root")
        assertEquals("root", root.selectedRunId)
        assertFalse(root.canNavigateBack)
        assertEquals(1, root.navigationDepth)

        val child = root.openChild("child")
        assertEquals("child", child.selectedRunId)
        assertTrue(child.canNavigateBack)
        assertEquals(2, child.navigationDepth)
        assertEquals(root, child.back())

        val replacement = child.openRoot("replacement")
        assertEquals("replacement", replacement.selectedRunId)
        assertFalse(replacement.canNavigateBack)
        assertEquals(1, replacement.navigationDepth)
    }

    @Test
    fun duplicateAndAncestorNavigationCannotCreateCycles() {
        val child = AgentRunNavigation()
            .openRoot("root")
            .openChild("child")

        assertEquals(child, child.openChild("child"))
        assertEquals(AgentRunNavigation().openRoot("root"), child.openChild("root"))
        assertNull(child.close().selectedRunId)
    }

    @Test
    fun contentAnimationKeyChangesByPhaseAndIdentityButNotTelemetryPayload() {
        val first = AgentRunDetailState.Content(detail("run", updatedAt = 1))
        val telemetryUpdate = AgentRunDetailState.Content(detail("run", updatedAt = 2))

        assertEquals(first.animationKey(), telemetryUpdate.animationKey())
        assertEquals(first.animationKey(), telemetryUpdate.copy(canNavigateBack = true).animationKey())
        assertNotEquals(first.animationKey(), AgentRunDetailState.Loading("run").animationKey())
        assertNotEquals(first.animationKey(), AgentRunDetailState.Content(detail("other", 2)).animationKey())
    }

    @Test
    fun detailMotionKeepsSameRunPhaseChangesSubtle() {
        val loading = AgentRunDetailState.Loading("run", navigationDepth = 2)
        val content = AgentRunDetailState.Content(detail("run", updatedAt = 1), navigationDepth = 2)

        assertEquals(AgentRunDetailMotion.PHASE, agentRunDetailMotion(loading, content))
        assertEquals(AgentRunDetailMotion.PHASE, agentRunDetailMotion(content, loading))
    }

    @Test
    fun detailMotionTracksDeeperAndShallowerDestinations() {
        val root = AgentRunDetailState.Content(detail("root", updatedAt = 1), navigationDepth = 1)
        val child = AgentRunDetailState.Loading("child", canNavigateBack = true, navigationDepth = 2)

        assertEquals(AgentRunDetailMotion.FORWARD, agentRunDetailMotion(root, child))
        assertEquals(AgentRunDetailMotion.BACK, agentRunDetailMotion(child, root))
    }

    @Test
    fun detailMotionDoesNotInventHierarchyForSameDepthOrClosedStates() {
        val first = AgentRunDetailState.Loading("first", canNavigateBack = true, navigationDepth = 2)
        val second = AgentRunDetailState.Loading("second", canNavigateBack = true, navigationDepth = 2)

        assertEquals(AgentRunDetailMotion.PHASE, agentRunDetailMotion(first, second))
        assertEquals(AgentRunDetailMotion.PHASE, agentRunDetailMotion(AgentRunDetailState.Closed, first))
    }

    @Test
    fun systemBackNavigatesOnlyNestedDetailStates() {
        val root = AgentRunDetailState.Loading("root", navigationDepth = 1)
        val child = AgentRunDetailState.Loading("child", canNavigateBack = true, navigationDepth = 2)

        assertEquals(AgentRunDetailBackBehavior.DISMISS_SHEET, agentRunDetailBackBehavior(AgentRunDetailState.Closed))
        assertEquals(AgentRunDetailBackBehavior.DISMISS_SHEET, agentRunDetailBackBehavior(root))
        assertEquals(AgentRunDetailBackBehavior.NAVIGATE_PARENT, agentRunDetailBackBehavior(child))
    }

    @Test
    fun detailStopTargetsOnlyTheActiveRootIdentity() {
        assertEquals("root", detailStopTarget(selectedRunId = "root", activeRootRunId = "root"))
        assertNull(detailStopTarget(selectedRunId = "child", activeRootRunId = "root"))
        assertNull(detailStopTarget(selectedRunId = "stale", activeRootRunId = "replacement"))
        assertNull(detailStopTarget(selectedRunId = "root", activeRootRunId = null))
        assertNull(detailStopTarget(selectedRunId = null, activeRootRunId = "root"))
    }

    @Test
    fun approvalLocationFeedbackDistinguishesResults() {
        val located = approvalLocationFeedback(located = true)
        assertEquals(R.string.agent_run_approval_location_success, located.messageRes)
        assertEquals(SnackbarDuration.Short, located.duration)
        assertFalse(located.withDismissAction)

        val missing = approvalLocationFeedback(located = false)
        assertEquals(R.string.agent_run_approval_location_missing, missing.messageRes)
        assertEquals(SnackbarDuration.Long, missing.duration)
        assertTrue(missing.withDismissAction)
    }

    private fun detail(runId: String, updatedAt: Long) = AgentRunDetail(
        run = AgentRunEntity(
            id = runId,
            conversationId = "conversation",
            assistantId = "assistant",
            status = AgentRunStatus.RUNNING.name,
            configSnapshotJson = "{}",
            createdAt = 0,
            updatedAt = updatedAt,
        ),
        steps = emptyList(),
        tools = emptyList(),
        approvals = emptyList(),
    )
}
