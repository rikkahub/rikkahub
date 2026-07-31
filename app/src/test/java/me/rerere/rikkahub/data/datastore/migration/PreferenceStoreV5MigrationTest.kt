package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceStoreV5MigrationTest {
    @Test
    fun `all existing assistants migrate to full access`() {
        val migrated = migrateAssistantsToFullAccess(
            """[{"name":"one"},{"name":"two","agentPermissionMode":"AUTO_REVIEW"}]""",
        )

        val assistants = JsonInstant.parseToJsonElement(migrated).jsonArray
        assertEquals(
            listOf("FULL_ACCESS", "FULL_ACCESS"),
            assistants.map { it.jsonObject.getValue("agentPermissionMode").jsonPrimitive.content },
        )
    }

    @Test
    fun `invalid assistant data is preserved`() {
        assertEquals("not-json", migrateAssistantsToFullAccess("not-json"))
    }
}
