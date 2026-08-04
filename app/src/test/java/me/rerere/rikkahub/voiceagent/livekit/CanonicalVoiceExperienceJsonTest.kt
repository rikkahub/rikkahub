package me.rerere.rikkahub.voiceagent.livekit

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CanonicalVoiceExperienceJsonTest {
    @Test
    fun `golden corpus retains its exact UTF-8 bytes and every row round trips`() {
        val bytes = javaClass.classLoader
            .getResourceAsStream("voice-experience-canonical-v1.ndjson")
            ?.use { it.readBytes() }
            ?: error("canonical corpus is missing")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                byte.toInt().and(0xff).toString(16).padStart(2, '0')
            }

        assertEquals(
            "sha256:06db9c679c58703aa65cab460c351d32d19e371dc0bda2aa5337f77be4fa5335",
            "sha256:$hash",
        )
        assertTrue(bytes.last() == '\n'.code.toByte())
        bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotEmpty).forEach { row ->
            val fields = Json.parseToJsonElement(row).jsonObject
            assertEquals(row, CanonicalVoiceExperienceJson.encodeObject(fields))
        }
    }

    @Test
    fun `canonical encoder rejects null and noninteger number primitives`() {
        listOf(JsonNull, JsonPrimitive(1.5), JsonPrimitive(1e3)).forEach { value ->
            try {
                CanonicalVoiceExperienceJson.encodeObject(mapOf("value" to value))
                fail("unsupported canonical primitive was accepted: $value")
            } catch (_: IllegalStateException) {
            }
        }
    }

    @Test
    fun `canonical encoder renders arrays without whitespace`() {
        val fields = Json.parseToJsonElement(
            """{"array":[{"b":2,"a":1},true,"x"]}""",
        ).jsonObject

        assertEquals(
            """{"array":[{"a":1,"b":2},true,"x"]}""",
            CanonicalVoiceExperienceJson.encodeObject(fields),
        )
    }

    @Test
    fun `canonical encoder sorts keys by Unicode code point`() {
        val fields = mapOf(
            "𐀀" to JsonPrimitive(1),
            "" to JsonPrimitive(0),
        )

        assertEquals(
            """{"":0,"𐀀":1}""",
            CanonicalVoiceExperienceJson.encodeObject(fields),
        )
    }

    @Test
    fun `timestamp accepts only canonical fractions`() {
        listOf(
            "2026-08-04T12:00:00Z",
            "2026-08-04T12:00:00.123Z",
            "2026-08-04T12:00:00.123456Z",
            "2026-08-04T12:00:00.123456789Z",
        ).forEach { assertTrue(CanonicalVoiceExperienceJson.isCanonicalInstant(it)) }
        listOf(
            "2026-08-04T12:00:00.1Z",
            "2026-08-04T12:00:00.123000Z",
            "2026-08-04T12:00:00+00:00",
            "2016-12-31T23:59:60Z",
        ).forEach { assertFalse(CanonicalVoiceExperienceJson.isCanonicalInstant(it)) }
    }
}
