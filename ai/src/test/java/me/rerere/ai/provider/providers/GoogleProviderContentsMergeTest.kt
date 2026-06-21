package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [mergeAdjacentSameRoleContents].
 *
 * The bug: rikkahub emits one Gemini `model` turn per assistant UIMessage and never merges across
 * messages, so a long / multi-step / model-switched transcript can place two `model` turns
 * back-to-back where the second leads with a `functionCall`. The backend rejects that with HTTP 400
 * ("Please ensure that function call turn comes immediately after a user turn or after a function
 * response turn") — reproduced on device against daily-cloudcode-pa with model gemini-3-flash-agent.
 *
 * The invariant: after the merge, a `model` turn carrying a `functionCall` always immediately
 * follows a `user`/`functionResponse` turn — guaranteed structurally because no two adjacent turns
 * can share a role. Pure + network-free.
 */
class GoogleProviderContentsMergeTest {

    private fun textTurn(role: String, vararg texts: String) = buildJsonObject {
        put("role", role)
        putJsonArray("parts") { texts.forEach { add(buildJsonObject { put("text", it) }) } }
    }

    private fun fnCallTurn(role: String, name: String) = buildJsonObject {
        put("role", role)
        putJsonArray("parts") {
            add(buildJsonObject { putJsonObject("functionCall") { put("name", name) } })
        }
    }

    private fun roles(contents: JsonArray) =
        contents.map { it.jsonObject["role"]!!.jsonPrimitive.content }

    @Test
    fun `adjacent model turns where the second leads with a functionCall are merged`() {
        // The exact 400 shape: user -> model(text) -> model(functionCall) -> user(functionResponse).
        val contents = buildJsonArray {
            add(textTurn("user", "hi"))
            add(textTurn("model", "let me check"))
            add(fnCallTurn("model", "search"))
            add(textTurn("user", "tool result"))
        }

        val merged = mergeAdjacentSameRoleContents(contents)

        // The two model turns collapse, so the functionCall now lives in a model turn that
        // immediately follows the user turn -> satisfies the Gemini ordering invariant.
        assertEquals(listOf("user", "model", "user"), roles(merged))
        val modelParts = merged[1].jsonObject["parts"]!!.jsonArray
        assertEquals(2, modelParts.size)
        assertEquals("let me check", modelParts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(modelParts[1].jsonObject.containsKey("functionCall"))
    }

    @Test
    fun `no two adjacent turns share a role after merge`() {
        val contents = buildJsonArray {
            add(textTurn("user", "a"))
            add(textTurn("user", "b"))
            add(textTurn("model", "c"))
            add(textTurn("model", "d"))
            add(textTurn("model", "e"))
            add(textTurn("user", "f"))
        }

        val merged = mergeAdjacentSameRoleContents(contents)

        val r = roles(merged)
        assertEquals(listOf("user", "model", "user"), r)
        for (i in 1 until r.size) {
            assertTrue("adjacent turns must differ in role", r[i] != r[i - 1])
        }
    }

    @Test
    fun `already-valid alternating transcript is unchanged`() {
        val contents = buildJsonArray {
            add(textTurn("user", "q"))
            add(fnCallTurn("model", "tool"))
            add(textTurn("user", "fr"))
            add(textTurn("model", "answer"))
        }

        val merged = mergeAdjacentSameRoleContents(contents)

        assertEquals(4, merged.size)
        assertEquals(listOf("user", "model", "user", "model"), roles(merged))
    }

    @Test
    fun `merge preserves part order within a run`() {
        val contents = buildJsonArray {
            add(textTurn("model", "1", "2"))
            add(textTurn("model", "3"))
        }

        val merged = mergeAdjacentSameRoleContents(contents)

        assertEquals(1, merged.size)
        val parts = merged[0].jsonObject["parts"]!!.jsonArray
        assertEquals(listOf("1", "2", "3"), parts.map { it.jsonObject["text"]!!.jsonPrimitive.content })
    }

    @Test
    fun `empty contents stays empty`() {
        assertEquals(0, mergeAdjacentSameRoleContents(JsonArray(emptyList())).size)
    }
}
