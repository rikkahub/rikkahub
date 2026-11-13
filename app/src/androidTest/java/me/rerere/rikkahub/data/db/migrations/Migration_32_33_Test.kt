package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_32_33_Test {
    private val testDb = "migration-32-33-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun loadsNodesInOrderWithoutSorting() {
        helper.createDatabase(testDb, 32).apply {
            execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) " +
                    "VALUES ('conv', 'assistant', 'Test', '[]', 1000, 1000)"
            )
            // 故意不按 node_index 的顺序插入
            listOf(2, 0, 3, 1).forEach { index ->
                execSQL(
                    "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                        "VALUES (?, 'conv', ?, '[]', 0)",
                    arrayOf<Any?>("node-$index", index),
                )
            }
            close()
        }

        // 同时校验迁移后的表结构和索引与 33 的 schema 一致
        val db = helper.runMigrationsAndValidate(testDb, 33, true, Migration_32_33)

        val query = "SELECT * FROM message_node WHERE conversation_id = 'conv' ORDER BY node_index ASC LIMIT 2 OFFSET 1"
        val ids = db.query(query).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("id"))) }
        }
        assertEquals(listOf("node-1", "node-2"), ids)

        val plan = db.query("EXPLAIN QUERY PLAN $query").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }
        assertTrue(plan.toString(), plan.any { "index_message_node_conversation_id_node_index" in it })
        assertFalse(plan.toString(), plan.any { "TEMP B-TREE" in it })
        db.close()
    }

    // 索引不是唯一索引：旧数据里同一会话有重复的 node_index 时迁移不能失败，分页读取也不能漏行或重复
    @Test
    fun keepsNodesWithDuplicateIndex() {
        helper.createDatabase(testDb, 32).apply {
            execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) " +
                    "VALUES ('conv', 'assistant', 'Test', '[]', 1000, 1000)"
            )
            listOf("a" to 0, "b" to 1, "c" to 1, "d" to 1, "e" to 2).forEach { (id, index) ->
                execSQL(
                    "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                        "VALUES (?, 'conv', ?, '[]', 0)",
                    arrayOf<Any?>(id, index),
                )
            }
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 33, true, Migration_32_33)

        val ids = (0 until 6 step 2).flatMap { offset ->
            db.query(
                "SELECT id FROM message_node WHERE conversation_id = 'conv' " +
                    "ORDER BY node_index ASC LIMIT 2 OFFSET $offset"
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        }
        assertEquals(listOf("a", "b", "c", "d", "e"), ids)
        db.close()
    }
}
