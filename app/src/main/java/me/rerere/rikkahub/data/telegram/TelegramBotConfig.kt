package me.rerere.rikkahub.data.telegram

/** Persisted bot configuration. Lives in TelegramBotPreferences (DataStore). */
data class TelegramBotConfig(
    val token: String = "",
    val enabled: Boolean = false,
    /** Outbound default — used by telegram_send_message when chat_id is omitted. */
    val defaultChatId: Long? = null,
    /** User/chat IDs allowed to talk to the bot. Empty == nobody. */
    val whitelist: Set<Long> = emptySet(),
    /** Stringified UUID of the assistant that handles inbound messages. Null == use the user's current assistant. */
    val assistantId: String? = null,
    /**
     * Custom slash commands added by the LLM via telegram_set_commands. Built-in commands
     * (/start, /help, /new, /stop, /status, /model, /ratelimit) are NOT in this list —
     * they're hard-coded and always re-registered on bot startup. This list is what we
     * MERGE with the built-ins every time the Telegram menu is refreshed, so custom
     * commands the user asked the model to add survive app restarts.
     *
     * (commandName-without-slash, description) pairs. Stored as "name|description" lines
     * in DataStore for simplicity (no JSON serializer dep needed for one tiny field).
     */
    val customCommands: List<Pair<String, String>> = emptyList(),
    /**
     * Auto-stream screenshots to Telegram after every state-changing browser tool and
     * after every interactive tool fires from a headless (Telegram) conversation. Default
     * on. Toggleable via the `/stream` slash command — users on metered data may want it
     * off. The streamers (TelegramBrowserScreenshotStreamer, TelegramInteractiveToolStreamer)
     * each read this flag at send-time and short-circuit when false.
     */
    val streamScreenshots: Boolean = true,
) {
    val isUsable: Boolean get() = token.isNotBlank() && enabled
}
