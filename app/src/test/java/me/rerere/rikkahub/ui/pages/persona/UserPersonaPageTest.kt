package me.rerere.rikkahub.ui.pages.persona

import kotlin.uuid.Uuid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPersonaPageTest {

    @Test
    fun `discardDeletedProfileDraftIfNeeded should discard matching draft`() {
        val profileId = Uuid.random()
        var discarded = false

        val result = discardDeletedProfileDraftIfNeeded(
            profileId = profileId,
            editorDraftId = profileId,
        ) {
            discarded = true
        }

        assertTrue(result)
        assertTrue(discarded)
    }

    @Test
    fun `discardDeletedProfileDraftIfNeeded should keep non matching draft`() {
        var discarded = false

        val result = discardDeletedProfileDraftIfNeeded(
            profileId = Uuid.random(),
            editorDraftId = Uuid.random(),
        ) {
            discarded = true
        }

        assertFalse(result)
        assertFalse(discarded)
    }
}
