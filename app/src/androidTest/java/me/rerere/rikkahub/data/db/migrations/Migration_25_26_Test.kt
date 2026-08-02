package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_25_26_Test {
    private val testDbName = "agent-run-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate25To26_createsAgentRunTablesAndPreservesCascadePolicies() {
        helper.createDatabase(testDbName, 25).close()

        val db = helper.runMigrationsAndValidate(testDbName, 26, true, Migration_25_26)
        db.execSQL(
            """
            INSERT INTO ConversationEntity (
                id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned
            )
            VALUES ('conversation', 'assistant', 'title', '[]', 1, 1, '[]', 0)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_runs (
                id, conversation_id, assistant_id, status, config_snapshot_json, created_at, updated_at
            )
            VALUES ('parent', 'conversation', 'assistant', 'QUEUED', '{}', 1, 1)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_runs (
                id, conversation_id, assistant_id, parent_run_id, status, config_snapshot_json, created_at, updated_at
            )
            VALUES ('child', 'conversation', 'assistant', 'parent', 'QUEUED', '{}', 1, 1)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_steps (id, run_id, sequence, kind, status, created_at, updated_at)
            VALUES ('step', 'child', 0, 'model', 'PENDING', 1, 1)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO tool_executions (id, run_id, step_id, sequence, tool_name, status, created_at, updated_at)
            VALUES ('tool', 'child', 'step', 0, 'safe_tool', 'PENDING', 1, 1)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_approvals (id, run_id, tool_execution_id, sequence, status, created_at)
            VALUES ('approval', 'child', 'tool', 0, 'PENDING', 1)
            """.trimIndent()
        )

        db.execSQL("DELETE FROM agent_runs WHERE id = 'parent'")
        db.query("SELECT parent_run_id FROM agent_runs WHERE id = 'child'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }

        db.execSQL("DELETE FROM ConversationEntity WHERE id = 'conversation'")
        assertEquals(0, rowCount(db, "agent_runs"))
        assertEquals(0, rowCount(db, "agent_steps"))
        assertEquals(0, rowCount(db, "tool_executions"))
        assertEquals(0, rowCount(db, "agent_approvals"))
        db.close()
    }

    private fun rowCount(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
