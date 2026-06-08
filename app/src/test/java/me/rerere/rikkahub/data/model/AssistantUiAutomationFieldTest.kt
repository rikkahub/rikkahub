package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The additive activation field for the #187 v1 UI-automation surface: [Assistant.uiAutomationEnabled]
 * is `@Serializable` with a `false` default, so NO DataStore migration is required (assistants persist
 * as JSON via JsonInstant, not Room — mirrors the #201 subagent-fields pattern exactly).
 *
 * The load-bearing invariant: a legacy assistant JSON written BEFORE this field existed (omitting the
 * key) must still deserialize, and must do so with the documented default (OFF / inert). Without the
 * default, kotlinx-serialization throws MissingFieldException — the exact failure this guards against.
 * Default OFF + empty surface is also the conservative fail-closed posture the gate review demands:
 * the tool authorizes nothing until the user explicitly enables it.
 */
class AssistantUiAutomationFieldTest {

    @Test
    fun `legacy assistant JSON without uiAutomationEnabled decodes with default false (no migration)`() {
        val legacyJson = """
            {
              "id": "22222222-2222-2222-2222-222222222222",
              "name": "Legacy"
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<Assistant>(legacyJson)

        assertEquals("Legacy", decoded.name)
        assertFalse("ui automation must default OFF for legacy assistants", decoded.uiAutomationEnabled)
    }

    @Test
    fun `a default-constructed assistant keeps ui automation OFF`() {
        assertFalse(Assistant().uiAutomationEnabled)
    }

    @Test
    fun `a non-default uiAutomationEnabled survives an encode then decode round-trip`() {
        val original = Assistant(name = "Driver", uiAutomationEnabled = true)

        val roundTripped = JsonInstant.decodeFromString<Assistant>(
            JsonInstant.encodeToString(original)
        )

        assertEquals(original.id, roundTripped.id)
        assertTrue(roundTripped.uiAutomationEnabled)
    }

    @Test
    fun `encoded assistant emits the new key with its default value (encodeDefaults)`() {
        val json = JsonInstant.parseToJsonElement(
            JsonInstant.encodeToString(Assistant(name = "X"))
        ) as kotlinx.serialization.json.JsonObject

        assertEquals(false, json["uiAutomationEnabled"]!!.jsonPrimitive.content.toBoolean())
    }
}
