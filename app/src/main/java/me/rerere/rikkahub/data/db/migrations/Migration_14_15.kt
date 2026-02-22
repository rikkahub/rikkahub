package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

val Migration_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(14, 15)
        try {
            db.execSQL(
                """
            CREATE TABLE IF NOT EXISTS favorites (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                ref_key TEXT NOT NULL,
                ref_json TEXT NOT NULL,
                snapshot_json TEXT NOT NULL,
                meta_json TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_favorites_ref_key ON favorites(ref_key)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_type ON favorites(type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_created_at ON favorites(created_at)")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
