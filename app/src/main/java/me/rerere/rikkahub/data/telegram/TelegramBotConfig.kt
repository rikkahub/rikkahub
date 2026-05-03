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
) {
    val isUsable: Boolean get() = token.isNotBlank() && enabled
}
