package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.migrateToolNodes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_15_16"

val Migration_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 15 to 16 (eager tool message migration)")
        DatabaseMigrationTracker.onMigrationStart(15, 16)
        db.beginTransaction()
        try {
            data class NodeRow(val id: String, val messages: List<UIMessage>, val selectIndex: Int)

            // Get all distinct conversation IDs
            val convCursor = db.query("SELECT DISTINCT conversation_id FROM message_node")
            val conversationIds = mutableListOf<String>()
            while (convCursor.moveToNext()) {
                conversationIds.add(convCursor.getString(0))
            }
            convCursor.close()

            var updatedConversations = 0

            for (conversationId in conversationIds) {
                // Load all nodes for this conversation ordered by node_index
                val nodeCursor = db.query(
                    "SELECT id, messages, node_index, select_index FROM message_node WHERE conversation_id = ? ORDER BY node_index ASC",
                    arrayOf(conversationId)
                )

                val rows = mutableListOf<NodeRow>()
                while (nodeCursor.moveToNext()) {
                    val id = nodeCursor.getString(0)
                    val messagesJson = nodeCursor.getString(1)
                    val selectIndex = nodeCursor.getInt(3)
                    runCatching {
                        val messages = JsonInstant.decodeFromString<List<UIMessage>>(messagesJson)
                        rows.add(NodeRow(id, messages, selectIndex))
                    }.onFailure {
                        Log.w(TAG, "migrate: failed to parse messages for node $id", it)
                    }
                }
                nodeCursor.close()

                if (rows.isEmpty()) continue

                // Apply migration: merge TOOL role nodes into preceding ASSISTANT nodes,
                // and convert legacy ToolCall/ToolResult parts to the unified Tool part
                val migrated = rows.migrateToolNodes(
                    getMessages = { it.messages },
                    setMessages = { row, msgs -> row.copy(messages = msgs) }
                )

                // Skip if nothing changed
                val changed = migrated.size != rows.size ||
                    migrated.zip(rows).any { (a, b) -> a.messages != b.messages }
                if (!changed) continue

                // Delete old nodes and re-insert migrated ones with corrected node_index
                db.execSQL("DELETE FROM message_node WHERE conversation_id = ?", arrayOf(conversationId))
                migrated.forEachIndexed { index, row ->
                    val messagesJson = JsonInstant.encodeToString(row.messages)
                    db.execSQL(
                        "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, ?)",
                        arrayOf<Any?>(row.id, conversationId, index, messagesJson, row.selectIndex)
                    )
                }
                updatedConversations++
            }

            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 15 to 16 success ($updatedConversations conversations updated)")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
