package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The additive activation field for the #231 mesh-gradient chat background:
 * [Assistant.useGradientBackground] is `@Serializable` with a `false` default, so NO DataStore
 * migration is required (assistants persist as JSON via JsonInstant, not Room — mirrors the
 * uiAutomationEnabled / subagent-fields pattern exactly).
 *
 * The load-bearing invariant: a legacy assistant JSON written BEFORE this field existed (omitting
 * the key) must still deserialize, and must do so with the documented default (gradient OFF, so the
 * chat page keeps its image/plain background). Without the default, kotlinx-serialization throws
 * MissingFieldException — the exact failure this guards against.
 */
class AssistantGradientBackgroundFieldTest {

    @Test
    fun `legacy assistant JSON without useGradientBackground decodes with default false (no migration)`() {
        val legacyJson = """
            {
              "id": "33333333-3333-3333-3333-333333333333",
              "name": "Legacy"
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<Assistant>(legacyJson)

        assertEquals("Legacy", decoded.name)
        assertFalse(
            "gradient background must default OFF for legacy assistants",
            decoded.useGradientBackground
        )
    }

    @Test
    fun `a default-constructed assistant keeps gradient background OFF`() {
        assertFalse(Assistant().useGradientBackground)
    }

    @Test
    fun `a non-default useGradientBackground survives an encode then decode round-trip`() {
        val original = Assistant(name = "Aurora", useGradientBackground = true)

        val roundTripped = JsonInstant.decodeFromString<Assistant>(
            JsonInstant.encodeToString(original)
        )

        assertEquals(original.id, roundTripped.id)
        assertTrue(roundTripped.useGradientBackground)
    }

    @Test
    fun `encoded assistant emits the new key with its default value (encodeDefaults)`() {
        val json = JsonInstant.parseToJsonElement(
            JsonInstant.encodeToString(Assistant(name = "X"))
        ) as kotlinx.serialization.json.JsonObject

        assertEquals(false, json["useGradientBackground"]!!.jsonPrimitive.content.toBoolean())
    }
}
