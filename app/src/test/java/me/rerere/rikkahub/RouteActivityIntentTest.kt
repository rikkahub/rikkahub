package me.rerere.rikkahub

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteActivityIntentTest {
    @Test
    fun `conversation intent replaces current route with chat route`() {
        val conversationId = "0e822879-5558-45c9-b3dd-8637db28ce17"
        val backStack = mutableListOf<NavKey>(Screen.VoiceAgent(conversationId))

        val handled = backStack.openConversationIntent(conversationId)

        assertTrue(handled)
        assertEquals(listOf(Screen.Chat(id = conversationId)), backStack)
    }

    @Test
    fun `conversation intent ignores invalid ids`() {
        val backStack = mutableListOf<NavKey>(Screen.Chat(id = "0e822879-5558-45c9-b3dd-8637db28ce17"))

        val handled = backStack.openConversationIntent("not-a-uuid")

        assertFalse(handled)
        assertEquals(listOf(Screen.Chat(id = "0e822879-5558-45c9-b3dd-8637db28ce17")), backStack)
    }

    @Test
    fun `voice agent intent replaces current route with voice agent route`() {
        val conversationId = "0e822879-5558-45c9-b3dd-8637db28ce17"
        val backStack = mutableListOf<NavKey>(Screen.Chat(id = conversationId))

        val handled = backStack.openVoiceAgentIntent(conversationId)

        assertTrue(handled)
        assertEquals(listOf(Screen.VoiceAgent(conversationId = conversationId)), backStack)
    }

    @Test
    fun `incoming intent falls back to chat when only conversation id is present`() {
        val conversationId = "0e822879-5558-45c9-b3dd-8637db28ce17"
        val backStack = mutableListOf<NavKey>(Screen.VoiceAgent(conversationId))

        val handled = backStack.openIncomingIntent(
            voiceConversationId = null,
            conversationId = conversationId,
        )

        assertTrue(handled)
        assertEquals(listOf(Screen.Chat(id = conversationId)), backStack)
    }

    @Test
    fun `incoming intent opens voice agent when only voice conversation id is present`() {
        val voiceConversationId = "0e822879-5558-45c9-b3dd-8637db28ce17"
        val backStack = mutableListOf<NavKey>(Screen.Chat(id = voiceConversationId))

        val handled = backStack.openIncomingIntent(
            voiceConversationId = voiceConversationId,
            conversationId = null,
        )

        assertTrue(handled)
        assertEquals(listOf(Screen.VoiceAgent(conversationId = voiceConversationId)), backStack)
    }

    @Test
    fun `incoming intent prioritizes voice agent when both conversation ids are present`() {
        val voiceConversationId = "0e822879-5558-45c9-b3dd-8637db28ce17"
        val conversationId = "46609e61-c17f-47f8-911b-8b26ae98e2a7"
        val backStack = mutableListOf<NavKey>(Screen.Chat(id = conversationId))

        val handled = backStack.openIncomingIntent(
            voiceConversationId = voiceConversationId,
            conversationId = conversationId,
        )

        assertTrue(handled)
        assertEquals(listOf(Screen.VoiceAgent(conversationId = voiceConversationId)), backStack)
    }
}
