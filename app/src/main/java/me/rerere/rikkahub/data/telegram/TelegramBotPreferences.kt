package me.rerere.rikkahub.data.telegram

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.telegramDataStore by preferencesDataStore(name = "telegram_bot")

class TelegramBotPreferences(private val context: Context) {
    private val store = context.telegramDataStore

    private val K_TOKEN = stringPreferencesKey("token")
    private val K_ENABLED = booleanPreferencesKey("enabled")
    private val K_DEFAULT_CHAT_ID = longPreferencesKey("default_chat_id")
    private val K_WHITELIST = stringPreferencesKey("whitelist")
    private val K_ASSISTANT_ID = stringPreferencesKey("assistant_id")

    val flow = store.data.map { p ->
        TelegramBotConfig(
            token = p[K_TOKEN].orEmpty(),
            enabled = p[K_ENABLED] == true,
            defaultChatId = p[K_DEFAULT_CHAT_ID],
            whitelist = parseWhitelist(p[K_WHITELIST].orEmpty()),
            assistantId = p[K_ASSISTANT_ID],
        )
    }

    suspend fun current(): TelegramBotConfig = flow.first()

    suspend fun update(fn: (TelegramBotConfig) -> TelegramBotConfig) {
        store.edit { p ->
            val cur = TelegramBotConfig(
                token = p[K_TOKEN].orEmpty(),
                enabled = p[K_ENABLED] == true,
                defaultChatId = p[K_DEFAULT_CHAT_ID],
                whitelist = parseWhitelist(p[K_WHITELIST].orEmpty()),
                assistantId = p[K_ASSISTANT_ID],
            )
            val next = fn(cur)
            p[K_TOKEN] = next.token
            p[K_ENABLED] = next.enabled
            if (next.defaultChatId != null) p[K_DEFAULT_CHAT_ID] = next.defaultChatId
            else p.remove(K_DEFAULT_CHAT_ID)
            p[K_WHITELIST] = next.whitelist.sorted().joinToString(",")
            if (next.assistantId != null) p[K_ASSISTANT_ID] = next.assistantId
            else p.remove(K_ASSISTANT_ID)
        }
    }

    private fun parseWhitelist(s: String): Set<Long> =
        s.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
}
