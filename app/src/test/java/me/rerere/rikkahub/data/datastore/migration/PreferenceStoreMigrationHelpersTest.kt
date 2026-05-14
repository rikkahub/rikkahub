package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the pure JSON-rewrite helpers behind the DataStore migrations
 * (V1 mcpServers type fix, V2 assistant UIMessagePart type fix, V3 quickMessages extraction).
 *
 * These helpers are the load-bearing part of each [androidx.datastore.core.DataMigration];
 * the Migration classes themselves only touch DataStore plumbing. Pinning the rewrite logic
 * here means a regression that would silently corrupt a user's saved providers/assistants on
 * upgrade fails fast in CI rather than on the user's device.
 */
class PreferenceStoreMigrationHelpersTest {

    // ---- V1: migrateMcpServersJson ----

    @Test
    fun `mcp migration rewrites legacy fully-qualified sse type`() {
        val input = """
            [{"type":"me.rerere.rikkahub.data.mcp.McpServerConfig.SseTransportServer","name":"a"}]
        """.trimIndent()
        val out = JsonInstant.parseToJsonElement(migrateMcpServersJson(input)).jsonArray
        assertEquals("sse", out[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("a", out[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mcp migration rewrites legacy fully-qualified streamable http type`() {
        val input = """
            [{"type":"me.rerere.rikkahub.data.mcp.McpServerConfig.StreamableHTTPServer","name":"b"}]
        """.trimIndent()
        val out = JsonInstant.parseToJsonElement(migrateMcpServersJson(input)).jsonArray
        assertEquals("streamable_http", out[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mcp migration leaves already-short type untouched`() {
        val input = """[{"type":"sse","name":"c"}]"""
        val out = JsonInstant.parseToJsonElement(migrateMcpServersJson(input)).jsonArray
        assertEquals("sse", out[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mcp migration handles empty array`() {
        assertEquals(0, JsonInstant.parseToJsonElement(migrateMcpServersJson("[]")).jsonArray.size)
    }

    // ---- V2: migrateAssistantsJson ----

    @Test
    fun `assistant migration rewrites preset message part types`() {
        val input = """
            [{"id":"x","presetMessages":[{"role":"user","parts":[
              {"type":"me.rerere.ai.ui.UIMessagePart.Text","text":"hi"},
              {"type":"UIMessagePart.Image","url":"u"}
            ]}]}]
        """.trimIndent()
        val out = JsonInstant.parseToJsonElement(migrateAssistantsJson(input)).jsonArray
        val parts = out[0].jsonObject["presetMessages"]!!.jsonArray[0]
            .jsonObject["parts"]!!.jsonArray
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        // payload fields preserved
        assertEquals("hi", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("u", parts[1].jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant migration is a no-op when types already short`() {
        val input = """
            [{"id":"x","presetMessages":[{"role":"user","parts":[{"type":"text","text":"hi"}]}]}]
        """.trimIndent()
        // identical structurally — round-trip parse to compare semantically, not byte-wise
        val out = JsonInstant.parseToJsonElement(migrateAssistantsJson(input))
        assertEquals(JsonInstant.parseToJsonElement(input), out)
    }

    @Test
    fun `assistant migration preserves assistants with no preset messages`() {
        val input = """[{"id":"x","name":"keepme"}]"""
        val out = JsonInstant.parseToJsonElement(migrateAssistantsJson(input)).jsonArray
        assertEquals("keepme", out[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant migration returns original on malformed json`() {
        val bad = "{not json"
        assertEquals(bad, migrateAssistantsJson(bad))
    }

    // ---- V3: migrateAssistantsQuickMessages ----

    @Test
    fun `quickmessages migration extracts inline messages and assigns ids`() {
        val input = """
            [{"id":"a1","quickMessages":[{"title":"t1","content":"c1"},{"title":"t2","content":"c2"}]}]
        """.trimIndent()
        val (migratedAssistants, extracted) = migrateAssistantsQuickMessages(input)

        // extracted global list has both messages, each with a generated id
        assertEquals(2, extracted.size)
        extracted.forEach { e ->
            assertNotNull((e as JsonObject)["id"])
        }

        val assistant = JsonInstant.parseToJsonElement(migratedAssistants).jsonArray[0].jsonObject
        // old inline field removed, id list added
        assertFalse(assistant.containsKey("quickMessages"))
        val ids = assistant["quickMessageIds"]!!.jsonArray
        assertEquals(2, ids.size)
        // the id list matches the extracted message ids
        val extractedIds = extracted.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }.toSet()
        val assistantIds = ids.map { it.jsonPrimitive.content }.toSet()
        assertEquals(extractedIds, assistantIds)
    }

    @Test
    fun `quickmessages migration leaves assistants without inline messages untouched`() {
        val input = """[{"id":"a1","name":"plain"}]"""
        val (migratedAssistants, extracted) = migrateAssistantsQuickMessages(input)
        assertTrue(extracted.isEmpty())
        assertEquals(JsonInstant.parseToJsonElement(input), JsonInstant.parseToJsonElement(migratedAssistants))
    }

    @Test
    fun `quickmessages migration returns original on malformed json`() {
        val bad = "{not json"
        val (migratedAssistants, extracted) = migrateAssistantsQuickMessages(bad)
        assertEquals(bad, migratedAssistants)
        assertTrue(extracted.isEmpty())
    }
}
