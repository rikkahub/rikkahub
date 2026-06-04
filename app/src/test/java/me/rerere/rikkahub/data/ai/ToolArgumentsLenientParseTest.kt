package me.rerere.rikkahub.data.ai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for lenient tool-argument parsing in [parseToolArguments].
 *
 * LLMs frequently emit tool-call arguments that are not strict JSON: most
 * commonly unquoted object keys (memory_tool's `{action:"create"}`). Strict
 * [Json] rejects these with "Unexpected JSON token", aborting an otherwise-valid
 * tool call. The fix routes tool args through a lenient parser so this structural
 * quirk is salvaged.
 *
 * Scope note: lenient mode salvages unquoted keys and barewords ONLY. It does not
 * normalize single-quoted literals — kotlinx.serialization treats `'` as part of a
 * bareword, not a string delimiter — so `{action:'create'}` parses to the literal
 * content `'create'`, not `create`. The single-quote cases below pin that contract.
 *
 * Before the fix the call site used strict JSON, so the accept-cases below threw
 * — the test was red for the exact reason in the ticket. After the fix they parse
 * to the expected [JsonObject]. The negative cases (truncation, bad escape) still
 * throw, proving lenient parsing does not swallow genuinely-unparseable input and
 * the existing "Invalid tool arguments JSON" error path is preserved.
 */
class ToolArgumentsLenientParseTest {

    private val strict = Json {}

    @Test
    fun `unquoted key is salvaged - mirrors memory_tool failure`() {
        val parsed = parseToolArguments("{action:\"create\"}").jsonObject
        assertEquals("create", parsed["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun `unquoted key with quoted value is salvaged - mirrors run_command relaxed syntax`() {
        val parsed = parseToolArguments("{command:\"ls -la\"}").jsonObject
        assertEquals("ls -la", parsed["command"]?.jsonPrimitive?.content)
    }

    @Test
    fun `strict JSON rejects the same input - documents the bug`() {
        // Proves the test would be red on unfixed (strict) code for the exact reason:
        // strict JSON rejects unquoted object keys.
        assertThrows(SerializationException::class.java) {
            strict.parseToJsonElement("{action:\"create\"}")
        }
        assertThrows(SerializationException::class.java) {
            strict.parseToJsonElement("{command:\"ls -la\"}")
        }
    }

    @Test
    fun `single-quoted value is NOT a string literal - lenient reads it as a bareword`() {
        // kotlinx.serialization 1.11.0 lenient mode treats only the double-quote as a
        // string delimiter; a single quote (0x27) is read as part of a bareword token.
        // So {action:'create'} yields the literal content "'create'", which would match
        // no branch in MemoryTools' `when (action)`. Pins that the parser does NOT
        // salvage single-quoted literals — the KDoc must not claim otherwise.
        val parsed = parseToolArguments("{action:'create'}").jsonObject
        assertEquals("'create'", parsed["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun `single-quoted key is NOT unquoted - lenient keeps the quotes in the key`() {
        // {'action':'create'} -> the key is the literal "'action'", so params["action"]
        // is null. Pins that single-quoted keys are not normalized to bare keys.
        val parsed = parseToolArguments("{'action':'create'}").jsonObject
        assertEquals(null, parsed["action"])
        assertEquals("'create'", parsed["'action'"]?.jsonPrimitive?.content)
    }

    @Test
    fun `truncated input still throws - lenient does not repair truncation`() {
        assertThrows(SerializationException::class.java) {
            parseToolArguments("{\"city\":\"S")
        }
    }

    @Test
    fun `bad escape still throws - lenient does not swallow invalid escapes`() {
        assertThrows(SerializationException::class.java) {
            parseToolArguments("{\"k\":\"\\x\"}")
        }
    }

    @Test
    fun `blank input parses to empty object`() {
        assertEquals(JsonObject(emptyMap()), parseToolArguments(""))
    }

    @Test
    fun `well-formed strict JSON still parses`() {
        val parsed = parseToolArguments("{\"action\":\"create\"}").jsonObject
        assertEquals("create", parsed["action"]?.jsonPrimitive?.content)
        assertTrue(parsed["action"] is JsonPrimitive)
    }
}
