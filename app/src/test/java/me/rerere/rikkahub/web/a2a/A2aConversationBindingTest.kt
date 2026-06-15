package me.rerere.rikkahub.web.a2a

import me.rerere.rikkahub.service.isA2aConversationBindingAllowed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class A2aConversationBindingTest {

    @Test
    fun `new context can bind to requested assistant`() {
        assertTrue(isA2aConversationBindingAllowed(null, Uuid.random()))
    }

    @Test
    fun `existing context can reuse same assistant`() {
        val assistantId = Uuid.random()
        assertTrue(isA2aConversationBindingAllowed(assistantId, assistantId))
    }

    @Test
    fun `existing context rejects different assistant`() {
        assertFalse(isA2aConversationBindingAllowed(Uuid.random(), Uuid.random()))
    }
}
