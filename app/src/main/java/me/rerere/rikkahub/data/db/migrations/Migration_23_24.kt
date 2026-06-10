package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_23_24"

/**
 * The exact DDL this migration runs, as a pure, unit-testable list (issue #197 workspace data layer).
 *
 * Creates the `workspaces` table — an assistant-scoped sandbox (id/name/root, shell enablement +
 * status, timestamps, and per-tool approval overrides) — plus its two indices: a UNIQUE index on
 * `root` (one workspace per on-disk root) and a plain index on `updated_at` (the DAO lists newest
 * first).
 *
 * Every column/table/index name AND type here MUST byte-match what Room's annotation processor
 * generates for v24 (schemas/24.json) — otherwise `runMigrationsAndValidate` rejects the upgraded
 * schema. `last_access_at` is the only nullable column; `tool_approvals` carries the only column
 * default ('{}') so an empty/legacy row decodes to an empty override map.
 *
 * Additive only — a brand-new table and indices; no existing row is read or rewritten, so an upgrade
 * cannot corrupt user data.
 */
internal val Migration_23_24Statements: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `workspaces` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `root` TEXT NOT NULL, `shell_enabled` INTEGER NOT NULL, `shell_status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `last_access_at` INTEGER, `tool_approvals` TEXT NOT NULL DEFAULT '{}', PRIMARY KEY(`id`))",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_root` ON `workspaces` (`root`)",
    "CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` ON `workspaces` (`updated_at`)",
)

val Migration_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 23 to 24 (workspaces table)")
        DatabaseMigrationTracker.onMigrationStart(23, 24)
        db.beginTransaction()
        try {
            Migration_23_24Statements.forEach(db::execSQL)
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 23 to 24 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
