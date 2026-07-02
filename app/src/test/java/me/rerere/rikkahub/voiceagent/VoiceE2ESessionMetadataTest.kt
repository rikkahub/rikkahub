package me.rerere.rikkahub.voiceagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

        assertEquals(
            setOf(
                "voiceTraceId",
                "voiceSessionId",
                "conversationId",
                "packageName",
                "versionName",
                "versionCode",
                "debuggable",
                "voiceModelId",
                "providerModel",
                "status",
                "startedAtEpochMs",
                "endedAtEpochMs",
                "closeStatus",
                "sentryDsnConfigured",
                "sentryTracingEnabled",
                "sentryPropagationCreated",
            ),
            jsonObject.keys,
        )
        assertEquals("VA000123", jsonObject.getValue("voiceTraceId").jsonPrimitive.content)
        assertEquals("session-123", jsonObject.getValue("voiceSessionId").jsonPrimitive.content)
        assertEquals("conversation-123", jsonObject.getValue("conversationId").jsonPrimitive.content)
        assertEquals("me.rerere.rikkahub", jsonObject.getValue("packageName").jsonPrimitive.content)
        assertEquals("1.2.3", jsonObject.getValue("versionName").jsonPrimitive.content)
        assertEquals("123", jsonObject.getValue("versionCode").jsonPrimitive.content)
        assertTrue(jsonObject.getValue("debuggable").jsonPrimitive.boolean)
        assertEquals("voice-model", jsonObject.getValue("voiceModelId").jsonPrimitive.content)
        assertEquals("provider-model", jsonObject.getValue("providerModel").jsonPrimitive.content)
        assertEquals("started", jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(1_700_000_000_000, jsonObject.getValue("startedAtEpochMs").jsonPrimitive.long)
        assertEquals(JsonNull, jsonObject.getValue("endedAtEpochMs"))
        assertEquals(JsonNull, jsonObject.getValue("closeStatus"))
        assertTrue(jsonObject.getValue("sentryDsnConfigured").jsonPrimitive.boolean)
        assertFalse(jsonObject.getValue("sentryTracingEnabled").jsonPrimitive.boolean)
        assertTrue(jsonObject.getValue("sentryPropagationCreated").jsonPrimitive.boolean)
        assertFalse(json.contains("Authorization"))
        assertFalse(json.contains("Bearer"))
    }
}
