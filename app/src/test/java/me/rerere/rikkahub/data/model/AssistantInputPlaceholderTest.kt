package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantInputPlaceholderTest {

    private val defaultPlaceholder = "Type a message"

    @Test
    fun `disabled custom placeholder falls back to default regardless of saved text`() {
        val assistant = Assistant(
            enableCustomInputPlaceholder = false,
            inputPlaceholder = "custom text",
        )
        assertEquals(defaultPlaceholder, assistant.resolveInputPlaceholder(defaultPlaceholder))
    }

    @Test
    fun `enabled custom placeholder with normal text returns saved text`() {
        val assistant = Assistant(
            enableCustomInputPlaceholder = true,
            inputPlaceholder = "custom text",
        )
        assertEquals("custom text", assistant.resolveInputPlaceholder(defaultPlaceholder))
    }

    @Test
    fun `enabled custom placeholder with empty string falls back to default`() {
        val assistant = Assistant(
            enableCustomInputPlaceholder = true,
            inputPlaceholder = "",
        )
        assertEquals(defaultPlaceholder, assistant.resolveInputPlaceholder(defaultPlaceholder))
    }

    @Test
    fun `enabled custom placeholder with three spaces preserves whitespace exactly`() {
        val assistant = Assistant(
            enableCustomInputPlaceholder = true,
            inputPlaceholder = "   ",
        )
        assertEquals("   ", assistant.resolveInputPlaceholder(defaultPlaceholder))
    }
}
