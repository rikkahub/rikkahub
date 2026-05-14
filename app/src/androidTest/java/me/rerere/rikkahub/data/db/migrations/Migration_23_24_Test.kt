package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 24 — instrumented migration test for v23 → v24 (`agent_runs` ledger table).
 *
 * Creates a v23 database with sample rows in pre-existing tables, runs [Migration_23_24],
 * and asserts:
 *  - the `agent_runs` table is created with the correct column set
 *  - all four indexes exist
 *  - the migration is additive — sample rows inserted at v23 survive
 *  - rows can be inserted into the new table after migration
 */
@RunWith(AndroidJUnit4::class)
class Migration_23_24_Test {
    private val TEST_DB = "migration-23-24-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate23To24_createsAgentRunsTableWithCorrectSchema() {
        helper.createDatabase(TEST_DB, 23).apply { close() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 24, true, Migration_23_24)

        val cursor = db.query("SELECT * FROM agent_runs LIMIT 0")
        val columns = cursor.columnNames.toList()
        cursor.close()

        assertTrue("agent_runs table should exist", columns.isNotEmpty())
        for (col in listOf(
            "id", "kind", "domain_id", "parent_run_id", "status",
            "created_at_ms", "updated_at_ms", "started_at_ms", "finished_at_ms",
            "last_error", "metadata_json",
        )) {
            assertTrue("agent_runs should have '$col' column", columns.contains(col))
        }
        db.close()
    }

    @Test
    fun migrate23To24_createsAllFourIndexes() {
        helper.createDatabase(TEST_DB, 23).apply { close() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 24, true, Migration_23_24)

        for (idx in listOf(
            "idx_runs_status", "idx_runs_kind_dom", "idx_runs_parent", "idx_runs_updated_at",
        )) {
            val c = db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='agent_runs' AND name=?",
                arrayOf(idx),
            )
            assertTrue("index $idx should exist on agent_runs", c.count > 0)
            c.close()
        }
        db.close()
    }

    @Test
    fun migrate23To24_isAdditive_preExistingRowsSurvive() {
        // Insert a sample scheduled_job_runs row at v23 — a Phase 9.5 domain detail table
        // that the shadow ledger explicitly does NOT migrate off.
        val jobRunId = "job-run-1"
        helper.createDatabase(TEST_DB, 23).apply {
            val values = ContentValues().apply {
                put("id", jobRunId)
                put("jobId", "job-1")
                put("mode", "llm")
                put("scheduledAtMs", 1_000L)
                put("startedAtMs", 1_100L)
                put("finishedAtMs", 1_200L)
                put("outcome", "success")
                putNull("conversationId")
                putNull("errorMessage")
            }
            insert("scheduled_job_runs", SQLiteDatabase.CONFLICT_NONE, values)
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 24, true, Migration_23_24)

        val c = db.query("SELECT outcome FROM scheduled_job_runs WHERE id = ?", arrayOf(jobRunId))
        assertTrue("pre-existing scheduled_job_runs row should survive the migration", c.moveToFirst())
        assertEquals("success", c.getString(0))
        c.close()

        // And the new table starts empty.
        val emptyCursor = db.query("SELECT COUNT(*) FROM agent_runs")
        emptyCursor.moveToFirst()
        assertEquals("agent_runs should start empty (no backfill)", 0, emptyCursor.getInt(0))
        emptyCursor.close()
        db.close()
    }

    @Test
    fun migrate23To24_newTableAcceptsInsertsAfterMigration() {
        helper.createDatabase(TEST_DB, 23).apply { close() }
        val db = helper.runMigrationsAndValidate(TEST_DB, 24, true, Migration_23_24)

        val values = ContentValues().apply {
            put("id", "run-1")
            put("kind", "cron")
            put("domain_id", "job-1:5000")
            putNull("parent_run_id")
            put("status", "running")
            put("created_at_ms", 5_000L)
            put("updated_at_ms", 5_000L)
            put("started_at_ms", 5_000L)
            putNull("finished_at_ms")
            putNull("last_error")
            put("metadata_json", "{\"job_name\":\"test\"}")
        }
        db.insert("agent_runs", SQLiteDatabase.CONFLICT_NONE, values)

        val c = db.query("SELECT kind, domain_id, status, finished_at_ms FROM agent_runs WHERE id = ?", arrayOf("run-1"))
        assertTrue(c.moveToFirst())
        assertEquals("cron", c.getString(0))
        assertEquals("job-1:5000", c.getString(1))
        assertEquals("running", c.getString(2))
        assertTrue("finished_at_ms should still be null for a running row", c.isNull(3))
        c.close()
        db.close()
    }
}
