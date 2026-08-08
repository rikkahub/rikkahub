package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_24_25"

val Migration_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 24 to 25 (persisting token usage)")
        DatabaseMigrationTracker.onMigrationStart(24, 25)
        try {
            db.beginTransaction()
            try {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS token_usage_summary (
                        id INTEGER NOT NULL,
                        prompt_tokens INTEGER NOT NULL,
                        completion_tokens INTEGER NOT NULL,
                        cached_tokens INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO token_usage_summary (id, prompt_tokens, completion_tokens, cached_tokens)
                    SELECT
                        0,
                        COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0),
                        COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0),
                        COALESCE(SUM(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER)), 0)
                    FROM message_node mn, json_each(mn.messages) j
                    """.trimIndent()
                )
                db.setTransactionSuccessful()
                Log.i(TAG, "migrate: migrate from 24 to 25 success")
            } finally {
                db.endTransaction()
            }
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
