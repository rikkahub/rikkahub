package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Maps a Telegram chat to an in-app rikkahub conversation so that the bot's responses to
 * the same Telegram chat preserve conversational context across turns.
 *
 * One chat → one active conversation. The /reset command (or telegram_reset_chat tool) clears
 * this row, so the next inbound message creates a fresh conversation.
 */
@Entity(tableName = "telegram_chats")
data class TelegramChatEntity(
    @PrimaryKey val chatId: Long,
    /** Stringified UUID of the in-app Conversation this chat is bound to. */
    val conversationId: String,
    val createdAtMs: Long,
    val lastMessageAtMs: Long,
)
