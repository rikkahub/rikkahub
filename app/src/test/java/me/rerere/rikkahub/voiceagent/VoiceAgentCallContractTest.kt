package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentCallContractTest {
    @Test
    fun `foreground service contract is stable`() {
        assertEquals("me.rerere.rikkahub.voiceagent.action.START", VoiceAgentCallContract.ACTION_START)
        assertEquals("me.rerere.rikkahub.voiceagent.action.END", VoiceAgentCallContract.ACTION_END)
        assertEquals("conversationId", VoiceAgentCallContract.EXTRA_CONVERSATION_ID)
        assertEquals(2401, VoiceAgentCallContract.NOTIFICATION_ID)
    }

    @Test
    fun `notification route extra matches RouteActivity contract`() {
        assertEquals("voiceAgentConversationId", VoiceAgentCallContract.EXTRA_ROUTE_VOICE_AGENT_CONVERSATION_ID)
    }

    @Test
    fun `end foreground promotion is used for active conversation`() {
        val activeConversationId = Uuid.parse("11111111-1111-4111-8111-111111111111")

        assertTrue(shouldStartForegroundForVoiceAgentEnd(activeConversationId))
    }

    @Test
    fun `end foreground promotion is used when idle`() {
        assertTrue(shouldStartForegroundForVoiceAgentEnd(null))
    }
}
