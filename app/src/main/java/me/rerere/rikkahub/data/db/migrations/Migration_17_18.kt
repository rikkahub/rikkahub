package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

val Migration_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(17, 18)
        try {
            ensureScheduledTaskRunTable(db)
            if (hasColumn(db, "ConversationEntity", "source")) {
                dropLegacyConversationSourceColumn(db)
            }
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}

private fun ensureScheduledTaskRunTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS scheduled_task_run (
            id TEXT NOT NULL PRIMARY KEY,
            task_id TEXT NOT NULL,
            task_title_snapshot TEXT NOT NULL,
            assistant_id_snapshot TEXT NOT NULL,
            status TEXT NOT NULL,
            started_at INTEGER NOT NULL,
            finished_at INTEGER NOT NULL,
            duration_ms INTEGER NOT NULL,
            prompt_snapshot TEXT NOT NULL,
            result_text TEXT NOT NULL,
            error_text TEXT NOT NULL,
            model_id_snapshot TEXT,
            provider_name_snapshot TEXT NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_task_run_task_id ON scheduled_task_run(task_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_task_run_started_at ON scheduled_task_run(started_at)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_task_run_status ON scheduled_task_run(status)")
}

private fun hasColumn(db: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean {
    val cursor = db.query("PRAGMA table_info('$tableName')")
    return try {
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex == -1) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == columnName) {
                return true
            }
        }
        false
    } finally {
        cursor.close()
    }
}

private fun dropLegacyConversationSourceColumn(db: SupportSQLiteDatabase) {
    db.execSQL("PRAGMA foreign_keys=OFF")
    db.beginTransaction()
    try {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ConversationEntity_new (
                id TEXT NOT NULL PRIMARY KEY,
                assistant_id TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e',
                title TEXT NOT NULL,
                nodes TEXT NOT NULL,
                create_at INTEGER NOT NULL,
                update_at INTEGER NOT NULL,
                suggestions TEXT NOT NULL DEFAULT '[]',
                is_pinned INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO ConversationEntity_new (
                id,
                assistant_id,
                title,
                nodes,
                create_at,
                update_at,
                suggestions,
                is_pinned
            )
            SELECT
                id,
                assistant_id,
                title,
                nodes,
                create_at,
                update_at,
                suggestions,
                is_pinned
            FROM ConversationEntity
            """.trimIndent()
        )
        db.execSQL("DROP TABLE ConversationEntity")
        db.execSQL("ALTER TABLE ConversationEntity_new RENAME TO ConversationEntity")
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
        db.execSQL("PRAGMA foreign_keys=ON")
    }
}
