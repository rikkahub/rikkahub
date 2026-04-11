package com.hefengfan.hffchat.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import com.hefengfan.hffchat.data.db.dao.ConversationDAO
import com.hefengfan.hffchat.data.db.dao.FavoriteDAO
import com.hefengfan.hffchat.data.db.dao.GenMediaDAO
import com.hefengfan.hffchat.data.db.dao.ManagedFileDAO
import com.hefengfan.hffchat.data.db.dao.MemoryDAO
import com.hefengfan.hffchat.data.db.dao.MessageNodeDAO
import com.hefengfan.hffchat.data.db.entity.ConversationEntity
import com.hefengfan.hffchat.data.db.entity.FavoriteEntity
import com.hefengfan.hffchat.data.db.entity.GenMediaEntity
import com.hefengfan.hffchat.data.db.entity.ManagedFileEntity
import com.hefengfan.hffchat.data.db.entity.MemoryEntity
import com.hefengfan.hffchat.data.db.entity.MessageNodeEntity
import com.hefengfan.hffchat.data.db.migrations.Migration_16_17
import com.hefengfan.hffchat.data.db.migrations.Migration_8_9
import com.hefengfan.hffchat.utils.JsonInstant

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class
    ],
    version = 17,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
