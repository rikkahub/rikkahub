package me.rerere.rikkahub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteActivityIntentRoutingTest {

    @Test
    fun `cold-start and new-intent use the same conversation-id extra parser`() {
        val conversationId = "11111111-1111-1111-1111-111111111111"

        val coldStartParsed = resolveConversationIdFromExtra(conversationId)
        val onNewIntentParsed = resolveConversationIdFromExtra(conversationId)

        assertEquals(coldStartParsed, onNewIntentParsed)
        assertEquals(conversationId, coldStartParsed)
    }

    @Test
    fun `missing conversation-id extra results in null route target`() {
        assertNull(resolveConversationIdFromExtra(null))
    }
}
