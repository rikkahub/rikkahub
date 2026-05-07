package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.TelegramChatEntity

@Dao
interface TelegramChatDao {
    @Query("SELECT * FROM telegram_chats WHERE chatId = :chatId LIMIT 1")
    suspend fun getByChatId(chatId: Long): TelegramChatEntity?

    @Query("SELECT * FROM telegram_chats WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getByConversationId(conversationId: String): TelegramChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TelegramChatEntity)

    @Query("DELETE FROM telegram_chats WHERE chatId = :chatId")
    suspend fun deleteByChatId(chatId: Long)

    @Query("UPDATE telegram_chats SET lastMessageAtMs = :ms WHERE chatId = :chatId")
    suspend fun touch(chatId: Long, ms: Long)
}
