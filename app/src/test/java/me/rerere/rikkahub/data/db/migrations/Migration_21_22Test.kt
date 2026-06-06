package me.rerere.rikkahub.data.db.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for issue #111: the hot conversation-list, message-node paging, and RAG
 * query/delete patterns had no matching composite index. Migration_21_22 adds them.
 *
 * The migration body itself runs raw SQLite DDL via a [androidx.sqlite.db.SupportSQLiteDatabase],
 * which is an Android type that no-ops under the JVM unit-test runtime (no Robolectric in this
 * source set). So the testable unit is [Migration_21_22Statements] — the exact CREATE/DROP DDL the
 * migration executes. Its index names and column lists MUST byte-match Room's generated 22.json
 * schema, or `runMigrationsAndValidate` rejects the upgrade at runtime.
 *
 * On the unfixed code this class does not compile (neither the migration nor the statement list
 * exists), so it exercises strictly the new behavior.
 */
class Migration_21_22Test {

    private val createStatements = Migration_21_22Statements.filter { it.startsWith("CREATE") }
    private val dropStatements = Migration_21_22Statements.filter { it.startsWith("DROP") }

    @Test
    fun `creates the conversation-list assistant-scoped composite index`() {
        assertTrue(
            "conversation list filters by assistant_id and orders by is_pinned, update_at",
            createStatements.any {
                it.contains("`index_ConversationEntity_assistant_id_is_pinned_update_at`") &&
                    it.contains("`ConversationEntity` (`assistant_id`, `is_pinned`, `update_at`)")
            }
        )
    }

    @Test
    fun `creates the pinned-ordering composite index`() {
        assertTrue(
            createStatements.any {
                it.contains("`index_ConversationEntity_is_pinned_update_at`") &&
                    it.contains("`ConversationEntity` (`is_pinned`, `update_at`)")
            }
        )
    }

    @Test
    fun `creates the message-node paging composite index`() {
        assertTrue(
            "message-node paging filters by conversation_id and orders by node_index",
            createStatements.any {
                it.contains("`index_message_node_conversation_id_node_index`") &&
                    it.contains("`message_node` (`conversation_id`, `node_index`)")
            }
        )
    }

    @Test
    fun `creates the RAG embedding-model and doc-id composite indexes`() {
        assertTrue(
            createStatements.any {
                it.contains("`index_knowledge_chunk_kb_id_embedding_model`") &&
                    it.contains("`knowledge_chunk` (`kb_id`, `embedding_model`)")
            }
        )
        assertTrue(
            "deleteByDoc filters by kb_id AND doc_id",
            createStatements.any {
                it.contains("`index_knowledge_chunk_kb_id_doc_id`") &&
                    it.contains("`knowledge_chunk` (`kb_id`, `doc_id`)")
            }
        )
    }

    @Test
    fun `drops the now-redundant single-column indexes covered by the composite prefixes`() {
        assertTrue(
            dropStatements.any { it.contains("`index_message_node_conversation_id`") }
        )
        assertTrue(
            dropStatements.any { it.contains("`index_knowledge_chunk_kb_id`") }
        )
    }

    @Test
    fun `every create uses IF NOT EXISTS and every drop uses IF EXISTS to stay idempotent`() {
        assertTrue(createStatements.all { it.contains("CREATE INDEX IF NOT EXISTS") })
        assertTrue(dropStatements.all { it.contains("DROP INDEX IF EXISTS") })
    }

    @Test
    fun `does not drop a composite index it just created`() {
        // The dropped names must be the bare single-column index names, never the new composite
        // ones — otherwise the migration would delete the indexes it is meant to add.
        assertFalse(dropStatements.any { it.contains("update_at") })
        assertFalse(dropStatements.any { it.contains("node_index") })
        assertFalse(dropStatements.any { it.contains("embedding_model") })
        assertFalse(dropStatements.any { it.contains("doc_id") })
    }

    @Test
    fun `statement list has exactly the five creates and two drops`() {
        assertEquals(5, createStatements.size)
        assertEquals(2, dropStatements.size)
        assertEquals(7, Migration_21_22Statements.size)
    }
}
