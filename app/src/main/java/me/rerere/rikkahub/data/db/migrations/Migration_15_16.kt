package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private val expectedManagedFilesColumnsV16 = setOf(
    "id",
    "folder",
    "relative_path",
    "display_name",
    "mime_type",
    "size_bytes",
    "created_at",
    "updated_at"
)

private val expectedFavoritesColumnsV16 = setOf(
    "id",
    "type",
    "ref_key",
    "ref_json",
    "snapshot_json",
    "meta_json",
    "created_at",
    "updated_at"
)

private fun tableColumnsV16(db: SupportSQLiteDatabase, tableName: String): Set<String> {
    val cursor = db.query("PRAGMA table_info($tableName)")
    val columns = mutableSetOf<String>()
    cursor.use {
        val nameIndex = it.getColumnIndex("name")
        while (it.moveToNext()) {
            columns.add(it.getString(nameIndex))
        }
    }
    return columns
}

private fun recreateManagedFilesTableV16(db: SupportSQLiteDatabase) {
    db.execSQL("DROP TABLE IF EXISTS managed_files")
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS managed_files (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            folder TEXT NOT NULL,
            relative_path TEXT NOT NULL,
            display_name TEXT NOT NULL,
            mime_type TEXT NOT NULL,
            size_bytes INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_managed_files_relative_path ON managed_files(relative_path)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_managed_files_folder ON managed_files(folder)")
}

private fun recreateFavoritesTableV16(db: SupportSQLiteDatabase) {
    db.execSQL("DROP TABLE IF EXISTS favorites")
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
}

val Migration_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val conversationColumns = tableColumnsV16(db, "conversationentity")
        if ("workflow_state" !in conversationColumns) {
            db.execSQL("ALTER TABLE conversationentity ADD COLUMN workflow_state TEXT NOT NULL DEFAULT ''")
        }

        val managedFilesColumns = tableColumnsV16(db, "managed_files")
        if (managedFilesColumns != expectedManagedFilesColumnsV16) {
            recreateManagedFilesTableV16(db)
        }

        val favoritesColumns = tableColumnsV16(db, "favorites")
        if (favoritesColumns != expectedFavoritesColumnsV16) {
            recreateFavoritesTableV16(db)
        } else {
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_favorites_ref_key ON favorites(ref_key)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_type ON favorites(type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_created_at ON favorites(created_at)")
        }
    }
}
