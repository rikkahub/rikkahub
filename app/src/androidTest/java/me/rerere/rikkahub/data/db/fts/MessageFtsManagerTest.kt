package me.rerere.rikkahub.data.db.fts

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageFtsManagerTest {
    @Test
    fun assistantScopedSearchExecutesAndExcludesOtherAssistants() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL(
                """
                CREATE TABLE conversationentity (
                    id TEXT PRIMARY KEY,
                    assistant_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    update_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE VIRTUAL TABLE message_fts USING fts5(
                    text,
                    node_id UNINDEXED,
                    message_id UNINDEXED,
                    conversation_id UNINDEXED,
                    title UNINDEXED,
                    update_at UNINDEXED
                )
                """.trimIndent()
            )
            db.execSQL("INSERT INTO conversationentity VALUES ('conversation-a', 'assistant-a', 'A', 100)")
            db.execSQL("INSERT INTO conversationentity VALUES ('conversation-b', 'assistant-b', 'B', 200)")
            db.execSQL("INSERT INTO message_fts VALUES ('needle', 'node-a', 'message-a', 'conversation-a', 'A', 100)")
            db.execSQL("INSERT INTO message_fts VALUES ('needle', 'node-b', 'message-b', 'conversation-b', 'B', 200)")

            db.rawQuery(
                """
                SELECT message_fts.node_id, message_fts.message_id, message_fts.conversation_id,
                       message_fts.title, message_fts.update_at,
                       snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
                FROM message_fts
                INNER JOIN conversationentity ON conversationentity.id = message_fts.conversation_id
                WHERE message_fts.text MATCH ? AND conversationentity.assistant_id = ?
                ORDER BY message_fts.rank, message_fts.update_at DESC
                LIMIT 50
                """.trimIndent(),
                arrayOf("needle", "assistant-a")
            ).use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals("conversation-a", cursor.getString(2))
            }
        } finally {
            db.close()
        }
    }
}
