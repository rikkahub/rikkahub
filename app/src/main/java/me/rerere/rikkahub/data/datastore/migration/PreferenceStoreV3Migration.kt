package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

class PreferenceStoreV3Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 3
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        val (migratedAssistants, extractedQuickMessages) =
            migrateAssistantsQuickMessages(prefs[SettingsStore.ASSISTANTS] ?: "[]")

        prefs[SettingsStore.ASSISTANTS] = migratedAssistants

        // 合并已有的全局快捷消息（防止重复）
        val existingQuickMessages = prefs[SettingsStore.QUICK_MESSAGES]?.let { json ->
            runCatching<JsonArray> {
                JsonInstant.parseToJsonElement(json).jsonArray
            }.getOrElse { JsonArray(emptyList()) }
        } ?: JsonArray(emptyList())

        val existingIds = existingQuickMessages.mapNotNull {
            (it as? JsonObject)?.get("id")?.toString()?.trim('"')
        }.toSet()

        val merged = JsonArray(
            existingQuickMessages + extractedQuickMessages.filter { element ->
                val id = (element as? JsonObject)?.get("id")?.toString()?.trim('"')
                id != null && id !in existingIds
            }
        )

        prefs[SettingsStore.QUICK_MESSAGES] = JsonInstant.encodeToString(merged)
        prefs[SettingsStore.VERSION] = 3

        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/**
 * 从旧格式 assistants JSON 中提取 quickMessages 字段（完整对象，无 id 字段），
 * 为每条消息生成新 UUID，将其替换为 quickMessageIds（仅 ID 列表），
 * 并返回补充了 id 的全局消息列表。
 */
internal fun migrateAssistantsQuickMessages(
    assistantsJson: String
): Pair<String, JsonArray> {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
            ?: return@runCatching assistantsJson to JsonArray(emptyList())

        val allQuickMessages = mutableListOf<JsonElement>()

        val migratedAssistants = JsonArray(
            root.map { assistant ->
                val assistantObj = assistant as? JsonObject
                    ?: return@map assistant

                // 如果不存在旧的 quickMessages 字段则无需迁移
                val oldQuickMessages = assistantObj["quickMessages"] as? JsonArray
                    ?: return@map assistant

                // 为每条旧消息注入新生成的 id
                val messagesWithIds = oldQuickMessages.map { element ->
                    val obj = element as? JsonObject ?: return@map element
                    val newId = Uuid.random().toString()
                    JsonObject(obj.toMutableMap().apply {
                        put("id", JsonPrimitive(newId))
                    })
                }

                // 收集到全局列表
                allQuickMessages.addAll(messagesWithIds)

                // 提取 ID 列表构建 quickMessageIds
                val ids = JsonArray(
                    messagesWithIds.mapNotNull { element ->
                        (element as? JsonObject)?.get("id")
                    }
                )

                JsonObject(
                    assistantObj.toMutableMap().apply {
                        remove("quickMessages")
                        put("quickMessageIds", ids)
                    }
                )
            }
        )

        JsonInstant.encodeToString(migratedAssistants) to JsonArray(allQuickMessages)
    }.getOrElse { assistantsJson to JsonArray(emptyList()) }
}
