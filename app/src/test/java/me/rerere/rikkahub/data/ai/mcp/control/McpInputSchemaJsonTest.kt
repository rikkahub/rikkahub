package me.rerere.rikkahub.data.ai.mcp.control

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in how mcp_list_tools renders an MCP tool's inputSchema. The LLM relies on this to
 * see each remote tool's argument shape; a regression that drops the properties or required
 * list would leave the model guessing at arguments.
 */
class McpInputSchemaJsonTest {

    @Test
    fun `null schema renders as null so callers can omit the field`() {
        assertNull(inputSchemaJson(null))
    }

    @Test
    fun `obj schema renders type properties and required`() {
        val schema = InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject { put("type", "string") })
                put("limit", buildJsonObject { put("type", "integer") })
            },
            required = listOf("query"),
        )
        val json = inputSchemaJson(schema)!!
        assertEquals("object", json["type"]?.jsonPrimitive?.content)
        val props = json["properties"]!!.jsonObject
        assertTrue("properties carries query", props.containsKey("query"))
        assertTrue("properties carries limit", props.containsKey("limit"))
        assertEquals("string", props["query"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        val required = json["required"]!!.jsonArray
        assertEquals(1, required.size)
        assertEquals("query", required[0].jsonPrimitive.content)
    }

    @Test
    fun `obj schema with null required omits the required key`() {
        val schema = InputSchema.Obj(
            properties = buildJsonObject {
                put("flag", buildJsonObject { put("type", "boolean") })
            },
            required = null,
        )
        val json = inputSchemaJson(schema)!!
        assertEquals("object", json["type"]?.jsonPrimitive?.content)
        assertFalse("no required key when schema had none", json.containsKey("required"))
    }

    @Test
    fun `empty-properties schema still renders a valid object`() {
        val json = inputSchemaJson(InputSchema.Obj(properties = buildJsonObject {}, required = null))!!
        assertEquals("object", json["type"]?.jsonPrimitive?.content)
        assertTrue("properties present even when empty", json.containsKey("properties"))
        assertEquals(0, json["properties"]!!.jsonObject.size)
    }
}
