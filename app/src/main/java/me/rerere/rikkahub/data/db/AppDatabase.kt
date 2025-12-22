package me.rerere.rikkahub.data.db

import android.util.Log
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "AppDatabase"

@Database(
    entities = [ConversationEntity::class, MemoryEntity::class, GenMediaEntity::class, MessageNodeEntity::class],
    version = 12,
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
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO
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

val Migration_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 6 to 7")
        db.beginTransaction()
        try {
            // 创建新表结构（不包含messages列）
            db.execSQL(
                """
                CREATE TABLE ConversationEntity_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    assistant_id TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e',
                    title TEXT NOT NULL,
                    nodes TEXT NOT NULL,
                    usage TEXT,
                    create_at INTEGER NOT NULL,
                    update_at INTEGER NOT NULL,
                    truncate_index INTEGER NOT NULL DEFAULT -1
                )
            """.trimIndent()
            )

            // 获取所有对话记录并转换数据
            val cursor =
                db.query("SELECT id, assistant_id, title, messages, usage, create_at, update_at, truncate_index FROM ConversationEntity")
            val updates = mutableListOf<Array<Any?>>()

            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val assistantId = cursor.getString(1)
                val title = cursor.getString(2)
                val messagesJson = cursor.getString(3)
                val usage = cursor.getString(4)
                val createAt = cursor.getLong(5)
                val updateAt = cursor.getLong(6)
                val truncateIndex = cursor.getInt(7)

                try {
                    // 尝试解析旧格式的消息列表 List<UIMessage>
                    val oldMessages = JsonInstant.decodeFromString<List<UIMessage>>(messagesJson)

                    // 转换为新格式 List<MessageNode>
                    val newMessages = oldMessages.map { message ->
                        MessageNode.of(message)
                    }

                    // 序列化新格式
                    val newMessagesJson = JsonInstant.encodeToString(newMessages)
                    updates.add(
                        arrayOf(
                            id,
                            assistantId,
                            title,
                            newMessagesJson,
                            usage,
                            createAt,
                            updateAt,
                            truncateIndex
                        )
                    )
                } catch (e: Exception) {
                    // 如果解析失败，可能已经是新格式或者数据损坏，跳过
                    error("Failed to migrate messages for conversation $id: ${e.message}")
                }
            }
            cursor.close()

            // 批量插入数据到新表
            updates.forEach { values ->
                db.execSQL(
                    "INSERT INTO ConversationEntity_new (id, assistant_id, title, nodes, usage, create_at, update_at, truncate_index) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    values
                )
            }

            // 删除旧表
            db.execSQL("DROP TABLE ConversationEntity")

            // 重命名新表
            db.execSQL("ALTER TABLE ConversationEntity_new RENAME TO ConversationEntity")

            db.setTransactionSuccessful()

            Log.i(TAG, "migrate: migrate from 6 to 7 success (${updates.size} conversations updated)")
        } finally {
            db.endTransaction()
        }
    }
}

@DeleteColumn(tableName = "ConversationEntity", columnName = "usage")
class Migration_8_9 : AutoMigrationSpec

val Migration_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 11 to 12 (extracting message nodes to separate table)")
        db.beginTransaction()
        try {
            // 1. 创建 message_node 表
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS message_node (
                    id TEXT NOT NULL PRIMARY KEY,
                    conversation_id TEXT NOT NULL,
                    node_index INTEGER NOT NULL,
                    messages TEXT NOT NULL,
                    select_index INTEGER NOT NULL,
                    FOREIGN KEY (conversation_id) REFERENCES ConversationEntity(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_message_node_conversation_id ON message_node(conversation_id)")

            // 2. 从 conversationentity.nodes 迁移数据到 message_node
            val cursor = db.query("SELECT id, nodes FROM conversationentity")
            var migratedCount = 0
            var nodeCount = 0

            while (cursor.moveToNext()) {
                val conversationId = cursor.getString(0)
                val nodesJson = cursor.getString(1)

                try {
                    val nodes = JsonInstant.decodeFromString<List<MessageNode>>(nodesJson)
                    nodes.forEachIndexed { index, node ->
                        val nodeId = node.id.toString()
                        val messagesJson = JsonInstant.encodeToString(node.messages)
                        db.execSQL(
                            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, ?)",
                            arrayOf(nodeId, conversationId, index, messagesJson, node.selectIndex)
                        )
                        nodeCount++
                    }
                    migratedCount++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to migrate nodes for conversation $conversationId: ${e.message}")
                }
            }
            cursor.close()

            // 3. 清空旧的 nodes 列（保留列结构以保持兼容性）
            db.execSQL("UPDATE conversationentity SET nodes = '[]'")

            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 11 to 12 success ($migratedCount conversations, $nodeCount nodes)")
        } finally {
            db.endTransaction()
        }
    }
}

