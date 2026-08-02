package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.ToolApprovalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserAnswerDraftTest {
    @Test
    fun textAndSingleAnswersUseImmutableCopies() {
        val initial = AskUserAnswerDraft()
        val updated = initial.withAnswer("question", "answer")

        assertTrue(initial.answers.isEmpty())
        assertEquals("answer", updated.answers["question"])
        assertEquals("replacement", updated.withAnswer("question", "replacement").answers["question"])
    }

    @Test
    fun multiAnswerToggleAddsAndRemovesExactOptions() {
        val selected = AskUserAnswerDraft()
            .toggleMultiAnswer("question", "alpha")
            .toggleMultiAnswer("question", "beta")
        val removed = selected.toggleMultiAnswer("question", "alpha")

        assertEquals(setOf("alpha", "beta"), selected.multiAnswers["question"])
        assertEquals(setOf("beta"), removed.multiAnswers["question"])
        assertFalse(removed.multiAnswers.getValue("question").contains("alpha"))
    }

    @Test
    fun codecRoundTripPreservesSpecialCharactersAndSets() {
        val draft = AskUserAnswerDraft(
            answers = mapOf("text" to "commas, quotes \" and 新行\n内容"),
            multiAnswers = mapOf("multi" to linkedSetOf("one, two", "three|four")),
        )

        val restored = decodeAskUserAnswerDraft(encodeAskUserAnswerDraft(draft))

        assertEquals(draft, restored)
    }

    @Test
    fun corruptedSavedDraftFallsBackToEmpty() {
        assertEquals(AskUserAnswerDraft(), decodeAskUserAnswerDraft("{not-json"))
    }

    @Test
    fun oversizedDraftIsNeverWrittenToSavedState() {
        val oversized = AskUserAnswerDraft(
            answers = mapOf("text" to "答".repeat(MAX_SAVED_ASK_USER_DRAFT_BYTES / 3)),
        )

        assertNull(encodeAskUserAnswerDraftForSave(oversized))
        assertTrue(encodeAskUserAnswerDraftForSave(AskUserAnswerDraft())?.isNotEmpty() == true)
    }

    @Test
    fun responseModeOnlyExposesEditorForActionablePendingAnswer() {
        assertEquals(
            AskUserResponseMode.Editing,
            askUserResponseMode(ToolApprovalState.Pending, hasAnswerHandler = true),
        )
        assertEquals(
            AskUserResponseMode.ReadOnly,
            askUserResponseMode(ToolApprovalState.Pending, hasAnswerHandler = false),
        )
        assertEquals(
            AskUserResponseMode.Answered,
            askUserResponseMode(ToolApprovalState.Answered("saved"), hasAnswerHandler = false),
        )
        assertEquals(
            AskUserResponseMode.ReadOnly,
            askUserResponseMode(ToolApprovalState.Approved, hasAnswerHandler = true),
        )
    }
}
