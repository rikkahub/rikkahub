package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_21_22"

/**
 * The exact DDL this migration runs, as a pure, unit-testable list.
 *
 * Every CREATE statement's index name AND column list must byte-match what the Room annotation
 * processor generates for the new composite indexes (see schemas/22.json) — otherwise
 * `runMigrationsAndValidate` rejects the upgraded schema. The two DROP statements remove the
 * single-column indexes (`index_message_node_conversation_id`, `index_knowledge_chunk_kb_id`)
 * that the new composite indexes' leading-column prefix already covers, so the post-migration
 * schema matches the generated one.
 *
 * Index names follow Room's `index_<tableName>_<col>_<col>...` convention; the conversation table
 * has no explicit @Entity(tableName) so its table is `ConversationEntity`.
 */
internal val Migration_21_22Statements: List<String> = listOf(
    "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_assistant_id_is_pinned_update_at` ON `ConversationEntity` (`assistant_id`, `is_pinned`, `update_at`)",
    "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_is_pinned_update_at` ON `ConversationEntity` (`is_pinned`, `update_at`)",
    "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` ON `message_node` (`conversation_id`, `node_index`)",
    "CREATE INDEX IF NOT EXISTS `index_knowledge_chunk_kb_id_embedding_model` ON `knowledge_chunk` (`kb_id`, `embedding_model`)",
    "CREATE INDEX IF NOT EXISTS `index_knowledge_chunk_kb_id_doc_id` ON `knowledge_chunk` (`kb_id`, `doc_id`)",
    "DROP INDEX IF EXISTS `index_message_node_conversation_id`",
    "DROP INDEX IF EXISTS `index_knowledge_chunk_kb_id`",
)

/**
 * Additive migration: adds composite indexes matching the hot conversation-list, message-node
 * paging, and RAG query/delete patterns, and drops the two now-redundant single-column indexes.
 *
 * Index-only DDL — no user rows are read or written, so an upgrade cannot corrupt data.
 */
val Migration_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 21 to 22 (query indexes)")
        DatabaseMigrationTracker.onMigrationStart(21, 22)
        db.beginTransaction()
        try {
            Migration_21_22Statements.forEach(db::execSQL)
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 21 to 22 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
