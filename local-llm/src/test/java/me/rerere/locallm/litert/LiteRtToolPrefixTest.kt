package me.rerere.locallm.litert

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtToolPrefixTest {

    private val sampleTool = Tool(
        name = "set_brightness",
        description = "Set the screen brightness to a specific value.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("value", buildJsonObject {
                        put("type", "integer")
                        put("description", "Brightness value 1..255")
                    })
                },
                required = listOf("value"),
            )
        },
        execute = { _ -> emptyList() },
    )

    @Test fun `buildPrefix mentions the tool name and the required argument`() {
        val prefix = LiteRtToolPrefix.buildPrefix(listOf(sampleTool))
        assertTrue("prefix should mention the tool name", prefix.contains("set_brightness"))
        assertTrue("prefix should mention the value arg", prefix.contains("value"))
        assertTrue(
            "prefix should describe the call shape so the model emits it",
            prefix.contains("<tool_call>"),
        )
    }

    @Test fun `buildPrefix returns empty when no tools enabled`() {
        assertEquals("", LiteRtToolPrefix.buildPrefix(emptyList()))
    }

    @Test fun `extractToolCalls picks a single well-formed call`() {
        val response = "Sure, I will adjust the brightness now. <tool_call>{\"name\": \"set_brightness\", \"arguments\": {\"value\": 128}}</tool_call>"
        val calls = LiteRtToolPrefix.extractToolCalls(response)
        assertEquals(1, calls.size)
        assertEquals("set_brightness", calls[0].name)
        val arg = calls[0].arguments["value"] as? JsonPrimitive
        assertNotNull(arg)
        assertEquals("128", arg!!.content)
    }

    @Test fun `extractToolCalls handles multiple calls in one response`() {
        val response = """
            Step 1. <tool_call>{"name": "tap", "arguments": {"x": 100, "y": 200}}</tool_call>
            Step 2. <tool_call>{"name": "wait", "arguments": {"ms": 500}}</tool_call>
        """.trimIndent()
        val calls = LiteRtToolPrefix.extractToolCalls(response)
        assertEquals(2, calls.size)
        assertEquals("tap", calls[0].name)
        assertEquals("wait", calls[1].name)
    }

    @Test fun `extractToolCalls is lenient about whitespace and trailing commas inside arguments`() {
        val response = "<tool_call>{\"name\": \"foo\", \"arguments\": {\"a\": 1, \"b\": 2,}}</tool_call>"
        val calls = LiteRtToolPrefix.extractToolCalls(response)
        assertEquals(1, calls.size)
        assertEquals("foo", calls[0].name)
        assertEquals(2, calls[0].arguments.size)
    }

    @Test fun `stripToolCallBlocks returns response with only the prose text`() {
        val response = "Here we go. <tool_call>{\"name\": \"foo\", \"arguments\": {}}</tool_call> Done."
        val prose = LiteRtToolPrefix.stripToolCallBlocks(response)
        assertEquals("Here we go.  Done.", prose)
    }

    @Test fun `extractToolCalls returns empty when no blocks present`() {
        val response = "I'm not sure how to do that yet."
        assertEquals(0, LiteRtToolPrefix.extractToolCalls(response).size)
    }

    @Test fun `extractToolCalls handles nested objects inside arguments`() {
        // Regression: an earlier \{[\s\S]*?\} regex stopped at the first "}" — breaking
        // nested argument objects.  The outer </tool_call> tag is now the real terminator.
        val response = """<tool_call>{"name": "set_config", "arguments": {"options": {"retry": true, "timeout": 30}}}</tool_call>"""
        val calls = LiteRtToolPrefix.extractToolCalls(response)
        assertEquals(1, calls.size)
        assertEquals("set_config", calls[0].name)
        // The outer "options" key should be present
        assertNotNull(calls[0].arguments["options"])
    }

    @Test fun `extractToolCalls returns empty when name field is missing`() {
        // A malformed emission without a "name" key should be silently skipped.
        val response = """<tool_call>{"arguments": {"x": 1}}</tool_call>"""
        assertEquals(0, LiteRtToolPrefix.extractToolCalls(response).size)
    }

    @Test fun `extractToolCalls defaults to empty arguments when arguments field is absent`() {
        // Model may omit "arguments" for no-arg tools; should default to an empty JsonObject.
        val response = """<tool_call>{"name": "get_time"}</tool_call>"""
        val calls = LiteRtToolPrefix.extractToolCalls(response)
        assertEquals(1, calls.size)
        assertEquals("get_time", calls[0].name)
        assertEquals(0, calls[0].arguments.size)
    }

    // -- unclosed-tag recovery: tiny local models drop the closing </tool_call> tag --

    @Test fun `extractToolCalls recovers a tool call with no closing tag`() {
        // Exactly the Qwen2.5-1.5B failure: emits <tool_call>{json} then stops, no </tool_call>.
        val response = """<tool_call>{"name":"search_web","arguments":{"query":"nmap installed in termux"}}"""
        val calls = LiteRtToolPrefix.extractToolCalls(response)
        assertEquals(1, calls.size)
        assertEquals("search_web", calls[0].name)
        assertEquals("nmap installed in termux", calls[0].arguments["query"]?.jsonPrimitive?.content)
    }

    @Test fun `extractToolCalls recovers an unclosed call with prose before it`() {
        val response = """Let me check that for you.
<tool_call>{"name":"get_time","arguments":{}}"""
        val calls = LiteRtToolPrefix.extractToolCalls(response)
        assertEquals(1, calls.size)
        assertEquals("get_time", calls[0].name)
    }

    @Test fun `extractToolCalls prefers the well-formed pass over recovery`() {
        // A complete block must still be parsed by pass 1, not the fallback.
        val response = """<tool_call>{"name":"a","arguments":{}}</tool_call> and then <tool_call>{"name":"b"""
        val calls = LiteRtToolPrefix.extractToolCalls(response)
        assertEquals(1, calls.size)
        assertEquals("a", calls[0].name)
    }

    @Test fun `extractToolCalls returns empty when an unclosed call is truncated mid-JSON`() {
        // No balanced object — the model cut off before finishing the JSON. Nothing to recover.
        val response = """<tool_call>{"name":"search_web","arguments":{"query":"""
        assertEquals(0, LiteRtToolPrefix.extractToolCalls(response).size)
    }
}
