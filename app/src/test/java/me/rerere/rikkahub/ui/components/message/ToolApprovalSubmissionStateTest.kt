package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalSubmissionStateTest {
    @Test
    fun submissionIsSingleFlightAndResettable() {
        val state = ToolApprovalSubmissionState()

        assertTrue(state.tryStart())
        assertTrue(state.isSubmitting)
        assertFalse(state.tryStart())

        state.finish()

        assertFalse(state.isSubmitting)
        assertTrue(state.tryStart())
    }

    @Test
    fun approvalStatusFeedbackSignalsOnlyNewVisibleErrors() {
        val state = ApprovalStatusFeedbackState(initialMessage = "historical")

        assertFalse(state.update("historical"))
        assertTrue(state.update("renewed"))
        assertFalse(state.update("renewed"))
        assertFalse(state.update(null))
        assertTrue(state.update("later error"))
    }
}
