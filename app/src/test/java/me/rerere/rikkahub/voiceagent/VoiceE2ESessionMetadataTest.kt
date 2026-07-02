package me.rerere.rikkahub.voiceagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceE2ESessionMetadataTest {
    @Test
    fun `toJson contains safe session and sentry metadata only`() {
        val metadata = VoiceE2ESessionMetadata(
            voiceTraceId = "VA000123",
            voiceSessionId = "session-123",
            conversationId = "conversation-123",
            packageName = "me.rerere.rikkahub",
            versionName = "1.2.3",
            versionCode = "123",
            debuggable = true,
            voiceModelId = "voice-model",
            providerModel = "provider-model",
            status = "started",
            startedAtEpochMs = 1_700_000_000_000,
            endedAtEpochMs = null,
            closeStatus = null,
            sentryDsnConfigured = true,
            sentryTracingEnabled = false,
            sentryPropagationCreated = true,
        )

        val json = metadata.toJson()
        val jsonObject = Json.parseToJsonElement(json).jsonObject

        assertEquals("VA000123", jsonObject.getValue("voiceTraceId").jsonPrimitive.content)
        assertEquals("session-123", jsonObject.getValue("voiceSessionId").jsonPrimitive.content)
        assertEquals("conversation-123", jsonObject.getValue("conversationId").jsonPrimitive.content)
        assertEquals("started", jsonObject.getValue("status").jsonPrimitive.content)
        assertTrue(jsonObject.getValue("sentryDsnConfigured").jsonPrimitive.boolean)
        assertFalse(jsonObject.getValue("sentryTracingEnabled").jsonPrimitive.boolean)
        assertTrue(jsonObject.getValue("sentryPropagationCreated").jsonPrimitive.boolean)
        assertFalse(json.contains("Authorization"))
        assertFalse(json.contains("Bearer"))
    }
}
