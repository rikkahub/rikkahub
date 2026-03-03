package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val DEFAULT_ASSISTANT_ID = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"

val Migration_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(18, 19)
        try {
            normalizeConversationEntity(db)
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}

private fun normalizeConversationEntity(db: SupportSQLiteDatabase) {
    val hasTruncateIndex = hasColumn(db, "ConversationEntity", "truncate_index")
    val hasSource = hasColumn(db, "ConversationEntity", "source")
    if (!hasTruncateIndex && !hasSource) return

    val assistantIdExpr = if (hasColumn(db, "ConversationEntity", "assistant_id")) {
        "assistant_id"
    } else {
        "'$DEFAULT_ASSISTANT_ID'"
    }
    val suggestionsExpr = if (hasColumn(db, "ConversationEntity", "suggestions")) "suggestions" else "'[]'"
    val isPinnedExpr = if (hasColumn(db, "ConversationEntity", "is_pinned")) "is_pinned" else "0"

    db.execSQL("PRAGMA foreign_keys=OFF")
    db.beginTransaction()
    try {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ConversationEntity_new (
                id TEXT NOT NULL PRIMARY KEY,
                assistant_id TEXT NOT NULL DEFAULT '$DEFAULT_ASSISTANT_ID',
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
                $assistantIdExpr,
                title,
                nodes,
                create_at,
                update_at,
                $suggestionsExpr,
                $isPinnedExpr
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
