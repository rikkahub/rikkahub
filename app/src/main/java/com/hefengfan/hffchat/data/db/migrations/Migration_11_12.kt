package com.hefengfan.hffchat.data.db.migrations

import android.database.sqlite.SQLiteBlobTooBigException
import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import com.hefengfan.hffchat.data.db.DatabaseMigrationTracker
import com.hefengfan.hffchat.utils.JsonInstant
import kotlin.uuid.Uuid

private const val TAG = "Migration_11_12"

val Migration_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 11 to 12 (extracting message nodes to separate table)")
        DatabaseMigrationTracker.onMigrationStart(11, 12)
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
            val cursor = db.query("SELECT id FROM conversationentity")
            var migratedCount = 0
            var nodeCount = 0
            var skippedCount = 0

            while (cursor.moveToNext()) {
                val conversationId = cursor.getString(0)
                try {
                    val nodeCursor = db.query(
                        "SELECT nodes FROM conversationentity WHERE id = ?",
                        arrayOf(conversationId)
                    )
                    try {
                        if (!nodeCursor.moveToFirst()) {
                            continue
                        }
                        val nodesJson = nodeCursor.getString(0)
                        // 使用原始 JSON 解析，避免因 UIMessagePart 类型名变更导致的反序列化失败
                        // 同时应用类型名映射（与 Migration_13_14 相同的逻辑）
                        val nodesArray = runCatching {
                            JsonInstant.parseToJsonElement(nodesJson) as? JsonArray
                        }.getOrNull() ?: JsonArray(emptyList())

                        nodesArray.forEachIndexed { index, nodeElement ->
                            val nodeObject = nodeElement as? JsonObject ?: return@forEachIndexed
                            val messagesElement = nodeObject["messages"] ?: JsonArray(emptyList())
                            // 迁移消息中的 UIMessagePart 类型名（旧完整类名 -> 新 @SerialName）
                            val migratedMessages = migrateMessagesElement(messagesElement)
                            val messagesJson = JsonInstant.encodeToString(migratedMessages)
                            val selectIndex = runCatching {
                                nodeObject["selectIndex"]?.jsonPrimitive?.int ?: 0
                            }.getOrDefault(0)
                            val nodeId = Uuid.random().toString()
                            db.execSQL(
                                "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, ?)",
                                arrayOf(nodeId, conversationId, index, messagesJson, selectIndex)
                            )
                            nodeCount++
                        }
                        db.execSQL(
                            "UPDATE conversationentity SET nodes = '[]' WHERE id = ?",
                            arrayOf(conversationId)
                        )
                        migratedCount++
                    } finally {
                        nodeCursor.close()
                    }
                } catch (e: SQLiteBlobTooBigException) {
                    skippedCount++
                    Log.w(TAG, "migrate: skip conversation $conversationId due to large nodes blob", e)
                    continue
                }
            }
            cursor.close()

            db.setTransactionSuccessful()
            Log.i(
                TAG,
                "migrate: migrate from 11 to 12 success ($migratedCount conversations, $nodeCount nodes, $skippedCount skipped)"
            )
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
