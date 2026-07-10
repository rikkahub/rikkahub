package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImagePreparationTest {
    @Test
    fun `image preparation starts only while idle`() {
        assertTrue(canStartImagePreparation(isPreparingImage = false))
        assertFalse(canStartImagePreparation(isPreparingImage = true))
    }
}
