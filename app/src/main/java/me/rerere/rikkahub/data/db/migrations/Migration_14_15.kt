package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private val expectedManagedFilesColumns = setOf(
    "id",
    "folder",
    "relative_path",
    "display_name",
    "mime_type",
    "size_bytes",
    "created_at",
    "updated_at"
)

private fun tableColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
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

private fun recreateManagedFilesTable(db: SupportSQLiteDatabase) {
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

val Migration_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
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

        runCatching {
            val cursor = db.query("PRAGMA table_info(conversationentity)")
            var hasWorkflowState = false
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    if (it.getString(nameIndex) == "workflow_state") {
                        hasWorkflowState = true
                        break
                    }
                }
            }
            if (!hasWorkflowState) {
                db.execSQL(
                    "ALTER TABLE conversationentity ADD COLUMN workflow_state TEXT NOT NULL DEFAULT ''"
                )
            }
        }.onFailure { error ->
            val msg = error.message.orEmpty().lowercase()
            if ("duplicate column name" !in msg || "workflow_state" !in msg) {
                throw error
            }
        }

        val managedFilesColumns = tableColumns(db, "managed_files")
        if (managedFilesColumns != expectedManagedFilesColumns) {
            recreateManagedFilesTable(db)
        }
    }
}
