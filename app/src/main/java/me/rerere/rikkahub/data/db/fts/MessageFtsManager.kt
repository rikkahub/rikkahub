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

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
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

    suspend fun updateTitle(conversationId: String, title: String) = withContext(Dispatchers.IO) {
        db.execSQL("UPDATE message_fts SET title = ? WHERE conversation_id = ?", arrayOf(title, conversationId))
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
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
            """
            AND EXISTS (
                SELECT 1 FROM conversationentity AS conversation
                WHERE conversation.id = message_fts.conversation_id
                  AND conversation.assistant_id IN (${assistantIds.joinToString { "?" }})
            )
            """.trimIndent()
        } else {
            ""
        }
        val cursor = db.query(
            """
            SELECT node_id, message_id, conversation_id, title, update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            WHERE text MATCH ?
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
