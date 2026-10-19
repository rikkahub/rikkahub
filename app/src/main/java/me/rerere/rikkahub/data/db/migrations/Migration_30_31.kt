package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_30_31"

/**
 * 新增 usage_record 用量流水表，并用现有消息上的 usage 回填，升级后统计不会断档。
 *
 * 旧消息只有整条消息的 usage，回填时每条助手消息记一行；所有分支都算，和实际消耗一致。
 * 消息的 createdAt 是本地时间的 ISO 字符串，按设备时区换算成时间戳，解析不了时用会话的创建时间。
 */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 30 to 31 (usage records)")
        DatabaseMigrationTracker.onMigrationStart(30, 31)
        db.beginTransaction()
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `usage_record` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, `source` TEXT NOT NULL, `provider_id` TEXT, `model_id` TEXT, " +
                    "`model_name` TEXT, `assistant_id` TEXT, `conversation_id` TEXT, " +
                    "`prompt_tokens` INTEGER NOT NULL, `completion_tokens` INTEGER NOT NULL, " +
                    "`cached_tokens` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_record_created_at` ON `usage_record` (`created_at`)")

            db.execSQL(
                """
                INSERT INTO usage_record (
                    created_at, source, provider_id, model_id, model_name, assistant_id, conversation_id,
                    prompt_tokens, completion_tokens, cached_tokens
                )
                SELECT
                    COALESCE(
                        CAST(strftime('%s', substr(json_extract(j.value, '$.createdAt'), 1, 19), 'utc') AS INTEGER) * 1000,
                        c.create_at
                    ),
                    'CHAT',
                    NULL,
                    json_extract(j.value, '$.modelId'),
                    json_extract(j.value, '$.modelSnapshot.modelId'),
                    c.assistant_id,
                    mn.conversation_id,
                    COALESCE(json_extract(j.value, '$.usage.promptTokens'), 0),
                    COALESCE(json_extract(j.value, '$.usage.completionTokens'), 0),
                    COALESCE(json_extract(j.value, '$.usage.cachedTokens'), 0)
                FROM message_node mn
                JOIN ConversationEntity c ON c.id = mn.conversation_id,
                    json_each(CASE WHEN json_valid(mn.messages) THEN mn.messages ELSE '[]' END) j
                WHERE json_extract(j.value, '$.role') = 'assistant'
                    AND json_type(j.value, '$.usage') = 'object'
                    AND (
                        COALESCE(json_extract(j.value, '$.usage.promptTokens'), 0) > 0
                        OR COALESCE(json_extract(j.value, '$.usage.completionTokens'), 0) > 0
                    )
                """.trimIndent()
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 30 to 31 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
