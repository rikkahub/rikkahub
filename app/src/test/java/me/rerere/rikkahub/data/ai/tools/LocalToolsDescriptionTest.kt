package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolsDescriptionTest {
    @Test
    fun `appendTopToolExample adds example for top tools`() {
        val tool = Tool(
            name = "set_volume",
            description = "Set the volume.",
            execute = { emptyList() },
        )

        val result = appendTopToolExample(tool)

        assertTrue(result.description.contains("Example: set_volume(stream=\"media\", percent=50)."))
    }

    @Test
    fun `appendTopToolExample leaves existing examples unchanged`() {
        val tool = Tool(
            name = "set_volume",
            description = "Set the volume. Example: set_volume(stream=\"alarm\", percent=30).",
            execute = { emptyList() },
        )

        val result = appendTopToolExample(tool)

        assertEquals(tool.description, result.description)
    }

    @Test
    fun `appendTopToolExample ignores tools outside quick-win set`() {
        val tool = Tool(
            name = "custom_tool",
            description = "Custom.",
            parameters = { null },
            systemPrompt = { _, _ -> "" },
            execute = { emptyList() },
        )

        val result = appendTopToolExample(tool)

        assertEquals(tool.description, result.description)
    }

    @Test
    fun `addHumanErrorEnvelopes adds readable human error to json error results`() = runBlocking {
        val tool = Tool(
            name = "write_text_file",
            description = "Write file.",
            execute = {
                listOf(UIMessagePart.Text("""{"error":"parent_missing","detail":"Parent directory does not exist"}"""))
            },
        )

        val result = addHumanErrorEnvelopes(tool).execute(Json.parseToJsonElement("{}")).single()
            as UIMessagePart.Text
        val json = Json.parseToJsonElement(result.text).jsonObject

        assertEquals("parent_missing", json["error"]?.jsonPrimitive?.content)
        assertEquals(
            "parent missing: Parent directory does not exist",
            json["human_error"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `addHumanErrorEnvelopes leaves existing human error unchanged`() = runBlocking {
        val text = """{"error":"permission_denied","human_error":"Storage permission is missing"}"""
        val tool = Tool(
            name = "read_file",
            description = "Read file.",
            execute = { listOf(UIMessagePart.Text(text)) },
        )

        val result = addHumanErrorEnvelopes(tool).execute(Json.parseToJsonElement("{}")).single()
            as UIMessagePart.Text

        assertEquals(text, result.text)
    }

    @Test
    fun `addHumanErrorEnvelopes normalizes reason into detail`() = runBlocking {
        val tool = Tool(
            name = "read_file",
            description = "Read file.",
            execute = {
                listOf(UIMessagePart.Text("""{"error":"permission_denied","reason":"Missing storage access"}"""))
            },
        )

        val result = addHumanErrorEnvelopes(tool).execute(Json.parseToJsonElement("{}")).single()
            as UIMessagePart.Text
        val json = Json.parseToJsonElement(result.text).jsonObject

        assertEquals("Missing storage access", json["detail"]?.jsonPrimitive?.content)
        assertEquals(null, json["reason"])
        assertEquals("permission denied: Missing storage access", json["human_error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `addHumanErrorEnvelopes preserves recovery and additional fields`() = runBlocking {
        val tool = Tool(
            name = "read_file",
            description = "Read file.",
            execute = {
                listOf(UIMessagePart.Text("""{"error":"missing_file","recovery":"Choose an existing file","path":"/tmp/a.txt"}"""))
            },
        )

        val result = addHumanErrorEnvelopes(tool).execute(Json.parseToJsonElement("{}")).single()
            as UIMessagePart.Text
        val json = Json.parseToJsonElement(result.text).jsonObject

        assertEquals("Choose an existing file", json["recovery"]?.jsonPrimitive?.content)
        assertEquals("/tmp/a.txt", json["path"]?.jsonPrimitive?.content)
        assertTrue(json.containsKey("human_error"))
    }

    @Test
    fun `addHumanErrorEnvelopes leaves json without error unchanged`() = runBlocking {
        val text = """{"ok":true}"""
        val tool = Tool(
            name = "read_file",
            description = "Read file.",
            execute = { listOf(UIMessagePart.Text(text)) },
        )

        val result = addHumanErrorEnvelopes(tool).execute(Json.parseToJsonElement("{}")).single()
            as UIMessagePart.Text

        assertEquals(text, result.text)
    }

    @Test
    fun `addHumanErrorEnvelopes leaves non json text unchanged`() = runBlocking {
        val tool = Tool(
            name = "show_toast",
            description = "Show toast.",
            execute = { listOf(UIMessagePart.Text("ok")) },
        )

        val result = addHumanErrorEnvelopes(tool).execute(Json.parseToJsonElement("{}")).single()
            as UIMessagePart.Text

        assertEquals("ok", result.text)
    }
}
