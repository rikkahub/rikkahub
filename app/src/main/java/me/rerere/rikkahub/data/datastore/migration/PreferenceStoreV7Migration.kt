package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import me.rerere.common.json.JsonInstant
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * v6 -> v7: fold the standalone ChatGPT provider into OpenAI as a mode.
 *
 * ChatGPT (the Codex subscription backend) used to be its own [me.rerere.ai.provider.ProviderSetting]
 * subtype with the polymorphic discriminator `"type":"chatgpt"`. It is now an
 * [me.rerere.ai.provider.OpenAIMode.ChatGPT] mode of an [me.rerere.ai.provider.ProviderSetting.OpenAI]
 * record (one provider tab fewer). The `chatgpt` subtype no longer exists, so an existing user's
 * persisted `{"type":"chatgpt",...}` provider would fail polymorphic decode — this migration rewrites
 * each one to `{"type":"openai","mode":"chatgpt",...}` BEFORE any decode runs (DataStore migrations
 * complete during store initialization, ahead of the first read).
 *
 * The rewrite is minimal and lossless: the ChatGPT JSON already carries `id`, `enabled`, `name`,
 * `models`, `balanceOption`, `accessToken`, and `baseUrl` — all valid OpenAI keys (OpenAI gained an
 * `accessToken` field for exactly this). Only the discriminator changes and `mode` is added; every
 * OpenAI-specific key absent from the old record (`apiKey`, `chatCompletionsPath`, `useResponseApi`,
 * `includeHistoryReasoning`) decodes to its default. Operating on raw JSON (not a decoded object)
 * mirrors [PreferenceStoreV6Migration] and avoids `ignoreUnknownKeys` silently dropping fields.
 */
class PreferenceStoreV7Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 7
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        val existing: List<JsonObject> = currentData[SettingsStore.PROVIDERS]
            ?.let { runCatching { JsonInstant.parseToJsonElement(it).jsonArray }.getOrNull() }
            ?.filterIsInstance<JsonObject>()
            ?: emptyList()

        val converted = existing.map { provider ->
            if ((provider["type"] as? JsonPrimitive)?.contentOrNull == CHATGPT_TYPE) {
                JsonObject(
                    provider.toMutableMap().apply {
                        put("type", JsonPrimitive(OPENAI_TYPE))
                        put("mode", JsonPrimitive(CHATGPT_MODE))
                    }
                )
            } else {
                provider
            }
        }

        // Only rewrite the PROVIDERS blob when something actually changed, but always bump the version
        // so the gate closes (a user with no ChatGPT provider still advances to v7 and never re-runs).
        if (converted != existing) {
            prefs[SettingsStore.PROVIDERS] = JsonArray(converted).toString()
        }
        prefs[SettingsStore.VERSION] = 7
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}

    private companion object {
        const val CHATGPT_TYPE = "chatgpt"   // the retired ProviderSetting.ChatGPT @SerialName
        const val OPENAI_TYPE = "openai"     // ProviderSetting.OpenAI @SerialName
        const val CHATGPT_MODE = "chatgpt"   // OpenAIMode.ChatGPT @SerialName
    }
}
