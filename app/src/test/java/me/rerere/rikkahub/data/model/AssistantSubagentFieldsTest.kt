package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.json.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SLICE 1 of the subagent feature (issue #201): the three additive [Assistant] fields
 * — `description`, `spawnable`, `maxSteps` — are `@Serializable` with defaults so that
 * NO DataStore migration is required (assistants persist as JSON via JsonInstant, not Room).
 *
 * The load-bearing invariant: a legacy assistant JSON written BEFORE these fields existed
 * (i.e. omitting all three keys) must still deserialize, and must do so with the documented
 * defaults. Without the field defaults, kotlinx-serialization throws MissingFieldException —
 * which IS the failure this test guards against. JsonInstant uses encodeDefaults=true and
 * ignoreUnknownKeys=true (utils/Json.kt), exactly as production does.
 */
class AssistantSubagentFieldsTest {

    @Test
    fun `legacy assistant JSON without the new fields decodes with defaults (no migration)`() {
        // A minimal assistant payload as it would have been persisted before SLICE 1.
        // Note: none of description / spawnable / maxSteps are present.
        val legacyJson = """
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "name": "Legacy"
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<Assistant>(legacyJson)

        assertEquals("Legacy", decoded.name)
        assertEquals("", decoded.description)
        assertFalse(decoded.spawnable)
        assertNull(decoded.maxSteps)
    }

    @Test
    fun `non-default subagent fields survive an encode then decode round-trip`() {
        val original = Assistant(
            name = "Researcher",
            description = "Use me for multi-source web research",
            spawnable = true,
            maxSteps = 64,
        )

        val roundTripped = JsonInstant.decodeFromString<Assistant>(
            JsonInstant.encodeToString(original)
        )

        assertEquals(original.id, roundTripped.id)
        assertEquals("Use me for multi-source web research", roundTripped.description)
        assertTrue(roundTripped.spawnable)
        assertEquals(64, roundTripped.maxSteps)
    }

    @Test
    fun `a default-constructed assistant keeps the documented subagent defaults`() {
        val a = Assistant()
        assertEquals("", a.description)
        assertFalse(a.spawnable)
        assertNull(a.maxSteps)
    }

    @Test
    fun `encoded assistant emits the new keys with their default values (encodeDefaults)`() {
        val json = JsonInstant.parseToJsonElement(
            JsonInstant.encodeToString(Assistant(name = "X"))
        )
        val obj = json as kotlinx.serialization.json.JsonObject
        // encodeDefaults=true means the new keys are present even at their defaults; this is
        // forward-compat insurance and proves the keys are wired into the serializer at all.
        assertEquals("", obj["description"]!!.jsonPrimitive.content)
        assertEquals(false, obj["spawnable"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(obj["maxSteps"]!!.jsonPrimitive.content == "null")
    }
}
