package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker
import me.rerere.rikkahub.data.model.MemoryFile

private const val TAG = "Migration_27_28"

val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 27 to 28 (memory records -> memory files)")
        DatabaseMigrationTracker.onMigrationStart(27, 28)
        db.beginTransaction()
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_file` (`assistant_id` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                    "`content` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`assistant_id`, `path`))"
            )

            val memories = linkedMapOf<String, MutableList<String>>()
            db.query("SELECT assistant_id, content FROM MemoryEntity ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) {
                    memories.getOrPut(cursor.getString(0)) { mutableListOf() }.add(cursor.getString(1))
                }
            }

            // 旧记忆每次都会完整注入，迁到同样会完整注入的 profile 里，升级后模型看到的内容不变
            val now = System.currentTimeMillis()
            var migratedCount = 0
            memories.forEach { (assistantId, contents) ->
                val profile = legacyMemoriesToProfile(contents) ?: return@forEach
                db.execSQL(
                    "INSERT INTO memory_file (assistant_id, path, content, updated_at) VALUES (?, ?, ?, ?)",
                    arrayOf<Any?>(assistantId, MemoryFile.PROFILE_PATH, profile, now)
                )
                migratedCount++
            }

            db.execSQL("DROP TABLE IF EXISTS MemoryEntity")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 27 to 28 success ($migratedCount memory stores migrated)")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}

/** 把旧的逐条记忆拼成一份 profile 文件，没有有效内容时返回 null */
internal fun legacyMemoriesToProfile(contents: List<String>): String? {
    val lines = contents
        .map { it.lines().joinToString(" ") { line -> line.trim() }.trim() }
        .filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    return buildString {
        appendLine("---")
        appendLine("name: profile")
        appendLine("description: Who the user is")
        appendLine("---")
        appendLine()
        lines.forEach { appendLine("- [stated] $it") }
    }
}
