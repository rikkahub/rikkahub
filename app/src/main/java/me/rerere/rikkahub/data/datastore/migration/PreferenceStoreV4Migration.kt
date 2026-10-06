package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        prefs[SettingsStore.ASSISTANTS]?.let { json ->
            prefs[SettingsStore.ASSISTANTS] = migrateAssistantsConversationInjections(json)
        }

        prefs[SettingsStore.VERSION] = 4
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/**
 * 移除助手的 allowConversationPromptInjection 开关。
 *
 * 开关开启时助手自身的注入绑定不生效，新会话从空集合开始；现在新会话一律继承助手的绑定，
 * 所以把这类助手的绑定清空，避免原本不生效的注入突然出现在新会话里。
 */
internal fun migrateAssistantsConversationInjections(assistantsJson: String): String {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
            ?: return@runCatching assistantsJson

        val migratedAssistants = JsonArray(
            root.map { assistant ->
                val assistantObj = assistant as? JsonObject
                    ?: return@map assistant
                if (FLAG !in assistantObj) return@map assistant

                val allowed = assistantObj[FLAG]?.jsonPrimitiveOrNull?.booleanOrNull == true
                JsonObject(
                    assistantObj.toMutableMap().apply {
                        remove(FLAG)
                        if (allowed) {
                            put("modeInjectionIds", JsonArray(emptyList()))
                            put("lorebookIds", JsonArray(emptyList()))
                        }
                    }
                )
            }
        )

        JsonInstant.encodeToString(migratedAssistants)
    }.getOrDefault(assistantsJson)
}

private const val FLAG = "allowConversationPromptInjection"
