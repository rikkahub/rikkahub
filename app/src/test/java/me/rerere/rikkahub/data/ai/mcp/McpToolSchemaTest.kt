package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.InputSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class McpToolSchemaTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun convert(properties: String, defs: String? = null): JsonObject {
        val schema = ToolSchema(
            properties = json(properties),
            required = listOf("trigger"),
            defs = defs?.let(::json),
        ).toSchema() as InputSchema.Obj
        assertEquals(listOf("trigger"), schema.required)
        return schema.properties
    }

    @Test
    fun `schema without refs is kept as is`() {
        val properties = """{"query":{"type":"string","description":"keyword"}}"""

        assertEquals(json(properties), convert(properties))
    }

    @Test
    fun `refs are inlined and sibling keywords override the definition`() {
        val result = convert(
            properties = """
                {
                  "trigger": {"${'$'}ref": "#/${'$'}defs/Trigger", "description": "override"},
                  "items": {"type": "array", "items": {"${'$'}ref": "#/${'$'}defs/Trigger"}},
                  "choice": {"anyOf": [{"${'$'}ref": "#/${'$'}defs/Trigger"}, {"type": "null"}]}
                }
            """,
            defs = """
                {
                  "Trigger": {
                    "type": "object",
                    "description": "original",
                    "properties": {"spec": {"${'$'}ref": "#/${'$'}defs/Spec"}}
                  },
                  "Spec": {"type": "string", "enum": ["a", "b"]}
                }
            """,
        )

        val trigger = """{"type":"object","description":"original","properties":{"spec":{"type":"string","enum":["a","b"]}}}"""
        assertEquals(
            json(
                """
                {
                  "trigger": {"type":"object","description":"override","properties":{"spec":{"type":"string","enum":["a","b"]}}},
                  "items": {"type": "array", "items": $trigger},
                  "choice": {"anyOf": [$trigger, {"type": "null"}]}
                }
                """
            ),
            result,
        )
    }

    @Test
    fun `literal data containing a ref field is kept as is`() {
        val properties = """
            {
              "trigger": {
                "enum": [{"${'$'}ref": "document.json"}],
                "const": {"${'$'}ref": "#/${'$'}defs/Spec"},
                "default": {"${'$'}ref": "document.json"},
                "examples": [{"${'$'}ref": "#/${'$'}defs/Spec"}]
              }
            }
        """

        assertEquals(json(properties), convert(properties, defs = """{"Spec": {"type": "string"}}"""))
    }

    @Test
    fun `properties named like keywords are still treated as schemas`() {
        val result = convert(
            properties = """
                {
                  "default": {"${'$'}ref": "#/${'$'}defs/Spec"},
                  "trigger": {"type": "object", "properties": {"enum": {"${'$'}ref": "#/${'$'}defs/Spec"}}}
                }
            """,
            defs = """{"Spec": {"type": "string"}}""",
        )

        assertEquals(
            json(
                """
                {
                  "default": {"type": "string"},
                  "trigger": {"type": "object", "properties": {"enum": {"type": "string"}}}
                }
                """
            ),
            result,
        )
    }

    @Test
    fun `cyclic refs are cut instead of expanding forever`() {
        val result = convert(
            properties = """{"trigger": {"${'$'}ref": "#/${'$'}defs/Node"}}""",
            defs = """
                {
                  "Node": {
                    "type": "object",
                    "properties": {"children": {"type": "array", "items": {"${'$'}ref": "#/${'$'}defs/Node"}}}
                  }
                }
            """,
        )

        assertEquals(
            json(
                """
                {
                  "trigger": {
                    "type": "object",
                    "properties": {"children": {"type": "array", "items": {"type": "object"}}}
                  }
                }
                """
            ),
            result,
        )
    }

    @Test
    fun `unresolvable refs are dropped so no dangling ref is sent`() {
        val result = convert(
            properties = """
                {
                  "trigger": {"${'$'}ref": "#/${'$'}defs/Missing", "description": "kept"},
                  "remote": {"${'$'}ref": "https://example.com/schema.json"}
                }
            """,
        )

        assertEquals(json("""{"trigger": {"description": "kept"}, "remote": {}}"""), result)
        assertFalse(result.toString().contains("\$ref"))
    }
}
