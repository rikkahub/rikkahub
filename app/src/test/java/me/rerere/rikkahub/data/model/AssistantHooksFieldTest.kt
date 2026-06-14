package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.runtime.hooks.HookConfig
import me.rerere.ai.runtime.hooks.HookEvent
import me.rerere.ai.runtime.hooks.HookHandler
import me.rerere.ai.runtime.hooks.HookMatcher
import me.rerere.common.json.JsonInstant
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.datastore.migration.migrateAssistantsQuickMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The additive hooks field (#200 v1): [Assistant.hooks] is `@Serializable` with a default, so NO
 * DataStore migration is required (assistants persist as JSON via JsonInstant — mirrors the
 * uiAutomationEnabled / subagent-fields pattern). The load-bearing invariant: a legacy assistant
 * JSON written BEFORE this field existed must still decode — including after passing through the
 * two real migration paths (SettingsJsonMigrator for backup restore, the
 * PreferenceStoreV3Migration core for the DataStore) — and must decode to the fail-closed default
 * (no hooks, untrusted).
 */
class AssistantHooksFieldTest {

    @Test
    fun `legacy assistant JSON without hooks decodes with default empty untrusted config`() {
        val legacyJson = """
            {
              "id": "33333333-3333-3333-3333-333333333333",
              "name": "Legacy"
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<Assistant>(legacyJson)

        assertEquals("Legacy", decoded.name)
        assertEquals(HookConfig(), decoded.hooks)
        assertTrue(decoded.hooks.hooks.isEmpty())
        assertFalse("hooks must default untrusted", decoded.hooks.trusted)
    }

    @Test
    fun `legacy settings JSON survives SettingsJsonMigrator and decodes with default hooks`() {
        val legacySettingsJson = """
            {
              "assistants": [
                {
                  "id": "44444444-4444-4444-4444-444444444444",
                  "name": "Restored",
                  "quickMessages": [{"title": "t", "content": "c"}]
                }
              ]
            }
        """.trimIndent()

        val migrated = SettingsJsonMigrator.migrate(legacySettingsJson)
        val assistantJson = (JsonInstant.parseToJsonElement(migrated) as JsonObject)
            .getValue("assistants").jsonArray.first()
        val decoded = JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(assistantJson))

        assertEquals("Restored", decoded.name)
        assertEquals("V3 must still extract quickMessages", 1, decoded.quickMessageIds.size)
        assertEquals(HookConfig(), decoded.hooks)
    }

    @Test
    fun `legacy assistants array survives the PreferenceStoreV3 migration core and decodes with default hooks`() {
        val legacyAssistantsJson = """
            [
              {
                "id": "55555555-5555-5555-5555-555555555555",
                "name": "Stored",
                "quickMessages": [{"title": "t", "content": "c"}]
              }
            ]
        """.trimIndent()

        val (migratedAssistants, extracted) = migrateAssistantsQuickMessages(legacyAssistantsJson)
        val decoded = JsonInstant.decodeFromString<List<Assistant>>(migratedAssistants).single()

        assertEquals("Stored", decoded.name)
        assertEquals(1, extracted.size)
        assertEquals(HookConfig(), decoded.hooks)
    }

    @Test
    fun `assistant with an llm hook survives an encode then decode round-trip`() {
        val original = Assistant(
            name = "Gated",
            hooks = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(
                            matcher = "search_web",
                            handlers = listOf(HookHandler.Llm(prompt = "deny PII", failClosed = true)),
                        )
                    )
                ),
                trusted = true,
            ),
        )

        val roundTripped = JsonInstant.decodeFromString<Assistant>(
            JsonInstant.encodeToString(original)
        )

        assertEquals(original.id, roundTripped.id)
        assertEquals(original.hooks, roundTripped.hooks)
    }

    @Test
    fun `encoded assistant emits the hooks key with its default value (encodeDefaults)`() {
        val json = JsonInstant.parseToJsonElement(
            JsonInstant.encodeToString(Assistant(name = "X"))
        ).jsonObject

        val hooks = json.getValue("hooks").jsonObject
        assertEquals("false", hooks.getValue("trusted").toString().trim('"'))
    }

    // ---- T6 / M3: additive AutomationGrant field ----------------------------------------------
    //
    // Same additive-default invariant as `hooks`: [Assistant.automationGrant] is `@Serializable`
    // with a default `AutomationGrant()`, so legacy assistant JSON written BEFORE this field
    // existed decodes UNCHANGED to the fail-closed default (disabled, no packages/verbs/sinks,
    // zero TTL/steps == deny-all). No DataStore migration is required.

    @Test
    fun `legacy assistant JSON without automationGrant decodes to the deny-all default`() {
        val legacyJson = """
            {
              "id": "66666666-6666-6666-6666-666666666666",
              "name": "LegacyGrantless"
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<Assistant>(legacyJson)

        assertEquals("LegacyGrantless", decoded.name)
        assertEquals(AutomationGrant(), decoded.automationGrant)
        assertFalse("grant must default disabled", decoded.automationGrant.enabled)
        assertTrue(decoded.automationGrant.allowedPackages.isEmpty())
        assertTrue(decoded.automationGrant.verbs.isEmpty())
        assertTrue(decoded.automationGrant.sinks.isEmpty())
        assertEquals(0, decoded.automationGrant.ttlMinutes)
        assertEquals(0, decoded.automationGrant.maxSteps)
    }

    @Test
    fun `assistant with a populated automationGrant survives an encode then decode round-trip`() {
        val original = Assistant(
            name = "Granted",
            automationGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.example.target"),
                verbs = setOf(AutomationVerb.OBSERVE, AutomationVerb.TAP),
                sinks = setOf(AutomationSink.TYPE_INTO),
                ttlMinutes = 5,
                maxSteps = 50,
            ),
        )

        val roundTripped = JsonInstant.decodeFromString<Assistant>(
            JsonInstant.encodeToString(original)
        )

        assertEquals(original.id, roundTripped.id)
        assertEquals(original.automationGrant, roundTripped.automationGrant)
    }
}
