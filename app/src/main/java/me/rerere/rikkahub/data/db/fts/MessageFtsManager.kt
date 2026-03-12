package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

private const val TAG = "MessageFtsManager"
internal const val MESSAGE_FTS_TABLE_NAME = "message_fts"
internal val MESSAGE_FTS_REQUIRED_COLUMNS = setOf(
    "text",
    "node_id",
    "message_id",
    "conversation_id",
    "title",
    "update_at",
)
internal val MESSAGE_FTS_CREATE_SQL = """
    CREATE VIRTUAL TABLE IF NOT EXISTS $MESSAGE_FTS_TABLE_NAME USING fts5(
        text,
        node_id UNINDEXED,
        message_id UNINDEXED,
        conversation_id UNINDEXED,
        title UNINDEXED,
        update_at UNINDEXED,
        tokenize = 'simple'
    )
""".trimIndent()

internal data class MessageFtsSearchRow(
    val rowId: Long,
    val nodeId: String?,
    val messageId: String?,
    val conversationId: String?,
    val title: String?,
    val updateAtRaw: String?,
    val snippet: String?,
)

internal fun isMessageFtsSchemaCompatible(columns: Set<String>): Boolean =
    MESSAGE_FTS_REQUIRED_COLUMNS.all(columns::contains)

internal fun MessageFtsSearchRow.toSearchResultOrNull(): MessageSearchResult? {
    val nodeId = nodeId ?: return null
    val messageId = messageId ?: return null
    val conversationId = conversationId ?: return null
    val updateAtMillis = updateAtRaw?.toLongOrNull() ?: return null
    return MessageSearchResult(
        nodeId = nodeId,
        messageId = messageId,
        conversationId = conversationId,
        title = title.orEmpty(),
        updateAt = Instant.ofEpochMilli(updateAtMillis),
        snippet = snippet.orEmpty(),
    )
}

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM $MESSAGE_FTS_TABLE_NAME WHERE conversation_id = ?", arrayOf(conversationId))
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO $MESSAGE_FTS_TABLE_NAME(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            text,
                            node.id.toString(),
                            message.id.toString(),
                            conversationId,
                            conversation.title,
                            conversation.updateAt.toEpochMilli().toString(),
                        )
                    )
                }
            }
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM $MESSAGE_FTS_TABLE_NAME WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM $MESSAGE_FTS_TABLE_NAME")
    }

    suspend fun countRows(): Int = withContext(Dispatchers.IO) {
        db.query("SELECT COUNT(*) FROM $MESSAGE_FTS_TABLE_NAME").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    suspend fun search(keyword: String): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        val invalidRowIds = mutableListOf<Long>()
        val cursor = db.query(
            """
            SELECT rowid,
                   node_id,
                   message_id,
                   conversation_id,
                   COALESCE(title, '') AS title,
                   COALESCE(update_at, '') AS update_at,
                   COALESCE(simple_snippet($MESSAGE_FTS_TABLE_NAME, 0, '[', ']', '...', 30), '') AS snippet
            FROM $MESSAGE_FTS_TABLE_NAME
            WHERE text MATCH jieba_query(?)
            ORDER BY rank, update_at DESC
            LIMIT 50
            """.trimIndent(),
            arrayOf(keyword)
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                val row = MessageFtsSearchRow(
                    rowId = it.getLong(0),
                    nodeId = it.getString(1),
                    messageId = it.getString(2),
                    conversationId = it.getString(3),
                    title = it.getString(4),
                    updateAtRaw = it.getString(5),
                    snippet = it.getString(6),
                )
                val result = row.toSearchResultOrNull()
                if (result != null) {
                    results.add(result)
                } else {
                    invalidRowIds.add(row.rowId)
                }
            }
        }
        invalidRowIds.forEach { rowId ->
            Log.w(TAG, "search: deleting invalid FTS row rowId=$rowId")
            db.execSQL("DELETE FROM $MESSAGE_FTS_TABLE_NAME WHERE rowid = ?", arrayOf(rowId))
        }
        results
    }
}

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
