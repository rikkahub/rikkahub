package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_23_24"

/**
 * v23 → v24 (Phase 24 — unified `AgentRun` ledger).
 *
 * Additive only: creates the `agent_runs` table plus its four indexes. No data migration —
 * existing rows in `scheduled_job_runs` / `workflow_runs` are NOT backfilled (a backfill
 * would be lossy and non-monotonic; the ledger starts populating from the first autonomous
 * run after upgrade).
 *
 * Hand-written rather than an AutoMigration so the table shape is pinned in source and the
 * `Migration_23_24_Test` can assert it directly. The `CREATE TABLE` / `CREATE INDEX`
 * statements must match the schema Room generates from the [me.rerere.rikkahub.data.agentrun.AgentRun]
 * `@Entity` exactly, or `runMigrationsAndValidate` fails the test.
 */
val Migration_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 23 to 24 (creating agent_runs ledger table)")
        db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_runs` (
                    `id` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `domain_id` TEXT NOT NULL,
                    `parent_run_id` TEXT,
                    `status` TEXT NOT NULL,
                    `created_at_ms` INTEGER NOT NULL,
                    `updated_at_ms` INTEGER NOT NULL,
                    `started_at_ms` INTEGER,
                    `finished_at_ms` INTEGER,
                    `last_error` TEXT,
                    `metadata_json` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_runs_status` ON `agent_runs` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_runs_kind_dom` ON `agent_runs` (`kind`, `domain_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_runs_parent` ON `agent_runs` (`parent_run_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_runs_updated_at` ON `agent_runs` (`updated_at_ms`)")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 23 to 24 success")
        } finally {
            db.endTransaction()
        }
    }
}
