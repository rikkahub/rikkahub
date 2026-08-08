package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class Migration_24_25_Test {
    private val testDatabase = "migration-24-25-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate24To25_backfillsAndPreservesTokenUsage() {
        val conversationId = Uuid.random().toString()
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("Question")),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("Answer")),
                usage = TokenUsage(
                    promptTokens = 120,
                    completionTokens = 30,
                    cachedTokens = 40,
                    totalTokens = 150,
                ),
            ),
        )

        helper.createDatabase(testDatabase, 24).apply {
            insert(
                "ConversationEntity",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", conversationId)
                    put("title", "Migration test")
                    put("nodes", "[]")
                    put("create_at", 0)
                    put("update_at", 0)
                },
            )
            insert(
                "message_node",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", Uuid.random().toString())
                    put("conversation_id", conversationId)
                    put("node_index", 0)
                    put("messages", JsonInstant.encodeToString(messages))
                    put("select_index", 0)
                },
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDatabase,
            25,
            true,
            Migration_24_25,
        )

        assertUsage(db, prompt = 120, completion = 30, cached = 40)

        db.execSQL("DELETE FROM ConversationEntity WHERE id = ?", arrayOf(conversationId))

        assertUsage(db, prompt = 120, completion = 30, cached = 40)
        db.close()
    }

    private fun assertUsage(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        prompt: Long,
        completion: Long,
        cached: Long,
    ) {
        db.query(
            "SELECT prompt_tokens, completion_tokens, cached_tokens FROM token_usage_summary WHERE id = 0"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(prompt, cursor.getLong(0))
            assertEquals(completion, cursor.getLong(1))
            assertEquals(cached, cursor.getLong(2))
        }
    }
}
