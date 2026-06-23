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
 * Regression test for the v32 -> v33 upgrade (#364 slice 2): `task_schedules.delivery_mode`, a NOT NULL
 * TEXT column defaulting to `DETACHED_TASK` so every pre-existing schedule keeps spawning a detached run
 * and an in-session `/loop` row can mark itself `CONVERSATION_EVENT`.
 *
 * A defaulted additive column is auto-migratable, so the upgrade is a Room AutoMigration(32, 33) with no
 * spec; KSP validates it against the exported schemas at build time. These assertions pin the exported
 * `33.json`: the new column exists with the right default, every v32 column survives, and no table is
 * dropped — so the upgrade is purely additive.
 *
 * On the unfixed tree the version is still 32 with no `33.json`, so every assertion fails RED until the
 * column + version bump + AutoMigration land and a build exports the schema.
 */
class Migration_32_33SchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `exported database version is 33`() {
        assertEquals(33, database(33)["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `task_schedules gains delivery_mode without losing any v32 column`() {
        val v32 = columnNames(entity(database(32), "task_schedules"))
        val v33 = columnNames(entity(database(33), "task_schedules"))

        assertTrue("every v32 task_schedules column survives", v33.containsAll(v32))
        assertEquals(
            "v33 adds exactly the delivery_mode column",
            setOf("delivery_mode"),
            v33 - v32,
        )
    }

    @Test
    fun `delivery_mode is NOT NULL with a DETACHED_TASK default`() {
        val field = field(entity(database(33), "task_schedules"), "delivery_mode")
        assertEquals("TEXT", field["affinity"]!!.jsonPrimitive.content)
        assertTrue("delivery_mode must be NOT NULL", field["notNull"]!!.jsonPrimitive.content.toBoolean())
        // Room serializes column defaults wrapped in single quotes for TEXT.
        assertEquals("'DETACHED_TASK'", field["defaultValue"]!!.jsonPrimitive.content)
    }

    @Test
    fun `v33 is purely additive over v32 - no table dropped`() {
        val v32 = database(32)
        val v33 = database(33)
        assertTrue("v32 tables all survive in v33", tableNames(v33).containsAll(tableNames(v32)))
        assertEquals("v33 removes no table", emptySet<String>(), tableNames(v32) - tableNames(v33))
    }

    // ---- helpers (mirror Migration_29_30SchemaTest) ----

    private fun schemaFile(version: Int): File = firstExisting(
        "schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json",
        "app/schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json",
    )

    private fun database(version: Int): JsonObject {
        val file = schemaFile(version)
        assertTrue(
            "Exported Room schema v$version not found (CWD=${File("").absolutePath}): " +
                "${file.path}; the version bump / AutoMigration(32,33) was not built/exported",
            file.isFile,
        )
        return json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    private fun entities(db: JsonObject): JsonArray = db["entities"]!!.jsonArray

    private fun tableNames(db: JsonObject): Set<String> =
        entities(db).map { it.jsonObject["tableName"]!!.jsonPrimitive.content }.toSet()

    private fun entity(db: JsonObject, table: String): JsonObject =
        entities(db).map { it.jsonObject }.single { it["tableName"]!!.jsonPrimitive.content == table }

    private fun field(entity: JsonObject, column: String): JsonObject =
        entity["fields"]!!.jsonArray.map { it.jsonObject }
            .single { it["columnName"]!!.jsonPrimitive.content == column }

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
