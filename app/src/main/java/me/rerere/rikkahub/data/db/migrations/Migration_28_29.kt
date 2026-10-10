package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/** 新增 memory_consolidation 表 */
class Migration_28_29 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        // 已有的对话视为整理过，否则升级后第一次整理会把整段历史都交给模型
        db.execSQL(
            "INSERT OR IGNORE INTO memory_consolidation (conversation_id, consolidated_at) " +
                "SELECT id, update_at FROM ConversationEntity"
        )
    }
}
