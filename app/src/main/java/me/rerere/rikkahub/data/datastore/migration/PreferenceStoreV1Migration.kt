package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

class PreferenceStoreV1Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 1
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        // 清理老的没有设置@SerialName的字段
        prefs[SettingsStore.MCP_SERVERS] = prefs[SettingsStore.MCP_SERVERS]?.let { json ->
            val element = JsonInstant.parseToJsonElement(json).jsonArray.map { element ->
                val jsonObj = element.jsonObject.toMutableMap()
                val type = jsonObj["type"]?.jsonPrimitive?.content ?: ""
                when(type) {
                    "me.rerere.rikkahub.data.mcp.McpServerConfig.SseTransportServer" -> {
                        jsonObj["type"] = JsonPrimitive("sse")
                    }
                    "me.rerere.rikkahub.data.mcp.McpServerConfig.StreamableHTTPServer" -> {
                        jsonObj["type"] = JsonPrimitive("streamable_http")
                    }
                }
                JsonObject(jsonObj)
            }
            JsonInstant.encodeToString(element)
        } ?: "[]"

        // 更新版本
        prefs[SettingsStore.VERSION] = 1

        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
