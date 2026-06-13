package me.rerere.rikkahub.data.db.migrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Schema-validation guard for the task-scheduling M2 step (T3): the v25->v26 upgrade adds the
 * `task_schedules` Room table additively via `AutoMigration(from = 25, to = 26)` with no spec.
 *
 * Room derives that auto-migration from the EXPORTED schema JSONs (`app/schemas/.../{25,26}.json`),
 * and KSP fails the build if v26 is missing or non-additive over v25. So the JVM-testable unit is
 * the exported `26.json` itself: its column/index/affinity/nullability shape MUST match the
 * `task_schedules` data model, or the runtime upgrade Room generated against it is wrong. This is
 * the unit/JVM kind CI runs (no emulator, no SQLite), mirroring the existing migration schema
 * guards (Migration_23_24Test).
 *
 * On the unfixed tree `26.json` does not exist (the entity is unregistered and the version is still
 * 25), so every assertion here fails RED until the entity + version bump land and the schema is
 * exported by a build.
 */
class Migration_25_26SchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun schemaFile(version: Int): File = firstExisting(
        "schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json",
        "app/schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json",
    )

    private fun database(version: Int): JsonObject {
        val file = schemaFile(version)
        assertTrue(
            "Exported Room schema v$version not found (CWD=${File("").absolutePath}): " +
                "${file.path}; the AutoMigration(25,26) was not built/exported",
            file.isFile,
        )
        return json.parseToString(file).jsonObject["database"]!!.jsonObject
    }

    private fun Json.parseToString(file: File): JsonObject =
        json.parseToJsonElement(file.readText()).jsonObject

    private fun entities(db: JsonObject): JsonArray = db["entities"]!!.jsonArray

    private fun tableNames(db: JsonObject): Set<String> =
        entities(db).map { it.jsonObject["tableName"]!!.jsonPrimitive.content }.toSet()

    private fun taskSchedules(db: JsonObject): JsonObject =
        entities(db).map { it.jsonObject }
            .single { it["tableName"]!!.jsonPrimitive.content == "task_schedules" }

    private fun createSql(entity: JsonObject): String =
        entity["createSql"]!!.jsonPrimitive.content

    @Test
    fun `database version is 26`() {
        assertEquals(26, database(26)["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `task_schedules table carries every data-model column at the right affinity and nullability`() {
        val sql = createSql(taskSchedules(database(26)))

        // Non-null identity + scope columns.
        assertTrue("id PK", sql.contains("`id` TEXT NOT NULL"))
        assertTrue("conversation_id", sql.contains("`conversation_id` TEXT NOT NULL"))
        assertTrue("target_assistant_id", sql.contains("`target_assistant_id` TEXT NOT NULL"))
        assertTrue("prompt", sql.contains("`prompt` TEXT NOT NULL"))
        assertTrue("owner", sql.contains("`owner` TEXT NOT NULL"))
        assertTrue("kind", sql.contains("`kind` TEXT NOT NULL"))
        assertTrue("time_zone_id", sql.contains("`time_zone_id` TEXT NOT NULL"))
        assertTrue("next_fire_at", sql.contains("`next_fire_at` INTEGER NOT NULL"))
        assertTrue("first_fire_at", sql.contains("`first_fire_at` INTEGER NOT NULL"))
        assertTrue("enabled", sql.contains("`enabled` INTEGER NOT NULL"))
        assertTrue("created_at", sql.contains("`created_at` INTEGER NOT NULL"))
        assertTrue("updated_at", sql.contains("`updated_at` INTEGER NOT NULL"))
        assertTrue("misfire_policy", sql.contains("`misfire_policy` TEXT NOT NULL"))

        // Nullable columns: present, but WITHOUT a NOT NULL marker (the column is followed by a
        // comma or the closing paren, never `NOT NULL`).
        assertNullableColumn(sql, "recurrence_spec", "TEXT")
        assertNullableColumn(sql, "last_task_run_id", "TEXT")
        assertNullableColumn(sql, "running_task_run_id", "TEXT")
        assertNullableColumn(sql, "last_fired_at", "INTEGER")

        assertTrue("id is the primary key", sql.contains("PRIMARY KEY(`id`)"))
    }

    @Test
    fun `task_schedules indexes conversation_id and next_fire_at`() {
        val entity = taskSchedules(database(26))
        val indices = entity["indices"]?.jsonArray.orEmpty()
        val indexSql = indices.map { it.jsonObject["createSql"]!!.jsonPrimitive.content }

        assertTrue(
            "conversation_id index",
            indexSql.any { it.contains("ON `\${TABLE_NAME}` (`conversation_id`)") },
        )
        assertTrue(
            "next_fire_at index",
            indexSql.any { it.contains("ON `\${TABLE_NAME}` (`next_fire_at`)") },
        )
    }

    @Test
    fun `task_schedules declares no foreign keys (conversation delete is cleaned explicitly, not cascaded)`() {
        val entity = taskSchedules(database(26))
        val foreignKeys = entity["foreignKeys"]?.jsonArray.orEmpty()
        assertTrue("no foreignKeys block", foreignKeys.isEmpty())
        assertFalse(
            "createSql must not declare a FOREIGN KEY",
            createSql(entity).contains("FOREIGN KEY"),
        )
    }

    @Test
    fun `v26 is purely additive over v25 - it adds exactly task_schedules and removes nothing`() {
        val v25 = tableNames(database(25))
        val v26 = tableNames(database(26))

        assertTrue("v25 tables all survive in v26", v26.containsAll(v25))
        assertEquals(
            "v26 adds exactly the task_schedules table",
            setOf("task_schedules"),
            v26 - v25,
        )
    }

    private fun assertNullableColumn(sql: String, column: String, affinity: String) {
        assertTrue(
            "$column ($affinity, nullable) present and NOT marked NOT NULL",
            sql.contains("`$column` $affinity,") || sql.contains("`$column` $affinity)"),
        )
    }

    private fun firstExisting(vararg candidates: String): File {
        candidates.forEach { c ->
            val f = File(c)
            if (f.isFile) return f
        }
        return File(candidates.first())
    }
}
