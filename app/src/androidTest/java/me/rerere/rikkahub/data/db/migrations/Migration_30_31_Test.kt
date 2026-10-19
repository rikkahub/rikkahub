package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class Migration_30_31_Test {
    private val testDb = "migration-30-31-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun backfillsUsageOfAssistantMessagesInAllBranches() {
        helper.createDatabase(testDb, 30).apply {
            execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) " +
                    "VALUES ('conv', 'assistant', 'Test', '[]', 1000, 1000)"
            )
            insertNode(
                "node-1",
                0,
                """[{"role":"user","createdAt":"2026-08-12T09:00:00"}]""",
            )
            // 一个节点两个分支，都要算进去；没有 usage 和 usage 全为 0 的跳过
            insertNode(
                "node-2",
                1,
                """[
                    {"role":"assistant","createdAt":"2026-08-12T09:01:00.123","modelId":"model-a",
                     "modelSnapshot":{"modelId":"gpt-a","displayName":"A"},
                     "usage":{"promptTokens":100,"completionTokens":40,"cachedTokens":20}},
                    {"role":"assistant","createdAt":"2026-08-12T09:02","modelId":"model-b",
                     "usage":{"promptTokens":50,"completionTokens":10,"cachedTokens":0}},
                    {"role":"assistant","createdAt":"2026-08-12T09:03:00"},
                    {"role":"assistant","createdAt":"2026-08-12T09:04:00",
                     "usage":{"promptTokens":0,"completionTokens":0,"cachedTokens":0}}
                ]""",
            )
            insertNode("node-3", 2, "not json")
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 31, true, Migration_30_31)

        val rows = db.query(
            "SELECT created_at, source, model_id, model_name, assistant_id, conversation_id, " +
                "prompt_tokens, completion_tokens, cached_tokens FROM usage_record ORDER BY created_at"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        listOf(
                            cursor.getLong(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getString(3),
                            cursor.getString(4),
                            cursor.getString(5),
                            cursor.getInt(6),
                            cursor.getInt(7),
                            cursor.getInt(8),
                        )
                    )
                }
            }
        }
        db.close()

        assertEquals(
            listOf(
                listOf(epochMillis("2026-08-12T09:01:00"), "CHAT", "model-a", "gpt-a", "assistant", "conv", 100, 40, 20),
                listOf(epochMillis("2026-08-12T09:02:00"), "CHAT", "model-b", null, "assistant", "conv", 50, 10, 0),
            ),
            rows,
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertNode(id: String, index: Int, messages: String) {
        execSQL(
            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (?, 'conv', ?, ?, 0)",
            arrayOf<Any?>(id, index, messages),
        )
    }

    private fun epochMillis(localDateTime: String): Long =
        LocalDateTime.parse(localDateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
