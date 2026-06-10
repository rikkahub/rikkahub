package me.rerere.rikkahub.data.db.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for issue #197 (workspace data layer): the v23->v24 migration must additively
 * create the `workspaces` table (id/name/root/shell_enabled/shell_status/created_at/updated_at/
 * last_access_at/tool_approvals) plus its unique-on-root and updated_at indices.
 *
 * The migration body runs raw SQLite DDL via [androidx.sqlite.db.SupportSQLiteDatabase], an Android
 * type that no-ops under the JVM unit-test runtime. So the testable unit is [Migration_23_24Statements]
 * — the exact DDL the migration executes. Its column/table/index names and types MUST byte-match
 * Room's generated 24.json schema, or `runMigrationsAndValidate` rejects the upgrade at runtime.
 *
 * On the unfixed code this class does not compile (neither the migration nor the statement list
 * exists), so it exercises strictly the new behavior.
 */
class Migration_23_24Test {

    private val createTableStatements = Migration_23_24Statements.filter { it.startsWith("CREATE TABLE") }
    private val createUniqueIndexStatements = Migration_23_24Statements.filter { it.startsWith("CREATE UNIQUE INDEX") }
    private val createIndexStatements = Migration_23_24Statements.filter { it.startsWith("CREATE INDEX") }

    @Test
    fun `creates the workspaces table with all columns at the exact affinity and nullability`() {
        val create = createTableStatements.singleOrNull { it.contains("`workspaces`") }
        assertTrue("workspaces CREATE TABLE present", create != null)
        create!!
        assertTrue("id column", create.contains("`id` TEXT NOT NULL"))
        assertTrue("name column", create.contains("`name` TEXT NOT NULL"))
        assertTrue("root column", create.contains("`root` TEXT NOT NULL"))
        assertTrue("shell_enabled column", create.contains("`shell_enabled` INTEGER NOT NULL"))
        assertTrue("shell_status column", create.contains("`shell_status` TEXT NOT NULL"))
        assertTrue("created_at column", create.contains("`created_at` INTEGER NOT NULL"))
        assertTrue("updated_at column", create.contains("`updated_at` INTEGER NOT NULL"))
        // last_access_at is nullable: no NOT NULL marker.
        assertTrue("last_access_at column (nullable)", create.contains("`last_access_at` INTEGER,"))
        // tool_approvals carries the only column default ('{}') so legacy/empty rows decode to {}.
        assertTrue(
            "tool_approvals column with DEFAULT '{}'",
            create.contains("`tool_approvals` TEXT NOT NULL DEFAULT '{}'")
        )
        assertTrue("id is the primary key", create.contains("PRIMARY KEY(`id`)"))
    }

    @Test
    fun `creates the unique index on root matching Room's generated name`() {
        assertTrue(
            createUniqueIndexStatements.any {
                it.contains("`index_workspaces_root`") &&
                    it.contains("`workspaces` (`root`)")
            }
        )
    }

    @Test
    fun `creates the non-unique index on updated_at matching Room's generated name`() {
        // A non-unique index starts with "CREATE INDEX" (filtered to exclude the UNIQUE one).
        assertTrue(
            createIndexStatements.any {
                it.contains("`index_workspaces_updated_at`") &&
                    it.contains("`workspaces` (`updated_at`)")
            }
        )
    }

    @Test
    fun `every create uses IF NOT EXISTS so the migration is idempotent`() {
        assertTrue(createTableStatements.all { it.contains("CREATE TABLE IF NOT EXISTS") })
        assertTrue(createUniqueIndexStatements.all { it.contains("CREATE UNIQUE INDEX IF NOT EXISTS") })
        assertTrue(createIndexStatements.all { it.contains("CREATE INDEX IF NOT EXISTS") })
    }

    @Test
    fun `statement list is exactly one table and two indexes`() {
        assertEquals(1, createTableStatements.size)
        assertEquals(1, createUniqueIndexStatements.size)
        assertEquals(1, createIndexStatements.size)
        assertEquals(3, Migration_23_24Statements.size)
    }

    @Test
    fun `migration is purely additive and never drops or rewrites data`() {
        // A data-corrupting migration would DROP/DELETE/UPDATE existing rows; this one must not.
        assertTrue(Migration_23_24Statements.none { it.contains("DROP") })
        assertTrue(Migration_23_24Statements.none { it.contains("DELETE") })
        assertTrue(Migration_23_24Statements.none { it.startsWith("UPDATE") })
    }
}
