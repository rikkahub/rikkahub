package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_20_21"

/**
 * Additive migration: creates the `knowledge_chunk` vector-store table (RAG knowledge bases).
 *
 * Backwards compatible — only a new table + index are created, existing rows are untouched, so an
 * upgrade cannot corrupt user data.
 */
val Migration_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 20 to 21 (knowledge_chunk table)")
        DatabaseMigrationTracker.onMigrationStart(20, 21)
        db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS knowledge_chunk (
                    id TEXT NOT NULL PRIMARY KEY,
                    kb_id TEXT NOT NULL,
                    doc_id TEXT NOT NULL,
                    source_ref TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    embedding TEXT NOT NULL,
                    embedding_model TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_chunk_kb_id ON knowledge_chunk(kb_id)")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 20 to 21 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
