package me.rerere.rikkahub.data.db.migrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression test for issue #282 (workspace working_dir, M2): the v26->v27 upgrade adds the
 * `working_dir` column to the existing `workspaces` table additively via a hand-written
 * [Migration_26_27] (one ALTER), mirroring [Migration_23_24Statements].
 *
 * The migration body runs raw SQLite DDL via [androidx.sqlite.db.SupportSQLiteDatabase], an Android
 * type that no-ops under the JVM unit-test runtime. So the testable units are (1)
 * [Migration_26_27Statements] — the exact DDL the migration executes — and (2) the EXPORTED
 * `27.json` schema, whose `workspaces` create-SQL must byte-match the `working_dir TEXT NOT NULL
 * DEFAULT ''` column or `runMigrationsAndValidate` rejects the upgrade at runtime.
 *
 * On the unfixed tree neither [Migration_26_27Statements] nor [Migration_26_27] exists and the
 * database version is still 26 with no `27.json`, so every assertion here fails RED until the
 * migration + version bump + DI registration land and the schema is exported by a build.
 */
class Migration_26_27Test {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- M-I1 / M-I4: the exact hand-written ALTER statement list (mirrors Migration_23_24Test) ----

    @Test
    fun `statement list is exactly one additive ALTER that adds working_dir`() {
        // M-I1: the property catalog pins the EXACT DDL string. Room's generated 27.json must
        // byte-match this column DDL (`working_dir` TEXT NOT NULL DEFAULT '') or the runtime upgrade
        // validation fails.
        assertEquals(
            listOf("ALTER TABLE `workspaces` ADD COLUMN `working_dir` TEXT NOT NULL DEFAULT ''"),
            Migration_26_27Statements,
        )
    }

    @Test
    fun `migration is purely additive and never drops or rewrites data`() {
        // M-I4: a data-corrupting migration would DROP/DELETE/UPDATE existing rows; this one must
        // only ALTER ... ADD COLUMN, so legacy workspaces keep working (unset -> default).
        assertTrue(Migration_26_27Statements.none { it.contains("DROP") })
        assertTrue(Migration_26_27Statements.none { it.contains("DELETE") })
        assertTrue(Migration_26_27Statements.none { it.startsWith("UPDATE") })
    }

    // ---- M-I3: the Migration object bridges v26 -> v27 ----

    @Test
    fun `migration object bridges version 26 to 27`() {
        // M-I3: DataSourceModule.addMigrations(..., Migration_26_27) registers exactly this object;
        // its start/end versions must be 26 and 27 so Room picks it for the 26->27 upgrade.
        assertEquals(26, Migration_26_27.startVersion)
        assertEquals(27, Migration_26_27.endVersion)
    }

    // ---- M-I2 / W-R4: the EXPORTED v27 schema (mirrors Migration_25_26SchemaTest) ----

    @Test
    fun `exported database version is 27`() {
        // M-I2: AppDatabase.version == 27, reflected in the exported schema KSP wrote.
        assertEquals(27, database(27)["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `exported workspaces create-SQL carries working_dir as TEXT NOT NULL DEFAULT empty`() {
        // W-R4: the exported 27.json workspaces create-SQL must contain the new column with the
        // exact affinity/nullability/default. A legacy row that never set it decodes as "" (unset).
        val sql = createSql(workspaces(database(27)))
        assertTrue(
            "workspaces create-SQL must declare working_dir TEXT NOT NULL DEFAULT '' (got: $sql)",
            sql.contains("`working_dir` TEXT NOT NULL DEFAULT ''"),
        )
    }

    @Test
    fun `exported workspaces keeps every v26 column - working_dir is purely additive`() {
        // W-R3 / M-I4 (schema-side): the v26 workspaces columns all survive, plus working_dir.
        val v26 = workspaces(database(26))
        val v27 = workspaces(database(27))
        val v26Columns = columnNames(v26)
        val v27Columns = columnNames(v27)

        assertTrue("every v26 workspaces column survives in v27", v27Columns.containsAll(v26Columns))
        assertEquals(
            "v27 adds exactly the working_dir column to workspaces",
            setOf("working_dir"),
            v27Columns - v26Columns,
        )
    }

    @Test
    fun `v27 is purely additive over v26 - no table is dropped`() {
        // M-I4: the upgrade adds a column, never a/removes a table.
        val v26 = tableNames(database(26))
        val v27 = tableNames(database(27))
        assertTrue("v26 tables all survive in v27", v27.containsAll(v26))
        assertEquals("v27 removes no table", emptySet<String>(), v26 - v27)
    }

    // ---- helpers (mirror Migration_25_26SchemaTest) ----

    private fun schemaFile(version: Int): File = firstExisting(
        "schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json",
        "app/schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json",
    )

    private fun database(version: Int): JsonObject {
        val file = schemaFile(version)
        assertTrue(
            "Exported Room schema v$version not found (CWD=${File("").absolutePath}): " +
                "${file.path}; the version bump / Migration_26_27 was not built/exported",
            file.isFile,
        )
        return json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    private fun entities(db: JsonObject): JsonArray = db["entities"]!!.jsonArray

    private fun tableNames(db: JsonObject): Set<String> =
        entities(db).map { it.jsonObject["tableName"]!!.jsonPrimitive.content }.toSet()

    private fun workspaces(db: JsonObject): JsonObject =
        entities(db).map { it.jsonObject }
            .single { it["tableName"]!!.jsonPrimitive.content == "workspaces" }

    private fun createSql(entity: JsonObject): String =
        entity["createSql"]!!.jsonPrimitive.content

    private fun columnNames(entity: JsonObject): Set<String> =
        entity["fields"]!!.jsonArray
            .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
            .toSet()

    private fun firstExisting(vararg candidates: String): File {
        candidates.forEach { c ->
            val f = File(c)
            if (f.isFile) return f
        }
        return File(candidates.first())
    }
}
