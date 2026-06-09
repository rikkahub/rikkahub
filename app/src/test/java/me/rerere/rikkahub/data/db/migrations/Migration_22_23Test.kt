package me.rerere.rikkahub.data.db.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for issue #210 (Memory v2): the v22->v23 migration must additively add the
 * `created_at`/`updated_at` columns to `memoryentity` (staleness + recency ranking) and create the
 * dedicated `memory_vector` table (1:1 per-memory embedding for relevance recall).
 *
 * The migration body runs raw SQLite DDL via [androidx.sqlite.db.SupportSQLiteDatabase], an Android
 * type that no-ops under the JVM unit-test runtime. So the testable unit is [Migration_22_23Statements]
 * — the exact DDL the migration executes. Its column/table/index names and types MUST byte-match
 * Room's generated 23.json schema, or `runMigrationsAndValidate` rejects the upgrade at runtime.
 *
 * On the unfixed code this class does not compile (neither the migration nor the statement list
 * exists), so it exercises strictly the new behavior.
 */
class Migration_22_23Test {

    private val alterStatements = Migration_22_23Statements.filter { it.startsWith("ALTER") }
    private val createTableStatements = Migration_22_23Statements.filter { it.startsWith("CREATE TABLE") }
    private val createIndexStatements = Migration_22_23Statements.filter { it.startsWith("CREATE INDEX") }

    @Test
    fun `adds created_at and updated_at to memoryentity as INTEGER NOT NULL DEFAULT 0`() {
        // DEFAULT 0 is required so existing rows backfill (and validates against the Long=0 field).
        assertTrue(
            "created_at column added",
            alterStatements.any {
                it.contains("ALTER TABLE `memoryentity` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0")
            }
        )
        assertTrue(
            "updated_at column added",
            alterStatements.any {
                it.contains("ALTER TABLE `memoryentity` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0")
            }
        )
    }

    @Test
    fun `creates the memory_vector table with the four columns and memory_id primary key`() {
        val create = createTableStatements.singleOrNull { it.contains("`memory_vector`") }
        assertTrue("memory_vector CREATE TABLE present", create != null)
        create!!
        assertTrue("memory_id column", create.contains("`memory_id` INTEGER NOT NULL"))
        assertTrue("content_hash column", create.contains("`content_hash` TEXT NOT NULL"))
        assertTrue("embedding column", create.contains("`embedding` TEXT NOT NULL"))
        assertTrue("embedding_space column", create.contains("`embedding_space` TEXT NOT NULL"))
        assertTrue("memory_id is the primary key", create.contains("PRIMARY KEY(`memory_id`)"))
    }

    @Test
    fun `creates the memory_vector memory_id index matching Room's generated name`() {
        assertTrue(
            createIndexStatements.any {
                it.contains("`index_memory_vector_memory_id`") &&
                    it.contains("`memory_vector` (`memory_id`)")
            }
        )
    }

    @Test
    fun `every create uses IF NOT EXISTS so the migration is idempotent`() {
        assertTrue(createTableStatements.all { it.contains("CREATE TABLE IF NOT EXISTS") })
        assertTrue(createIndexStatements.all { it.contains("CREATE INDEX IF NOT EXISTS") })
    }

    @Test
    fun `statement list is exactly the two alters, one table, and one index`() {
        assertEquals(2, alterStatements.size)
        assertEquals(1, createTableStatements.size)
        assertEquals(1, createIndexStatements.size)
        assertEquals(4, Migration_22_23Statements.size)
    }

    @Test
    fun `migration is purely additive and never drops or rewrites data`() {
        // A data-corrupting migration would DROP/DELETE/UPDATE existing rows; this one must not.
        assertTrue(Migration_22_23Statements.none { it.contains("DROP") })
        assertTrue(Migration_22_23Statements.none { it.contains("DELETE") })
        assertTrue(Migration_22_23Statements.none { it.startsWith("UPDATE") })
    }
}
