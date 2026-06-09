package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_22_23"

/**
 * The exact DDL this migration runs, as a pure, unit-testable list (issue #210 §9).
 *
 * Memory v2: enriches `memoryentity` with `created_at`/`updated_at` (staleness + recency ranking) and
 * creates the dedicated `memory_vector` table (1:1 per-memory embedding for relevance recall).
 *
 * Every column/table/index name AND type here MUST byte-match what Room's annotation processor
 * generates for v23 (schemas/23.json) — otherwise `runMigrationsAndValidate` rejects the upgraded
 * schema. The two added columns are `INTEGER NOT NULL DEFAULT 0` to match the Kotlin `Long = 0`
 * fields; legacy rows backfill to 0 (recall via recency until re-embedded). `memory_vector`'s
 * `memory_id` is the PRIMARY KEY and gets Room's implicit single-column index
 * `index_memory_vector_memory_id`.
 *
 * Additive only — no existing row is read or rewritten, so an upgrade cannot corrupt user data.
 */
internal val Migration_22_23Statements: List<String> = listOf(
    "ALTER TABLE `memoryentity` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `memoryentity` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0",
    "CREATE TABLE IF NOT EXISTS `memory_vector` (`memory_id` INTEGER NOT NULL, `content_hash` TEXT NOT NULL, `embedding` TEXT NOT NULL, `embedding_space` TEXT NOT NULL, PRIMARY KEY(`memory_id`))",
    "CREATE INDEX IF NOT EXISTS `index_memory_vector_memory_id` ON `memory_vector` (`memory_id`)",
)

val Migration_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 22 to 23 (memory timestamps + memory_vector)")
        DatabaseMigrationTracker.onMigrationStart(22, 23)
        db.beginTransaction()
        try {
            Migration_22_23Statements.forEach(db::execSQL)
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 22 to 23 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
