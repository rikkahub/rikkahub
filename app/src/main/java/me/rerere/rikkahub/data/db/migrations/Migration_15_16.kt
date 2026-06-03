package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_15_16"

val Migration_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 15 to 16 (eager tool message migration)")
        DatabaseMigrationTracker.onMigrationStart(15, 16)
        db.beginTransaction()
        try {
            // Get all distinct conversation IDs
            val convCursor = db.query("SELECT DISTINCT conversation_id FROM message_node")
            val conversationIds = mutableListOf<String>()
            while (convCursor.moveToNext()) {
                conversationIds.add(convCursor.getString(0))
            }
            convCursor.close()

            var updatedConversations = 0

            for (conversationId in conversationIds) {
                // Load all nodes for this conversation ordered by node_index.
                // Undecodable nodes are kept as MigrationNodeRow(messages = null) so the decision
                // function can preserve them instead of dropping them (issue #9 data loss).
                val nodeCursor = db.query(
                    "SELECT id, messages, node_index, select_index FROM message_node WHERE conversation_id = ? ORDER BY node_index ASC",
                    arrayOf(conversationId)
                )

                val rows = mutableListOf<MigrationNodeRow>()
                while (nodeCursor.moveToNext()) {
                    val id = nodeCursor.getString(0)
                    val messagesJson = nodeCursor.getString(1)
                    val selectIndex = nodeCursor.getInt(3)
                    val messages = runCatching {
                        JsonInstant.decodeFromString<List<UIMessage>>(messagesJson)
                    }.onFailure {
                        Log.w(TAG, "migrate: failed to parse messages for node $id", it)
                    }.getOrNull()
                    rows.add(MigrationNodeRow(id, messages, selectIndex))
                }
                nodeCursor.close()

                // Decide whether to rewrite this conversation. If any node failed to decode,
                // the decision is Skip and we leave every original row exactly as stored.
                val decision = decideMigration15To16(rows)
                if (decision !is MigrationDecision.Rewrite) continue

                // Delete old nodes and re-insert migrated ones with corrected node_index
                db.execSQL("DELETE FROM message_node WHERE conversation_id = ?", arrayOf(conversationId))
                decision.rows.forEachIndexed { index, row ->
                    val messagesJson = JsonInstant.encodeToString(row.messages!!)
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
