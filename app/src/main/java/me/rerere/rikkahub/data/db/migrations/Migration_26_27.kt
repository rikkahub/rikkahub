package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_26_27"

/**
 * The exact DDL this migration runs, as a pure, unit-testable list (issue #282 workspace working_dir).
 *
 * Adds the `working_dir` column to the existing `workspaces` table: a FILES-area relative seed for
 * the resolved shell cwd, "" == UNSET. The DEFAULT '' means a legacy v26 row that never had the
 * column decodes as unset (and so resolves to the files root at cwd time), never as NULL —
 * `WorkspaceEntity.workingDir` is non-null.
 *
 * The column name AND type here MUST byte-match what Room's annotation processor generates for v27
 * (schemas/27.json `workspaces` create-SQL) — otherwise `runMigrationsAndValidate` rejects the
 * upgraded schema. This is a hand-written migration rather than an AutoMigration because the issue's
 * property catalog (M-I1) asserts on this exact statement list, mirroring [Migration_23_24Statements].
 *
 * Additive only — a single `ALTER TABLE ... ADD COLUMN`; no existing row is read or rewritten, so an
 * upgrade cannot corrupt user data.
 */
internal val Migration_26_27Statements: List<String> = listOf(
    "ALTER TABLE `workspaces` ADD COLUMN `working_dir` TEXT NOT NULL DEFAULT ''",
)

val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 26 to 27 (workspaces.working_dir)")
        DatabaseMigrationTracker.onMigrationStart(26, 27)
        db.beginTransaction()
        try {
            Migration_26_27Statements.forEach(db::execSQL)
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 26 to 27 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
