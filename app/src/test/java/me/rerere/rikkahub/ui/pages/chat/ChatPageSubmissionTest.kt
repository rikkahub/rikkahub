package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPageSubmissionTest {
    @Test
    fun `draft submission should require model only for answered sends`() {
        assertTrue(
            draftSubmissionRequiresModel(
                isEditing = false,
                answer = true,
                isTermuxDirect = false,
            )
        )
        assertFalse(
            draftSubmissionRequiresModel(
                isEditing = true,
                answer = true,
                isTermuxDirect = false,
            )
        )
        assertFalse(
            draftSubmissionRequiresModel(
                isEditing = false,
                answer = false,
                isTermuxDirect = false,
            )
        )
        assertFalse(
            draftSubmissionRequiresModel(
                isEditing = false,
                answer = true,
                isTermuxDirect = true,
            )
        )
    }
}
