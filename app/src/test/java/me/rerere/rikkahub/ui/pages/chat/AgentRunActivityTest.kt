package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRunActivityTest {
    @Test
    fun stoppingFeedbackHasHighestPriority() {
        assertEquals(
            "Stopping",
            activityText(
                visualState = AgentRunVisualState.WORKING,
                waitingReason = "Waiting for approval",
                currentStep = "Reading workspace",
                isStopping = true,
            ),
        )
    }

    @Test
    fun liveActivityUsesWaitingReasonThenCurrentStepThenFallback() {
        assertEquals(
            "Waiting for approval",
            activityText(
                visualState = AgentRunVisualState.NEEDS_ATTENTION,
                waitingReason = "Waiting for approval",
                currentStep = "Reading workspace",
            ),
        )
        assertEquals(
            "Reading workspace",
            activityText(
                visualState = AgentRunVisualState.WORKING,
                waitingReason = "  ",
                currentStep = "Reading workspace",
            ),
        )
        assertEquals(
            "Waiting for telemetry",
            activityText(
                visualState = AgentRunVisualState.PENDING,
                waitingReason = null,
                currentStep = null,
            ),
        )
    }

    @Test
    fun successfulTerminalStateSuppressesStaleLiveFields() {
        assertNull(
            activityText(
                visualState = AgentRunVisualState.SUCCEEDED,
                waitingReason = "Old waiting reason",
                currentStep = "Old current step",
            )
        )
    }

    @Test
    fun failedTerminalStateShowsOnlyFailureGuidance() {
        assertEquals(
            "Run failed; retry from chat",
            activityText(
                visualState = AgentRunVisualState.FAILED,
                waitingReason = "Old waiting reason",
                currentStep = "Old current step",
                statusDescription = "Run failed; retry from chat",
            ),
        )
    }

    private fun activityText(
        visualState: AgentRunVisualState,
        waitingReason: String?,
        currentStep: String?,
        statusDescription: String? = null,
        isStopping: Boolean = false,
    ): String? = agentRunActivityText(
        visualState = visualState,
        waitingReason = waitingReason,
        currentStep = currentStep,
        statusDescription = statusDescription,
        isStopping = isStopping,
        stoppingLabel = "Stopping",
        waitingTelemetryLabel = "Waiting for telemetry",
    )
}
