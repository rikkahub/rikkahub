package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillContent
import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 16 audit fix — pure-JVM tests for the `skill_get_content` tool factory. The
 * disk-reading [SkillContent] producer is swapped for an in-memory map via the
 * `contentReader` seam, so no Android Context is needed.
 */
class SkillGetContentToolTest {

    private fun meta(name: String) =
        SkillMetadata(name = name, description = "desc for $name", skillDir = File("/tmp/$name"))

    private fun exec(tool: me.rerere.ai.core.Tool, args: String): String = runBlocking {
        (tool.execute(Json.parseToJsonElement(args)).first() as UIMessagePart.Text).text
    }

    @Test
    fun `found - returns content body description and enabled true`() {
        val content = SkillContent(
            name = "zip-and-send",
            description = "Zip a file and send it",
            format = "native",
            sourceLabel = "manual",
            contentMd = "# Instructions\nDo the thing.",
        )
        val tool = skillGetContentTool(
            enabledSkills = setOf("zip-and-send", "agent-core"),
            allSkills = listOf(meta("zip-and-send"), meta("agent-core")),
            contentReader = { name -> if (name == "zip-and-send") content else null },
        )

        val out = Json.parseToJsonElement(exec(tool, """{"name":"zip-and-send"}""")).jsonObject

        assertEquals("zip-and-send", out["name"]!!.jsonPrimitive.content)
        assertEquals("Zip a file and send it", out["description"]!!.jsonPrimitive.content)
        assertEquals("native", out["format"]!!.jsonPrimitive.content)
        assertEquals("manual", out["source_label"]!!.jsonPrimitive.content)
        assertEquals("# Instructions\nDo the thing.", out["content_md"]!!.jsonPrimitive.content)
        assertTrue(out["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertNull(out["args_schema"])
        assertNull(out["error"])
    }

    @Test
    fun `not found - returns skill_not_found with available_skills`() {
        val tool = skillGetContentTool(
            enabledSkills = setOf("agent-core"),
            allSkills = listOf(meta("agent-core"), meta("morning-briefing")),
            contentReader = { null },
        )

        val out = Json.parseToJsonElement(exec(tool, """{"name":"ghost"}""")).jsonObject

        assertEquals("skill_not_found", out["error"]!!.jsonPrimitive.content)
        assertEquals("ghost", out["name"]!!.jsonPrimitive.content)
        val available = out["available_skills"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("agent-core", "morning-briefing"), available)
    }

    @Test
    fun `disabled skill - content still returned with enabled false`() {
        val content = SkillContent(
            name = "morning-briefing",
            description = "Daily briefing",
            contentMd = "# Briefing",
        )
        val tool = skillGetContentTool(
            enabledSkills = setOf("agent-core"), // morning-briefing NOT enabled
            allSkills = listOf(meta("agent-core"), meta("morning-briefing")),
            contentReader = { name -> if (name == "morning-briefing") content else null },
        )

        val out = Json.parseToJsonElement(exec(tool, """{"name":"morning-briefing"}""")).jsonObject

        assertEquals("# Briefing", out["content_md"]!!.jsonPrimitive.content)
        assertFalse(out["enabled"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `args schema present - surfaced in response`() {
        val schema = buildJsonObject {
            put("file_path", "Absolute path to the file to zip")
        }
        val content = SkillContent(
            name = "zip-and-send",
            description = "Zip a file",
            contentMd = "# body",
            argsSchema = schema,
        )
        val tool = skillGetContentTool(
            enabledSkills = setOf("zip-and-send"),
            allSkills = listOf(meta("zip-and-send")),
            contentReader = { content },
        )

        val out = Json.parseToJsonElement(exec(tool, """{"name":"zip-and-send"}""")).jsonObject

        assertEquals(
            "Absolute path to the file to zip",
            out["args_schema"]!!.jsonObject["file_path"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `args schema absent - key omitted not null`() {
        val content = SkillContent(name = "plain", description = "no schema", contentMd = "# x")
        val tool = skillGetContentTool(
            enabledSkills = setOf("plain"),
            allSkills = listOf(meta("plain")),
            contentReader = { content },
        )

        val out = Json.parseToJsonElement(exec(tool, """{"name":"plain"}""")).jsonObject

        assertFalse(out.containsKey("args_schema"))
    }

    @Test
    fun `missing name arg - returns missing_required_arg`() {
        val tool = skillGetContentTool(
            enabledSkills = emptySet(),
            allSkills = listOf(meta("agent-core")),
            contentReader = { null },
        )

        val out = Json.parseToJsonElement(exec(tool, """{}""")).jsonObject

        assertEquals("missing_required_arg", out["error"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("agent-core"),
            out["available_skills"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
