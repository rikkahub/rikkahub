package com.hefengfan.hffchat.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hefengfan.hffchat.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_13_14"

val Migration_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 13 to 14 (UIMessagePart type -> @SerialName)")
        DatabaseMigrationTracker.onMigrationStart(13, 14)
        db.beginTransaction()
        try {
            val cursor = db.query("SELECT id, messages FROM message_node")
            var updatedCount = 0
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val messagesJson = cursor.getString(1)
                val migratedJson = migrateMessagesJson(messagesJson)
                if (migratedJson != messagesJson) {
                    db.execSQL(
                        "UPDATE message_node SET messages = ? WHERE id = ?",
                        arrayOf(migratedJson, id)
                    )
                    updatedCount++
                }
            }
            cursor.close()
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 13 to 14 success ($updatedCount nodes updated)")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
