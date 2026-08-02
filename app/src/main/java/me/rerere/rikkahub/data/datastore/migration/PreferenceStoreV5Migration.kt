package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.ai.agent.permission.AgentPermissionMode
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

/** Makes the user's explicit full-access choice the one-time default for every existing assistant. */
class PreferenceStoreV5Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        (currentData[SettingsStore.VERSION] ?: 0) < 5

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.ASSISTANTS] = migrateAssistantsToFullAccess(
            prefs[SettingsStore.ASSISTANTS] ?: "[]",
        )
        prefs[SettingsStore.VERSION] = 5
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

internal fun migrateAssistantsToFullAccess(assistantsJson: String): String = runCatching {
    val assistants = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
        ?: return@runCatching assistantsJson
    JsonInstant.encodeToString(
        JsonArray(
            assistants.map { assistant ->
                val assistantObject = assistant as? JsonObject ?: return@map assistant
                JsonObject(
                    assistantObject.toMutableMap().apply {
                        put(
                            "agentPermissionMode",
                            JsonPrimitive(AgentPermissionMode.FULL_ACCESS.name),
                        )
                    },
                )
            },
        ),
    )
}.getOrDefault(assistantsJson)
