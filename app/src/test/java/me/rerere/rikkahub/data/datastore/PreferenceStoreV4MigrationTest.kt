package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.migration.migrateAssistantsConversationInjections
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PreferenceStoreV4MigrationTest {
    private val flag = "allowConversationPromptInjection"

    private fun migrate(json: String): List<JsonObject> =
        JsonInstant.parseToJsonElement(migrateAssistantsConversationInjections(json)).jsonArray.map { it.jsonObject }

    private fun ids(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })

    @Test
    fun `assistant that allowed conversation injections loses its own unused bindings`() {
        val (assistant) = migrate(
            """[{"id":"a","name":"A","$flag":true,"modeInjectionIds":["m"],"lorebookIds":["l"]}]"""
        )

        assertFalse(flag in assistant)
        assertEquals(ids(), assistant["modeInjectionIds"])
        assertEquals(ids(), assistant["lorebookIds"])
        assertEquals(JsonPrimitive("A"), assistant["name"])
    }

    @Test
    fun `assistant without conversation injections keeps its bindings`() {
        val (disabled, missing) = migrate(
            """[
                {"id":"a","$flag":false,"modeInjectionIds":["m"],"lorebookIds":["l"]},
                {"id":"b","modeInjectionIds":["m"],"lorebookIds":["l"]}
            ]"""
        )

        assertFalse(flag in disabled)
        assertEquals(ids("m"), disabled["modeInjectionIds"])
        assertEquals(ids("l"), disabled["lorebookIds"])
        assertEquals(ids("m"), missing["modeInjectionIds"])
        assertEquals(ids("l"), missing["lorebookIds"])
    }

    @Test
    fun `migration can be applied again without changing the result`() {
        val once = migrateAssistantsConversationInjections(
            """[{"id":"a","$flag":true,"modeInjectionIds":["m"],"lorebookIds":["l"]}]"""
        )

        assertEquals(once, migrateAssistantsConversationInjections(once))
    }

    @Test
    fun `unparseable assistants are left untouched`() {
        assertEquals("not json", migrateAssistantsConversationInjections("not json"))
    }
}
