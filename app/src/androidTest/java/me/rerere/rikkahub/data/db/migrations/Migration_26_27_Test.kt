package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
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
class Migration_26_27_Test {
    private val testDbName = "agent-run-26-27-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate26To27_preservesLegacyExecutionValuesAndRebuildsForeignKeysAndIndexes() {
        helper.createDatabase(testDbName, 26).apply {
            execSQL(
                """
                INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned)
                VALUES ('conversation', 'assistant', 'title', '[]', 1, 1, '[]', 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO agent_runs (id, conversation_id, assistant_id, status, config_snapshot_json, created_at, updated_at)
                VALUES ('run', 'conversation', 'assistant', 'RUNNING', '{}', 10, 11)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO agent_steps (id, run_id, sequence, kind, status, summary_json, created_at, updated_at)
                VALUES ('step', 'run', 0, 'agent', 'SUCCEEDED', '{"legacy":true}', 12, 13)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO tool_executions (
                    id, run_id, step_id, sequence, tool_name, status, summary_json, error_json,
                    created_at, updated_at, started_at, finished_at
                ) VALUES ('tool', 'run', 'step', 0, 'legacy_tool', 'FAILED', '{"old":1}', '{"code":"OLD"}', 14, 15, 16, 17)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO agent_approvals (
                    id, run_id, tool_execution_id, sequence, status, summary_json, created_at, resolved_at
                ) VALUES ('approval-pending', 'run', 'tool', 0, 'PENDING', '{"kind":"pending"}', 18, NULL)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO agent_approvals (
                    id, run_id, tool_execution_id, sequence, status, summary_json, created_at, resolved_at
                ) VALUES ('approval-resolved', 'run', 'tool', 1, 'APPROVED', '{"kind":"resolved"}', 19, 20)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDbName, 27, true, Migration_26_27)
        db.query(
            """
            SELECT tool_name, tool_call_id, input_sha256, status, summary_json, error_json,
                   created_at, updated_at, started_at, finished_at
            FROM tool_executions WHERE id = 'tool'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy_tool", cursor.getString(0))
            assertEquals("legacy-tool", cursor.getString(1))
            assertEquals("", cursor.getString(2))
            assertEquals("FAILED", cursor.getString(3))
            assertEquals("{\"old\":1}", cursor.getString(4))
            assertEquals("{\"code\":\"OLD\"}", cursor.getString(5))
            assertEquals(14, cursor.getLong(6))
            assertEquals(15, cursor.getLong(7))
            assertEquals(16, cursor.getLong(8))
            assertEquals(17, cursor.getLong(9))
        }
        assertTrue(indexNames(db, "tool_executions").containsAll(
            listOf(
                "index_tool_executions_run_id_sequence",
                "index_tool_executions_run_id_step_id_tool_name_tool_call_id_input_sha256",
                "index_tool_executions_run_id_step_id_tool_call_id",
                "index_tool_executions_step_id",
                "index_tool_executions_status",
                "index_tool_executions_run_id_tool_name_input_sha256_status",
            )
        ))
        assertTrue(indexNames(db, "agent_runs").contains("index_agent_runs_conversation_id_created_at_id"))
        assertTrue(indexNames(db, "agent_approvals").containsAll(
            listOf(
                "index_agent_approvals_run_id_sequence",
                "index_agent_approvals_tool_execution_id",
                "index_agent_approvals_status",
                "index_agent_approvals_run_id_status",
            )
        ))
        assertEquals(
            setOf(
                "id", "run_id", "step_id", "sequence", "tool_name", "tool_call_id", "input_sha256",
                "status", "summary_json", "error_json", "created_at", "updated_at", "started_at", "finished_at",
            ),
            columnNames(db, "tool_executions"),
        )
        assertEquals(
            setOf("id", "run_id", "tool_execution_id", "sequence", "status", "summary_json", "created_at", "resolved_at"),
            columnNames(db, "agent_approvals"),
        )
        assertEquals(
            setOf("agent_runs|run_id|id|CASCADE", "agent_steps|step_id|id|CASCADE"),
            foreignKeys(db, "tool_executions"),
        )
        assertEquals(
            setOf("agent_runs|run_id|id|CASCADE", "tool_executions|tool_execution_id|id|CASCADE"),
            foreignKeys(db, "agent_approvals"),
        )
        db.query(
            """
            SELECT id, run_id, tool_execution_id, sequence, status, summary_json, created_at, resolved_at
            FROM agent_approvals ORDER BY sequence
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("approval-pending", cursor.getString(0))
            assertEquals("run", cursor.getString(1))
            assertEquals("tool", cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals("PENDING", cursor.getString(4))
            assertEquals("{\"kind\":\"pending\"}", cursor.getString(5))
            assertEquals(18, cursor.getLong(6))
            assertTrue(cursor.isNull(7))
            assertTrue(cursor.moveToNext())
            assertEquals("approval-resolved", cursor.getString(0))
            assertEquals("APPROVED", cursor.getString(4))
            assertEquals("{\"kind\":\"resolved\"}", cursor.getString(5))
            assertEquals(19, cursor.getLong(6))
            assertEquals(20, cursor.getLong(7))
        }
        assertEquals(0, foreignKeyCheckCount(db))

        db.execSQL("DELETE FROM agent_steps WHERE id = 'step'")
        assertEquals(0, rowCount(db, "tool_executions"))
        assertEquals(0, rowCount(db, "agent_approvals"))
        db.close()
    }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): Set<String> = db.query("PRAGMA index_list(`$table`)").use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> = db.query("PRAGMA table_info(`$table`)").use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
    }

    private fun foreignKeys(db: SupportSQLiteDatabase, table: String): Set<String> = db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
        buildSet {
            while (cursor.moveToNext()) {
                add(
                    listOf(
                        cursor.getString(cursor.getColumnIndexOrThrow("table")),
                        cursor.getString(cursor.getColumnIndexOrThrow("from")),
                        cursor.getString(cursor.getColumnIndexOrThrow("to")),
                        cursor.getString(cursor.getColumnIndexOrThrow("on_delete")),
                    ).joinToString("|"),
                )
            }
        }
    }

    private fun rowCount(db: SupportSQLiteDatabase, table: String): Int = db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

    private fun foreignKeyCheckCount(db: SupportSQLiteDatabase): Int = db.query("PRAGMA foreign_key_check").use { cursor ->
        var count = 0
        while (cursor.moveToNext()) count++
        count
    }
}
