package me.rerere.rikkahub.data.db.migrations

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit coverage for the pure JSON-rewrite helpers behind Migration_11_12 / Migration_13_14
 * (legacy `UIMessagePart` `type` discriminator -> short @SerialName).
 *
 * Both DB migrations delegate the actual message-blob rewrite to [migrateMessagesJson] /
 * [migratePartsArray]. Those are pure functions, so they belong in a fast JVM test rather
 * than the device-only `Migration_*_Test` instrumentation suite. A regression here would
 * silently fail to migrate a user's stored chat history on upgrade.
 */
class MigrationUtilsTest {

    @Test
    fun `rewrites fully-qualified part type to short serial name`() {
        val input = """
            [{"role":"user","parts":[{"type":"me.rerere.ai.ui.UIMessagePart.Text","text":"hi"}]}]
        """.trimIndent()
        val out = JsonInstant.parseToJsonElement(migrateMessagesJson(input)).jsonArray
        val part = out[0].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals("text", part["type"]!!.jsonPrimitive.content)
        assertEquals("hi", part["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rewrites short-class-name part type variants`() {
        val input = """
            [{"role":"assistant","parts":[
              {"type":"UIMessagePart.Image","url":"u"},
              {"type":"Reasoning","reasoning":"r"}
            ]}]
        """.trimIndent()
        val parts = JsonInstant.parseToJsonElement(migrateMessagesJson(input)).jsonArray[0]
            .jsonObject["parts"]!!.jsonArray
        assertEquals("image", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("reasoning", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rewrites nested output part types recursively`() {
        val input = """
            [{"role":"assistant","parts":[
              {"type":"me.rerere.ai.ui.UIMessagePart.Tool","output":[
                {"type":"UIMessagePart.Text","text":"nested"}
              ]}
            ]}]
        """.trimIndent()
        val toolPart = JsonInstant.parseToJsonElement(migrateMessagesJson(input)).jsonArray[0]
            .jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals("tool", toolPart["type"]!!.jsonPrimitive.content)
        val nested = toolPart["output"]!!.jsonArray[0].jsonObject
        assertEquals("text", nested["type"]!!.jsonPrimitive.content)
        assertEquals("nested", nested["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `leaves already-short types untouched`() {
        val input = """[{"role":"user","parts":[{"type":"text","text":"hi"}]}]"""
        assertEquals(
            JsonInstant.parseToJsonElement(input),
            JsonInstant.parseToJsonElement(migrateMessagesJson(input))
        )
    }

    @Test
    fun `returns original string on malformed json`() {
        val bad = "{not json"
        assertEquals(bad, migrateMessagesJson(bad))
    }

    @Test
    fun `handles empty message array`() {
        assertEquals(
            JsonInstant.parseToJsonElement("[]"),
            JsonInstant.parseToJsonElement(migrateMessagesJson("[]"))
        )
    }

    @Test
    fun `unknown part type is passed through unchanged`() {
        val input = """[{"role":"user","parts":[{"type":"something_new","data":"x"}]}]"""
        val part = JsonInstant.parseToJsonElement(migrateMessagesJson(input)).jsonArray[0]
            .jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals("something_new", part["type"]!!.jsonPrimitive.content)
        assertEquals("x", part["data"]!!.jsonPrimitive.content)
    }
}
