package me.rerere.ai.runtime.memory

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.runtime.contract.AssistantMemory
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the moved callback-based `memory_tool` builder (issue #243 slice 8/10). Drives
 * [buildMemoryTools] with fake suspend callbacks and asserts the action dispatch, the JSON result
 * shape `{id, content}` / `{success, id}`, and the required-argument errors — pinning that the tool
 * moved to `:ai-runtime` with identical behaviour and consumes the neutral [AssistantMemory].
 */
class MemoryToolsTest {

    private val json = Json

    private fun text(parts: List<UIMessagePart>): String =
        (parts.single() as UIMessagePart.Text).text

    private fun execute(params: Map<String, Any?>, recorder: Recorder): String = runBlocking {
        val tool = buildMemoryTools(
            json = json,
            onCreation = { content -> recorder.created.add(content); AssistantMemory(id = 42, content = content) },
            onUpdate = { id, content -> recorder.updated.add(id to content); AssistantMemory(id = id, content = content) },
            onDelete = { id -> recorder.deleted.add(id) },
        ).single()
        val element = buildJsonObject {
            params["action"]?.let { put("action", it as String) }
            params["id"]?.let { put("id", it as Int) }
            params["content"]?.let { put("content", it as String) }
        }
        text(tool.execute(element))
    }

    private class Recorder {
        val created = mutableListOf<String>()
        val updated = mutableListOf<Pair<Int, String>>()
        val deleted = mutableListOf<Int>()
    }

    @Test
    fun `create invokes onCreation and returns id and content`() {
        val rec = Recorder()
        val out = execute(mapOf("action" to "create", "content" to "remember this"), rec)
        assertEquals(listOf("remember this"), rec.created)
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals(42, obj["id"]!!.jsonPrimitive.int)
        assertEquals("remember this", obj["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `edit invokes onUpdate with id and content`() {
        val rec = Recorder()
        val out = execute(mapOf("action" to "edit", "id" to 7, "content" to "updated"), rec)
        assertEquals(listOf(7 to "updated"), rec.updated)
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals(7, obj["id"]!!.jsonPrimitive.int)
        assertEquals("updated", obj["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete invokes onDelete and returns success and id`() {
        val rec = Recorder()
        val out = execute(mapOf("action" to "delete", "id" to 9), rec)
        assertEquals(listOf(9), rec.deleted)
        val obj = json.parseToJsonElement(out).jsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(9, obj["id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `missing action errors`() {
        val rec = Recorder()
        assertThrows(IllegalStateException::class.java) { execute(emptyMap(), rec) }
    }

    @Test
    fun `create without content errors`() {
        val rec = Recorder()
        assertThrows(IllegalStateException::class.java) {
            execute(mapOf("action" to "create"), rec)
        }
    }

    @Test
    fun `edit without id errors`() {
        val rec = Recorder()
        assertThrows(IllegalStateException::class.java) {
            execute(mapOf("action" to "edit", "content" to "x"), rec)
        }
    }

    @Test
    fun `delete without id errors`() {
        val rec = Recorder()
        assertThrows(IllegalStateException::class.java) {
            execute(mapOf("action" to "delete"), rec)
        }
    }

    @Test
    fun `unknown action errors`() {
        val rec = Recorder()
        assertThrows(IllegalStateException::class.java) {
            execute(mapOf("action" to "purge"), rec)
        }
    }
}
