package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class PreferenceStoreV4MigrationTest {
    @Test
    fun `migrateAssistantsStCompatSettings should lift selected assistant config into global settings`() {
        val assistantsJson = JsonInstant.encodeToString(
            JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("assistant-a"),
                            "stCompatScriptEnabled" to JsonPrimitive(false),
                            "stCompatScriptSource" to JsonPrimitive("script-a"),
                            "stCompatExtensionSettings" to JsonObject(
                                mapOf(
                                    "shared" to JsonPrimitive(1),
                                    "aOnly" to JsonPrimitive("a"),
                                )
                            ),
                        )
                    ),
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("assistant-b"),
                            "stCompatScriptEnabled" to JsonPrimitive(true),
                            "stCompatScriptSource" to JsonPrimitive("script-b"),
                            "stCompatExtensionSettings" to JsonObject(
                                mapOf(
                                    "shared" to JsonPrimitive(2),
                                    "bOnly" to JsonPrimitive("b"),
                                )
                            ),
                        )
                    ),
                )
            )
        )

        val migrated = migrateAssistantsStCompatSettings(
            assistantsJson = assistantsJson,
            selectedAssistantId = "assistant-b",
        )

        assertEquals(true, migrated.enabled)
        assertEquals("script-b", migrated.scriptSource)
        assertEquals(2, migrated.extensionSettings["shared"]?.jsonPrimitive?.int)
        assertNotNull(migrated.extensionSettings["aOnly"])
        assertNotNull(migrated.extensionSettings["bOnly"])

        val cleanedAssistants = JsonInstant.parseToJsonElement(migrated.assistantsJson).jsonArray
        cleanedAssistants.forEach { assistant ->
            val assistantObject = assistant.jsonObject
            assertFalse(assistantObject.containsKey("stCompatScriptEnabled"))
            assertFalse(assistantObject.containsKey("stCompatScriptSource"))
            assertFalse(assistantObject.containsKey("stCompatExtensionSettings"))
        }
    }
}
