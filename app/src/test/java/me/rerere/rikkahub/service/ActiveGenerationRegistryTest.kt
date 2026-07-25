package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveGenerationRegistryTest {
    @Test
    fun `first active generation starts foreground lifetime and last completion stops it`() {
        val registry = ActiveGenerationRegistry()

        assertTrue(registry.add("conversation-1"))
        assertFalse(registry.add("conversation-1"))
        assertEquals(1, registry.size)

        assertFalse(registry.remove("missing-conversation"))
        assertTrue(registry.remove("conversation-1"))
        assertEquals(0, registry.size)
    }

    @Test
    fun `foreground lifetime remains active while another generation is running`() {
        val registry = ActiveGenerationRegistry()

        assertTrue(registry.add("conversation-1"))
        assertFalse(registry.add("conversation-2"))

        assertFalse(registry.remove("conversation-1"))
        assertEquals(1, registry.size)
        assertTrue(registry.remove("conversation-2"))
    }
}
