package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/**
 * Memory entity: auto-increment Int PK -> UUID String PK for cloud sync.
 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memoryentity_new` (
                `id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `content` TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        val cursor = db.query("SELECT id, assistant_id, content FROM memoryentity")
        cursor.use {
            while (it.moveToNext()) {
                val uuid = UUID.randomUUID().toString()
                val assistantId = it.getString(1)
                val content = it.getString(2) ?: ""
                db.execSQL(
                    "INSERT INTO memoryentity_new (id, assistant_id, content) VALUES (?, ?, ?)",
                    arrayOf(uuid, assistantId, content),
                )
            }
        }
        db.execSQL("DROP TABLE memoryentity")
        db.execSQL("ALTER TABLE memoryentity_new RENAME TO memoryentity")
    }
}
