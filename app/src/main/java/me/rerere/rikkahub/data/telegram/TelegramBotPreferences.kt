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
    private val K_CUSTOM_COMMANDS = stringPreferencesKey("custom_commands")
    private val K_STREAM_SCREENSHOTS = booleanPreferencesKey("stream_screenshots")
    // Long-poll update_id offset survives process death. Without this, an OEM kill
    // followed by a restart would re-process up to 24 h of cached updates (Telegram
    // retains unconfirmed updates server-side) — the user would see the bot replying
    // to messages from yesterday on every cold start. Kept out of TelegramBotConfig
    // because it's runtime state, not user-facing configuration.
    private val K_LAST_OFFSET = longPreferencesKey("last_offset")

    val flow = store.data.map { p -> readConfig(p) }

    suspend fun current(): TelegramBotConfig = flow.first()

    suspend fun update(fn: (TelegramBotConfig) -> TelegramBotConfig) {
        store.edit { p ->
            val cur = readConfig(p)
            val next = fn(cur)
            p[K_TOKEN] = next.token
            p[K_ENABLED] = next.enabled
            if (next.defaultChatId != null) p[K_DEFAULT_CHAT_ID] = next.defaultChatId
            else p.remove(K_DEFAULT_CHAT_ID)
            p[K_WHITELIST] = next.whitelist.sorted().joinToString(",")
            if (next.assistantId != null) p[K_ASSISTANT_ID] = next.assistantId
            else p.remove(K_ASSISTANT_ID)
            if (next.customCommands.isNotEmpty()) {
                p[K_CUSTOM_COMMANDS] = serializeCustomCommands(next.customCommands)
            } else p.remove(K_CUSTOM_COMMANDS)
            p[K_STREAM_SCREENSHOTS] = next.streamScreenshots
        }
    }

    private fun readConfig(p: androidx.datastore.preferences.core.Preferences): TelegramBotConfig =
        TelegramBotConfig(
            token = p[K_TOKEN].orEmpty(),
            enabled = p[K_ENABLED] == true,
            defaultChatId = p[K_DEFAULT_CHAT_ID],
            whitelist = parseWhitelist(p[K_WHITELIST].orEmpty()),
            assistantId = p[K_ASSISTANT_ID],
            customCommands = parseCustomCommands(p[K_CUSTOM_COMMANDS].orEmpty()),
            // Default to true — preference key absent on first launch means "stream on",
            // matching the default in TelegramBotConfig.
            streamScreenshots = p[K_STREAM_SCREENSHOTS] != false,
        )

    /** Last update_id confirmed to Telegram. The next getUpdates pass uses this + 1
     *  so server-cached updates from before a process restart don't replay. */
    suspend fun lastOffset(): Long = store.data.first()[K_LAST_OFFSET] ?: 0L

    /** Persist after every successful getUpdates cycle. Reset to 0 on token change
     *  (handled by the poll loop). */
    suspend fun setLastOffset(offset: Long) {
        store.edit { p -> p[K_LAST_OFFSET] = offset }
    }

    private fun parseWhitelist(s: String): Set<Long> =
        s.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()

    /** "name|description\nname|description" → list of pairs. Empty input → empty list. */
    private fun parseCustomCommands(s: String): List<Pair<String, String>> {
        if (s.isBlank()) return emptyList()
        return s.split("\n").mapNotNull { line ->
            val idx = line.indexOf('|')
            if (idx <= 0 || idx == line.lastIndex) null
            else line.substring(0, idx) to line.substring(idx + 1)
        }
    }

    private fun serializeCustomCommands(list: List<Pair<String, String>>): String =
        list.joinToString("\n") { "${it.first}|${it.second}" }
}
