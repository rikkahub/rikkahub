package me.rerere.rikkahub.data.db.fts

import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
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

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("message_fts.rank, conversation.update_at DESC"),
    NEWEST_FIRST("conversation.update_at DESC, message_fts.rank"),
    OLDEST_FIRST("conversation.update_at ASC, message_fts.rank"),
}

private const val TAG = "MessageFtsManager"

/**
 * 建立消息索引用到的表，每次打开数据库时调用。
 *
 * message_fts 是 FTS5 虚表，除 rowid 外的列都无法用索引定位，按 conversation_id 删除会扫描全部消息；
 * 所以另用普通表 message_fts_rowid 记录每行索引属于哪个会话，按会话增删都先经它查出 rowid。
 * title、update_at 两列已不再写入，搜索时从 conversationentity 读取，保留只是为了已有索引不用重建。
 */
internal fun createMessageFtsTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
            text,
            node_id UNINDEXED,
            message_id UNINDEXED,
            conversation_id UNINDEXED,
            title UNINDEXED,
            update_at UNINDEXED,
            tokenize = 'simple'
        )
        """.trimIndent()
    )
    val hasRowidTable = db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'message_fts_rowid'")
        .use { it.moveToFirst() }
    if (hasRowidTable) return
    db.beginTransaction()
    try {
        db.execSQL("CREATE TABLE message_fts_rowid(fts_rowid INTEGER PRIMARY KEY, conversation_id TEXT NOT NULL)")
        db.execSQL("CREATE INDEX index_message_fts_rowid_conversation_id ON message_fts_rowid(conversation_id)")
        // 旧版本建好的索引只需补上对应关系，读一遍 message_fts 的内容表，不用重新分词
        db.execSQL(
            "INSERT INTO message_fts_rowid(fts_rowid, conversation_id) SELECT rowid, conversation_id FROM message_fts"
        )
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

private class IndexedMessage(val rowid: Long, val nodeId: String, val text: String)

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    /**
     * 增量更新会话的索引：只重写新增或内容变了的消息，删掉已不存在的消息。
     * 每次保存会话都会调用，长会话里大部分消息没变，不必整份删掉重新分词。
     */
    suspend fun indexConversation(conversation: Conversation) = database.withTransaction {
        val conversationId = conversation.id.toString()
        val indexed = HashMap<String, IndexedMessage>()
        val stale = mutableListOf<Long>()
        db.query(
            """
            SELECT f.rowid, f.node_id, f.message_id, f.text
            FROM message_fts_rowid AS r JOIN message_fts AS f ON f.rowid = r.fts_rowid
            WHERE r.conversation_id = ?
            """.trimIndent(),
            arrayOf(conversationId)
        ).use {
            while (it.moveToNext()) {
                val row = IndexedMessage(it.getLong(0), it.getString(1), it.getString(3))
                if (indexed.putIfAbsent(it.getString(2), row) != null) stale.add(row.rowid)
            }
        }
        conversation.messageNodes.forEach { node ->
            val nodeId = node.id.toString()
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                val existing = indexed.remove(message.id.toString())
                if (existing != null && existing.nodeId == nodeId && existing.text == text) return@forEach
                existing?.let { stale.add(it.rowid) }
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id) VALUES (?, ?, ?, ?)",
                        arrayOf(text, nodeId, message.id.toString(), conversationId)
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO message_fts_rowid(fts_rowid, conversation_id) VALUES (last_insert_rowid(), ?)",
                        arrayOf(conversationId)
                    )
                }
            }
        }
        indexed.values.forEach { stale.add(it.rowid) }
        stale.forEach { rowid ->
            db.execSQL("DELETE FROM message_fts WHERE rowid = ?", arrayOf(rowid))
            db.execSQL("DELETE FROM message_fts_rowid WHERE fts_rowid = ?", arrayOf(rowid))
        }
    }

    suspend fun deleteConversation(conversationId: String) = database.withTransaction {
        db.execSQL(
            "DELETE FROM message_fts WHERE rowid IN (SELECT fts_rowid FROM message_fts_rowid WHERE conversation_id = ?)",
            arrayOf(conversationId)
        )
        db.execSQL("DELETE FROM message_fts_rowid WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = database.withTransaction {
        db.execSQL("DELETE FROM message_fts")
        db.execSQL("DELETE FROM message_fts_rowid")
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        // 只搜这些助手的对话，null 表示不限
        assistantIds: Collection<String>? = null,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        if (assistantIds != null && assistantIds.isEmpty()) return@withContext results
        val matchQuery = buildFtsMatchQuery(keyword, ::simpleQuery) ?: return@withContext results
        val assistantFilter = if (assistantIds != null) {
            "AND conversation.assistant_id IN (${assistantIds.joinToString { "?" }})"
        } else {
            ""
        }
        // 标题和更新时间以会话表为准，改标题、发消息都不用改写索引
        val cursor = db.query(
            """
            SELECT message_fts.node_id, message_fts.message_id, message_fts.conversation_id,
                   conversation.title, conversation.update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            JOIN conversationentity AS conversation ON conversation.id = message_fts.conversation_id
            WHERE message_fts.text MATCH ?
            $assistantFilter
            ORDER BY ${sort.orderBy}
            LIMIT 50
            """.trimIndent(),
            arrayOf(matchQuery, *assistantIds.orEmpty().toTypedArray())
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        results
    }

    // simple 扩展生成的查询，带拼音展开和前缀匹配
    private fun simpleQuery(text: String): String =
        db.query(
            "SELECT simple_query(?)",
            arrayOf(text)
        ).use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
}

private val QUERY_RUN = Regex("[A-Za-z0-9]+|[^\\sA-Za-z0-9]+")

// 中文片段各字之间最多允许夹几个额外的字（如标点、“的”），再大就容易把散落各处的字也算作命中
private const val CJK_NEAR_SLACK = 2

/**
 * 把搜索词拼成 FTS5 查询，各段之间用 AND 连接：
 * 英文和数字交给 [asciiQuery]（simple_query，支持拼音和前缀匹配）；
 * 索引按单字切分中文，所以中文等其他字符的连续片段拆成单字，用 NEAR 要求它们挨得足够近，
 * 效果接近按词匹配，又不需要 jieba 词典。
 * 没有可搜索的内容时返回 null。
 */
internal fun buildFtsMatchQuery(keyword: String, asciiQuery: (String) -> String): String? {
    val terms = QUERY_RUN.findAll(keyword).mapNotNull { match ->
        val text = match.value
        val term = if (text.first().isAsciiLetterOrDigit()) asciiQuery(text) else nearQuery(text)
        term.takeIf { it.isNotBlank() }
    }.toList()
    return terms.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
}

private fun nearQuery(text: String): String {
    val chars = text.codePoints().toArray().map { "\"${String(Character.toChars(it)).replace("\"", "\"\"")}\"" }
    if (chars.size == 1) return chars.single()
    return "NEAR(${chars.joinToString(" ")}, ${chars.size - 2 + CJK_NEAR_SLACK})"
}

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
