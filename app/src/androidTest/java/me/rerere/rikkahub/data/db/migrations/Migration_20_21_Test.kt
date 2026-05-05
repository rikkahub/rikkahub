package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration test for v20 → v21.
 *
 * Room handles this as an AutoMigration, so we do NOT pass the migration object to
 * runMigrationsAndValidate. Instead we rely on the generated migration being registered
 * in AppDatabase's autoMigrations list — Room picks it up automatically.
 *
 * The post-migrate fixup (interval → cron translation) is tested by asserting the
 * SQL UPDATE in Migration_20_21.onPostMigrate ran correctly.
 */
@RunWith(AndroidJUnit4::class)
class Migration_20_21_Test {

    private val TEST_DB = "migration-20-21-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /**
     * Insert a minimal valid v20 scheduled_jobs row.
     * Only columns present in v20 schema are supplied.
     */
    private fun insertV20Row(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        scheduleType: String,
        intervalSeconds: Int? = null,
        atUnixMs: Long? = null,
    ) {
        val values = ContentValues().apply {
            put("id", id)
            put("name", "test-job-$id")
            put("prompt", "do something")
            put("assistantId", "asst-1")
            put("scheduleType", scheduleType)
            put("enabled", 1)
            put("createdAtMs", 1_000_000L)
            if (intervalSeconds != null) put("intervalSeconds", intervalSeconds)
            else putNull("intervalSeconds")
            if (atUnixMs != null) put("atUnixMs", atUnixMs) else putNull("atUnixMs")
        }
        db.insert("scheduled_jobs", SQLiteDatabase.CONFLICT_NONE, values)
    }

    // ── Test 1: interval row (60s) → cron + '@every 60s' ─────────────────────

    @Test
    fun migrate_intervalRow_60s_becomesCron() {
        helper.createDatabase(TEST_DB, 20).use { db ->
            insertV20Row(db, "job-60s", "interval", intervalSeconds = 60)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 21, true)

        val cursor = db.query("SELECT scheduleType, cronExpression FROM scheduled_jobs WHERE id='job-60s'")
        cursor.use {
            assertEquals("should have 1 row", 1, it.count)
            it.moveToFirst()
            assertEquals("scheduleType should be 'cron'", "cron", it.getString(0))
            assertEquals("cronExpression should be '@every 60s'", "@every 60s", it.getString(1))
        }
        db.close()
    }

    // ── Test 2: interval row (3600s) → cron + '@every 3600s' ─────────────────

    @Test
    fun migrate_intervalRow_3600s_becomesCron() {
        helper.createDatabase(TEST_DB, 20).use { db ->
            insertV20Row(db, "job-3600s", "interval", intervalSeconds = 3600)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 21, true)

        val cursor = db.query("SELECT scheduleType, cronExpression FROM scheduled_jobs WHERE id='job-3600s'")
        cursor.use {
            assertEquals("should have 1 row", 1, it.count)
            it.moveToFirst()
            assertEquals("scheduleType should be 'cron'", "cron", it.getString(0))
            assertEquals("cronExpression should be '@every 3600s'", "@every 3600s", it.getString(1))
        }
        db.close()
    }

    // ── Test 3: once row is untouched ─────────────────────────────────────────

    @Test
    fun migrate_onceRow_isUntouched() {
        helper.createDatabase(TEST_DB, 20).use { db ->
            insertV20Row(db, "job-once", "once", atUnixMs = 12345L)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 21, true)

        val cursor = db.query("SELECT scheduleType, atUnixMs, cronExpression FROM scheduled_jobs WHERE id='job-once'")
        cursor.use {
            assertEquals("should have 1 row", 1, it.count)
            it.moveToFirst()
            assertEquals("scheduleType should remain 'once'", "once", it.getString(0))
            assertEquals("atUnixMs should be unchanged", 12345L, it.getLong(1))
            val cronIdx = it.getColumnIndex("cronExpression")
            assert(it.isNull(cronIdx)) { "cronExpression should be null for once rows" }
        }
        db.close()
    }

    // ── Test 4: interval row with null intervalSeconds stays as-is (corrupt data) ──

    @Test
    fun migrate_intervalRow_nullIntervalSeconds_staysInterval() {
        helper.createDatabase(TEST_DB, 20).use { db ->
            // intervalSeconds=null — the WHERE clause (intervalSeconds IS NOT NULL) guards this
            insertV20Row(db, "job-corrupt", "interval", intervalSeconds = null)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 21, true)

        val cursor = db.query("SELECT scheduleType, cronExpression FROM scheduled_jobs WHERE id='job-corrupt'")
        cursor.use {
            assertEquals("should have 1 row", 1, it.count)
            it.moveToFirst()
            assertEquals("corrupt row scheduleType should remain 'interval'", "interval", it.getString(0))
            val cronIdx = it.getColumnIndex("cronExpression")
            assert(it.isNull(cronIdx)) { "cronExpression should be null for corrupt interval row" }
        }
        db.close()
    }

    // ── Test 5: new v21 columns carry correct defaults after migration ─────────

    @Test
    fun migrate_newColumns_haveCorrectDefaults() {
        helper.createDatabase(TEST_DB, 20).use { db ->
            insertV20Row(db, "job-defaults", "once")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 21, true)

        val cursor = db.query("SELECT mode, runsSoFar, catchup FROM scheduled_jobs WHERE id='job-defaults'")
        cursor.use {
            assertEquals("should have 1 row", 1, it.count)
            it.moveToFirst()
            assertEquals("mode default should be 'llm'", "llm", it.getString(0))
            assertEquals("runsSoFar default should be 0", 0, it.getInt(1))
            assertEquals("catchup default should be 'fire_once'", "fire_once", it.getString(2))
        }
        db.close()
    }
}
