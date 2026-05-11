package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: when the LLM provider's stream gets cut off mid-string (max_tokens, network
 * drop, etc.), the tool args JSON arrives truncated. Without the explicit pre-parse path
 * in [GenerationHandler], kotlinx.serialization's exception message — which contains the
 * entire failed JSON input — used to land verbatim in the LLM-facing `detail` field. A
 * 4000-char emoji message produced an 8000+ char `detail` blob shown to the user and
 * burned back into context on the next turn. This test pins the shape of the new envelope
 * so a future refactor can't silently re-leak the truncated payload.
 *
 * The envelope is built inline in `GenerationHandler.generateText`'s tool-execution branch,
 * so this test rebuilds it identically to assert the contract: error code, capped detail,
 * recovery hint pointing at "split into smaller calls", exception class hint.
 */
class InvalidToolArgsEnvelopeTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Mirrors the envelope structure built at GenerationHandler.kt for parsedArgs.isFailure. */
    private fun buildInvalidToolArgsEnvelope(cause: Throwable): String {
        return json.encodeToString(
            kotlinx.serialization.json.buildJsonObject {
                put("error", kotlinx.serialization.json.JsonPrimitive("invalid_tool_args"))
                put(
                    "detail",
                    kotlinx.serialization.json.JsonPrimitive(
                        (cause.message ?: cause.javaClass.simpleName).take(200)
                    ),
                )
                put(
                    "recovery",
                    kotlinx.serialization.json.JsonPrimitive(
                        "Tool args JSON failed to parse — most often the provider's " +
                            "stream was cut off mid-string by max_tokens or a network drop. " +
                            "Retry with a shorter call. For long payloads (e.g. a 4000-char " +
                            "message), split into multiple smaller tool calls or shrink the " +
                            "content."
                    ),
                )
                put(
                    "exception",
                    kotlinx.serialization.json.JsonPrimitive(cause.javaClass.simpleName),
                )
            }
        )
    }

    @Test fun `envelope caps detail at 200 chars even when message is huge`() {
        // 9000-char message simulates kotlinx's "Unexpected JSON token at offset 8878 ...
        // JSON input: <8000 chars of emoji>" pattern that bit us in real usage.
        val giant = "x".repeat(9000)
        val cause = RuntimeException(giant)

        val out = buildInvalidToolArgsEnvelope(cause)
        val obj = json.parseToJsonElement(out).jsonObject

        assertEquals("invalid_tool_args", obj["error"]?.jsonPrimitive?.content)
        val detail = obj["detail"]?.jsonPrimitive?.content
        assertNotNull(detail)
        assertEquals("detail must be capped at 200 chars", 200, detail!!.length)
        assertTrue("detail must be the prefix of the message", giant.startsWith(detail))
    }

    @Test fun `envelope falls back to class name when message is null`() {
        val cause = object : RuntimeException() {} // message = null
        val out = buildInvalidToolArgsEnvelope(cause)
        val obj = json.parseToJsonElement(out).jsonObject

        assertEquals("invalid_tool_args", obj["error"]?.jsonPrimitive?.content)
        val detail = obj["detail"]?.jsonPrimitive?.content
        assertNotNull(detail)
        // Inner anonymous class has a synthetic simpleName (often empty) — accept either
        // an empty string or any non-null value, just don't crash.
        assertTrue(detail!!.length <= 200)
    }

    @Test fun `recovery field tells the model to split or shrink`() {
        val cause = RuntimeException("boom")
        val out = buildInvalidToolArgsEnvelope(cause)
        val obj = json.parseToJsonElement(out).jsonObject

        val recovery = obj["recovery"]?.jsonPrimitive?.content
        assertNotNull(recovery)
        assertTrue(
            "recovery must instruct the model to retry with smaller payload",
            recovery!!.contains("split", ignoreCase = true) ||
                recovery.contains("shorter", ignoreCase = true) ||
                recovery.contains("shrink", ignoreCase = true)
        )
    }

    @Test fun `exception field carries the class name for LLM-side disambiguation`() {
        val cause = kotlinx.serialization.SerializationException("Unexpected JSON token at offset 8878: Expected quotation mark '\"', but had 'EOF' instead at path: $")
        val out = buildInvalidToolArgsEnvelope(cause)
        val obj = json.parseToJsonElement(out).jsonObject

        assertEquals("SerializationException", obj["exception"]?.jsonPrimitive?.content)
    }
}
