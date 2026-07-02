package me.rerere.rikkahub.voiceagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

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

    @Test
    fun `default metadata builder uses trace context package build and launch fields`() {
        val metadata = buildDefaultVoiceE2ESessionMetadata(
            traceContext = VoiceTraceContext(
                traceId = "VA654321",
                voiceSessionId = "VS654321",
                sentryTrace = "0123456789abcdef0123456789abcdef-0123456789abcdef-1",
                sentryBaggage = "sentry-public_key=abc",
            ),
            conversationId = Uuid.parse("00000000-0000-0000-0000-000000000123"),
            packageName = "me.rerere.rikkahub.debug",
            voiceModelId = "gemini-flash",
            startedAtEpochMs = 1_700_000_005_000,
        )
        val jsonObject = Json.parseToJsonElement(metadata.toJson()).jsonObject

        assertEquals("VA654321", jsonObject.getValue("voiceTraceId").jsonPrimitive.content)
        assertEquals("VS654321", jsonObject.getValue("voiceSessionId").jsonPrimitive.content)
        assertEquals(
            "00000000-0000-0000-0000-000000000123",
            jsonObject.getValue("conversationId").jsonPrimitive.content,
        )
        assertEquals("me.rerere.rikkahub.debug", jsonObject.getValue("packageName").jsonPrimitive.content)
        assertEquals(BuildConfig.VERSION_NAME, jsonObject.getValue("versionName").jsonPrimitive.content)
        assertEquals(BuildConfig.VERSION_CODE, jsonObject.getValue("versionCode").jsonPrimitive.content)
        assertEquals("gemini-flash", jsonObject.getValue("voiceModelId").jsonPrimitive.content)
        assertEquals(1_700_000_005_000, jsonObject.getValue("startedAtEpochMs").jsonPrimitive.long)
        assertEquals("created", jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(JsonNull, jsonObject.getValue("providerModel"))
        assertTrue(jsonObject.getValue("debuggable").jsonPrimitive.boolean)
        assertTrue(jsonObject.getValue("sentryPropagationCreated").jsonPrimitive.boolean)
        assertEquals(
            BuildConfig.VOICE_AGENT_SENTRY_DSN.isNotBlank(),
            jsonObject.getValue("sentryDsnConfigured").jsonPrimitive.boolean,
        )
        assertEquals(
            BuildConfig.VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE.toDoubleOrNull()
                ?.let { it > 0.0 } ?: false,
            jsonObject.getValue("sentryTracingEnabled").jsonPrimitive.boolean,
        )
    }
}
