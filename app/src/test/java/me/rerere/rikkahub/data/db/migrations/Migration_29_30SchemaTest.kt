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
 * Regression test for the v29 -> v30 upgrade: status-leading indexes on `agent_events` and
 * `shell_runs`. The cold-start replay/recovery scans filter by status alone
 * (`SELECT DISTINCT conversation_id WHERE status = 'PENDING'`; `WHERE status IN (...)`), but the
 * pre-existing indexes lead with `conversation_id`, so SQLite cannot use them for a status-only
 * predicate and full-scans as the queues grow.
 *
 * Adding an index is auto-migratable, so the upgrade is a Room AutoMigration(29, 30) with no spec;
 * KSP validates it against the exported schemas at build time. These assertions pin the exported
 * `30.json`: the two new status-leading indexes exist, every v29 index/table/column survives, and
 * nothing is dropped — so the upgrade is purely additive.
 *
 * On the unfixed tree the version is still 29 with no `30.json`, so every assertion fails RED until
 * the index annotations + version bump + AutoMigration land and a build exports the schema.
 */
class Migration_29_30SchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `exported database version is 30`() {
        assertEquals(30, database(30)["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `agent_events gains a status-leading index without losing any v29 index`() {
        val v29 = indexNames(entity(database(29), "agent_events"))
        val v30 = indexNames(entity(database(30), "agent_events"))

        assertTrue("every v29 agent_events index survives", v30.containsAll(v29))
        assertEquals(
            "v30 adds exactly the status-leading agent_events index",
            setOf("index_agent_events_status_conversation_id"),
            v30 - v29,
        )
    }

    @Test
    fun `the new agent_events index leads with status`() {
        val cols = indexColumns(entity(database(30), "agent_events"), "index_agent_events_status_conversation_id")
        assertEquals(listOf("status", "conversation_id"), cols)
    }

    @Test
    fun `shell_runs gains a status-leading index without losing any v29 index`() {
        val v29 = indexNames(entity(database(29), "shell_runs"))
        val v30 = indexNames(entity(database(30), "shell_runs"))

        assertTrue("every v29 shell_runs index survives", v30.containsAll(v29))
        assertEquals(
            "v30 adds exactly the status-leading shell_runs index",
            setOf("index_shell_runs_status"),
            v30 - v29,
        )
    }

    @Test
    fun `the new shell_runs index leads with status`() {
        val cols = indexColumns(entity(database(30), "shell_runs"), "index_shell_runs_status")
        assertEquals(listOf("status"), cols)
    }

    @Test
    fun `v30 is purely additive over v29 - no table dropped and no column changed`() {
        val v29 = database(29)
        val v30 = database(30)
        assertTrue("v29 tables all survive in v30", tableNames(v30).containsAll(tableNames(v29)))
        assertEquals("v30 removes no table", emptySet<String>(), tableNames(v29) - tableNames(v30))

        // The two touched tables keep exactly their v29 columns (an index add must not alter columns).
        for (table in listOf("agent_events", "shell_runs")) {
            assertEquals(
                "$table columns unchanged across 29->30",
                columnNames(entity(v29, table)),
                columnNames(entity(v30, table)),
            )
        }
    }

    // ---- helpers (mirror Migration_26_27Test) ----

    private fun schemaFile(version: Int): File = firstExisting(
        "schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json",
        "app/schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json",
    )

    private fun database(version: Int): JsonObject {
        val file = schemaFile(version)
        assertTrue(
            "Exported Room schema v$version not found (CWD=${File("").absolutePath}): " +
                "${file.path}; the version bump / AutoMigration(29,30) was not built/exported",
            file.isFile,
        )
        return json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    private fun entities(db: JsonObject): JsonArray = db["entities"]!!.jsonArray

    private fun tableNames(db: JsonObject): Set<String> =
        entities(db).map { it.jsonObject["tableName"]!!.jsonPrimitive.content }.toSet()

    private fun entity(db: JsonObject, table: String): JsonObject =
        entities(db).map { it.jsonObject }.single { it["tableName"]!!.jsonPrimitive.content == table }

    private fun indices(entity: JsonObject): JsonArray =
        entity["indices"]?.jsonArray ?: JsonArray(emptyList())

    private fun indexNames(entity: JsonObject): Set<String> =
        indices(entity).map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()

    private fun indexColumns(entity: JsonObject, name: String): List<String> =
        indices(entity).map { it.jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == name }["columnNames"]!!.jsonArray
            .map { it.jsonPrimitive.content }

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
