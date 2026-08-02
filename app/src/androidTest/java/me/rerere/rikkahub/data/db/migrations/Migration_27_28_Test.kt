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
class Migration_27_28_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationPreservesExistingRunsAndCreatesCascadingTraceTable() {
        val name = "agent-trace-27-28"
        helper.createDatabase(name, 27).apply {
            execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned) " +
                    "VALUES ('conversation', 'assistant', 'title', '[]', 1, 1, '[]', 0)",
            )
            execSQL(
                "INSERT INTO agent_runs (id, conversation_id, assistant_id, status, config_snapshot_json, created_at, updated_at) " +
                    "VALUES ('run', 'conversation', 'assistant', 'RUNNING', '{}', 1, 1)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 28, true, Migration_27_28)
        db.execSQL(
            "INSERT INTO agent_trace_events (id, run_id, sequence, type, status, timestamp_millis, error_category, attributes_json, created_at) " +
                "VALUES ('event', 'run', 0, 'RUN_STARTED', 'STARTED', 1, 'NONE', '{}', 1)",
        )
        db.query("SELECT status, config_snapshot_json FROM agent_runs WHERE id = 'run'").use {
            assertTrue(it.moveToFirst())
            assertEquals("RUNNING", it.getString(0))
            assertEquals("{}", it.getString(1))
        }
        db.execSQL("DELETE FROM ConversationEntity WHERE id = 'conversation'")
        db.query("SELECT COUNT(*) FROM agent_trace_events").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        db.close()
    }
}
