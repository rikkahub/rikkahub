package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

class PreferenceStoreV5Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 5
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        prefs[SettingsStore.PROVIDERS]?.let { json ->
            prefs[SettingsStore.PROVIDERS] = migrateProvidersQuotaQuery(json)
        }

        prefs[SettingsStore.VERSION] = 5
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/** 对提供商列表应用 [migrateProviderQuotaQuery] */
internal fun migrateProvidersQuotaQuery(providersJson: String): String {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(providersJson) as? JsonArray
            ?: return@runCatching providersJson
        JsonInstant.encodeToString(JsonArray(root.map(::migrateProviderQuotaQuery)))
    }.getOrDefault(providersJson)
}

/**
 * 余额查询 `balanceOption` 改成自定义额度查询 `quotaQuery`。
 *
 * 旧的查询只对 OpenAI 类型生效：GET `baseUrl + apiPath`（apiPath 是完整地址时直接用），带 Bearer key，
 * 这里转成等价的模板。Google、Claude 上的旧配置界面上从没开放过，直接丢掉。
 * 模型级覆盖里嵌套的提供商配置一并处理。
 */
internal fun migrateProviderQuotaQuery(provider: JsonElement): JsonElement {
    val obj = provider as? JsonObject ?: return provider
    val migrated = obj.toMutableMap()
    val balanceOption = migrated.remove(LEGACY_KEY) as? JsonObject
    val type = obj["type"]?.jsonPrimitiveOrNull?.contentOrNull
    if (balanceOption != null && type == "openai" && "quotaQuery" !in obj) {
        migrated["quotaQuery"] = balanceOption.toQuotaQuery()
    }
    (obj["models"] as? JsonArray)?.let { models ->
        migrated["models"] = JsonArray(
            models.map { model ->
                val modelObj = model as? JsonObject ?: return@map model
                val override = modelObj["providerOverwrite"] as? JsonObject ?: return@map model
                JsonObject(modelObj + ("providerOverwrite" to migrateProviderQuotaQuery(override)))
            }
        )
    }
    return JsonObject(migrated)
}

private fun JsonObject.toQuotaQuery(): JsonObject {
    val apiPath = get("apiPath")?.jsonPrimitiveOrNull?.contentOrNull ?: "/credits"
    return buildJsonObject {
        put("enabled", get("enabled")?.jsonPrimitiveOrNull?.booleanOrNull ?: false)
        put("url", if (apiPath.startsWith("http")) apiPath else "{{baseUrl}}$apiPath")
        putJsonArray("headers") {
            addJsonObject {
                put("name", "Authorization")
                put("value", "Bearer {{apiKey}}")
            }
        }
        put("resultPath", get("resultPath")?.jsonPrimitiveOrNull?.contentOrNull ?: "data.total_usage")
    }
}

private const val LEGACY_KEY = "balanceOption"
