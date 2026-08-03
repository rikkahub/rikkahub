package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentCallContractTest {
    @Test
    fun `foreground service contract is stable`() {
        assertEquals("me.rerere.rikkahub.voiceagent.action.START", VoiceAgentCallContract.ACTION_START)
        assertEquals("me.rerere.rikkahub.voiceagent.action.END", VoiceAgentCallContract.ACTION_END)
        assertEquals("me.rerere.rikkahub.voiceagent.action.END_BOUND", VoiceAgentCallContract.ACTION_END_BOUND)
        assertEquals("conversationId", VoiceAgentCallContract.EXTRA_CONVERSATION_ID)
        assertEquals("transport", VoiceAgentCallContract.EXTRA_TRANSPORT)
        assertEquals("run_hash", VoiceAgentCallContract.EXTRA_RUN_HASH)
        assertEquals("comparison_hash", VoiceAgentCallContract.EXTRA_COMPARISON_HASH)
        assertEquals(
            "voiceAgentTransport",
            VoiceAgentCallContract.EXTRA_ROUTE_VOICE_AGENT_TRANSPORT,
        )
        assertEquals(2401, VoiceAgentCallContract.NOTIFICATION_ID)
    }

    @Test
    fun `notification route transport defaults only a missing legacy extra to direct`() {
        val conversationId = "0e822879-5558-45c9-b3dd-8637db28ce17"

        assertEquals(
            VoiceAgentTransport.DirectGemini,
            decodeVoiceAgentNotificationRouteFields(conversationId, null)?.transport,
        )
        assertEquals(
            VoiceAgentTransport.LiveKitExperimental,
            decodeVoiceAgentNotificationRouteFields(
                conversationId,
                VoiceAgentTransport.LiveKitExperimental.wireName,
            )?.transport,
        )
        assertNull(decodeVoiceAgentNotificationRouteFields(conversationId, "unknown"))
    }

    @Test
    fun `notification route extra matches RouteActivity contract`() {
        assertEquals("voiceAgentConversationId", VoiceAgentCallContract.EXTRA_ROUTE_VOICE_AGENT_CONVERSATION_ID)
    }

    @Test
    fun `start binding requires both canonical hashes or neither`() {
        assertNull(
            decodeVoiceAgentCallStartFields(
                CONVERSATION_ID,
                VoiceAgentTransport.LiveKitExperimental.wireName,
                "fixture-1",
                RUN_HASH,
                null,
            ),
        )
        assertNull(
            decodeVoiceAgentCallStartFields(
                CONVERSATION_ID,
                VoiceAgentTransport.LiveKitExperimental.wireName,
                "fixture-1",
                null,
                COMPARISON_HASH,
            ),
        )
        assertNull(
            decodeVoiceAgentCallStartFields(
                CONVERSATION_ID,
                VoiceAgentTransport.LiveKitExperimental.wireName,
                "fixture-1",
                "raw-run",
                COMPARISON_HASH,
            ),
        )
        assertNull(
            decodeVoiceAgentCallStartFields(
                CONVERSATION_ID,
                VoiceAgentTransport.LiveKitExperimental.wireName,
                "fixture-1",
                RUN_HASH,
                "raw-comparison",
            ),
        )

        assertEquals(
            null,
            decodeVoiceAgentCallStartFields(
                CONVERSATION_ID,
                VoiceAgentTransport.LiveKitExperimental.wireName,
                "fixture-1",
                null,
                null,
            )?.automationBinding,
        )
        assertEquals(
            VoiceAgentAutomationBinding(RUN_HASH, COMPARISON_HASH),
            decodeVoiceAgentCallStartFields(
                CONVERSATION_ID,
                VoiceAgentTransport.LiveKitExperimental.wireName,
                "fixture-1",
                RUN_HASH,
                COMPARISON_HASH,
            )?.automationBinding,
        )
    }

    @Test
    fun `bound end identity requires an exact conversation transport and binding`() {
        val expected =
            VoiceAgentBoundCallIdentity(
                conversationId = Uuid.parse(CONVERSATION_ID),
                transport = VoiceAgentTransport.LiveKitExperimental,
                automationBinding = VoiceAgentAutomationBinding(RUN_HASH, COMPARISON_HASH),
            )
        assertEquals(
            expected,
            decodeVoiceAgentBoundCallIdentity(
                CONVERSATION_ID,
                VoiceAgentTransport.LiveKitExperimental.wireName,
                RUN_HASH,
                COMPARISON_HASH,
            ),
        )
        assertNull(decodeVoiceAgentBoundCallIdentity(CONVERSATION_ID, null, RUN_HASH, COMPARISON_HASH))
        assertNull(
            decodeVoiceAgentBoundCallIdentity(
                CONVERSATION_ID,
                VoiceAgentTransport.LiveKitExperimental.wireName,
                RUN_HASH,
                null,
            ),
        )
        val exactExtras = mapOf(
            VoiceAgentCallContract.EXTRA_CONVERSATION_ID to CONVERSATION_ID,
            VoiceAgentCallContract.EXTRA_TRANSPORT to VoiceAgentTransport.LiveKitExperimental.wireName,
            VoiceAgentCallContract.EXTRA_RUN_HASH to RUN_HASH,
            VoiceAgentCallContract.EXTRA_COMPARISON_HASH to COMPARISON_HASH,
        )
        assertEquals(expected, decodeVoiceAgentBoundCallIdentity(exactExtras))
        assertNull(decodeVoiceAgentBoundCallIdentity(exactExtras + ("unexpected" to "value")))
        assertNull(decodeVoiceAgentBoundCallIdentity(exactExtras - VoiceAgentCallContract.EXTRA_RUN_HASH))
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

    private companion object {
        const val CONVERSATION_ID = "0e822879-5558-45c9-b3dd-8637db28ce17"
        const val RUN_HASH = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val COMPARISON_HASH =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
