package me.rerere.rikkahub.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.db.dao.AgentEventDAO
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.dao.KnowledgeChunkDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryVectorDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.ShellRunDAO
import me.rerere.rikkahub.data.db.dao.TaskRunDAO
import me.rerere.rikkahub.data.db.dao.TaskScheduleDAO
import me.rerere.rikkahub.data.db.dao.WorkItemDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.AgentEventEntity
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeChunkEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryVectorEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.ShellRunEntity
import me.rerere.rikkahub.data.db.entity.TaskRunEntity
import me.rerere.rikkahub.data.db.entity.TaskScheduleEntity
import me.rerere.rikkahub.data.db.entity.WorkItemDependencyEntity
import me.rerere.rikkahub.data.db.entity.WorkItemEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.migrations.Migration_16_17
import me.rerere.rikkahub.data.db.migrations.Migration_8_9
import me.rerere.common.json.JsonInstant

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        KnowledgeChunkEntity::class,
        MemoryVectorEntity::class,
        WorkspaceEntity::class,
        TaskRunEntity::class,
        WorkItemEntity::class,
        WorkItemDependencyEntity::class,
        TaskScheduleEntity::class,
        AgentEventEntity::class,
        ShellRunEntity::class
    ],
    version = 30,
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
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        // 24 -> 25 is purely additive (three new tables: task_runs, work_items,
        // work_item_dependencies); no existing table is altered, so Room derives the migration
        // from the exported schemas without a spec.
        AutoMigration(from = 24, to = 25),
        // 25 -> 26 is purely additive (one new table: task_schedules); no existing table is
        // altered, so Room derives the migration from the exported schemas without a spec.
        AutoMigration(from = 25, to = 26),
        // 27 -> 28 is purely additive (one new table: agent_events, issue #290); no existing table
        // is altered, so Room derives the migration from the exported schemas without a spec.
        AutoMigration(from = 27, to = 28),
        // 28 -> 29 is purely additive (one new table: shell_runs, issue #291); no existing table is
        // altered, so Room derives the migration from the exported schemas without a spec.
        AutoMigration(from = 28, to = 29),
        // 29 -> 30 adds status-leading indexes on agent_events and shell_runs so the cold-start
        // replay/recovery scans (status-only predicates) stop full-scanning. Index creation is
        // auto-migratable, so Room derives the migration from the exported schemas without a spec.
        AutoMigration(from = 29, to = 30),
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

    abstract fun knowledgeChunkDao(): KnowledgeChunkDAO

    abstract fun memoryVectorDao(): MemoryVectorDAO

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun taskRunDao(): TaskRunDAO

    abstract fun workItemDao(): WorkItemDAO

    abstract fun taskScheduleDao(): TaskScheduleDAO

    abstract fun agentEventDao(): AgentEventDAO

    abstract fun shellRunDao(): ShellRunDAO
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
