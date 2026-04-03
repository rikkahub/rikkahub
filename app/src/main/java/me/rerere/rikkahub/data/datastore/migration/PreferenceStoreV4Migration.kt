package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val migrated = migrateAssistantsStCompatSettings(
            assistantsJson = prefs[SettingsStore.ASSISTANTS] ?: "[]",
            selectedAssistantId = prefs[SettingsStore.SELECT_ASSISTANT],
        )

        val currentMap = currentData.asMap()
        val hasGlobalEnabled = currentMap.containsKey(SettingsStore.ST_COMPAT_SCRIPT_ENABLED)
        val hasGlobalSource = currentMap.containsKey(SettingsStore.ST_COMPAT_SCRIPT_SOURCE)
        val hasGlobalSettings = currentMap.containsKey(SettingsStore.ST_COMPAT_EXTENSION_SETTINGS)

        prefs[SettingsStore.ASSISTANTS] = migrated.assistantsJson
        prefs[SettingsStore.ST_COMPAT_SCRIPT_ENABLED] = if (hasGlobalEnabled) {
            prefs[SettingsStore.ST_COMPAT_SCRIPT_ENABLED] == true
        } else {
            migrated.enabled
        }
        prefs[SettingsStore.ST_COMPAT_SCRIPT_SOURCE] = if (hasGlobalSource) {
            prefs[SettingsStore.ST_COMPAT_SCRIPT_SOURCE].orEmpty()
        } else {
            migrated.scriptSource
        }
        prefs[SettingsStore.ST_COMPAT_EXTENSION_SETTINGS] = JsonInstant.encodeToString(
            if (hasGlobalSettings) {
                prefs[SettingsStore.ST_COMPAT_EXTENSION_SETTINGS]
                    ?.let { runCatching { JsonInstant.parseToJsonElement(it).jsonObject }.getOrNull() }
                    ?: buildJsonObject { }
            } else {
                migrated.extensionSettings
            }
        )
        prefs[SettingsStore.VERSION] = 4

        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal data class LegacyStCompatMigrationResult(
    val assistantsJson: String,
    val enabled: Boolean,
    val scriptSource: String,
    val extensionSettings: JsonObject,
)

private data class LegacyAssistantStCompatState(
    val id: String?,
    val enabled: Boolean?,
    val scriptSource: String,
    val extensionSettings: JsonObject,
)

internal fun migrateAssistantsStCompatSettings(
    assistantsJson: String,
    selectedAssistantId: String? = null,
): LegacyStCompatMigrationResult {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
            ?: return@runCatching LegacyStCompatMigrationResult(
                assistantsJson = assistantsJson,
                enabled = false,
                scriptSource = "",
                extensionSettings = buildJsonObject { },
            )

        val legacyStates = mutableListOf<LegacyAssistantStCompatState>()
        val migratedAssistants = JsonArray(
            root.map { assistant ->
                val assistantObject = assistant as? JsonObject
                    ?: return@map assistant

                val legacyEnabled = assistantObject["stCompatScriptEnabled"]
                    ?.jsonPrimitive
                    ?.booleanOrNull
                val legacyScriptSource = assistantObject["stCompatScriptSource"]
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
                val legacyExtensionSettings = runCatching {
                    assistantObject["stCompatExtensionSettings"]?.jsonObject
                }.getOrNull() ?: buildJsonObject { }
                val hasLegacyFields =
                    assistantObject.containsKey("stCompatScriptEnabled") ||
                        assistantObject.containsKey("stCompatScriptSource") ||
                        assistantObject.containsKey("stCompatExtensionSettings")

                if (hasLegacyFields) {
                    legacyStates += LegacyAssistantStCompatState(
                        id = assistantObject["id"]?.jsonPrimitive?.contentOrNull,
                        enabled = legacyEnabled,
                        scriptSource = legacyScriptSource,
                        extensionSettings = legacyExtensionSettings,
                    )
                }

                JsonObject(
                    assistantObject.toMutableMap().apply {
                        remove("stCompatScriptEnabled")
                        remove("stCompatScriptSource")
                        remove("stCompatExtensionSettings")
                    }
                )
            }
        )

        val selectedState = legacyStates.firstOrNull { it.id == selectedAssistantId }
        val scriptPriority = buildList {
            selectedState?.let(::add)
            addAll(legacyStates.filterNot { it.id == selectedAssistantId })
        }
        val mergePriority = buildList {
            addAll(legacyStates.filterNot { it.id == selectedAssistantId })
            selectedState?.let(::add)
        }

        val mergedExtensionSettings = JsonObject(
            buildMap<String, JsonElement> {
                mergePriority.forEach { state ->
                    putAll(state.extensionSettings)
                }
            }
        )

        LegacyStCompatMigrationResult(
            assistantsJson = JsonInstant.encodeToString(migratedAssistants),
            enabled = selectedState?.enabled ?: legacyStates.any { it.enabled == true },
            scriptSource = scriptPriority.firstOrNull { it.scriptSource.isNotBlank() }?.scriptSource.orEmpty(),
            extensionSettings = mergedExtensionSettings,
        )
    }.getOrElse {
        LegacyStCompatMigrationResult(
            assistantsJson = assistantsJson,
            enabled = false,
            scriptSource = "",
            extensionSettings = buildJsonObject { },
        )
    }
}
