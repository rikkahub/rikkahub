package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.util.HttpException
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the SSE `event: error` handling in ClaudeProvider.streamText.
 *
 * Bug: the error branch did `eventData["error"]?.parseErrorDetail()` and then
 * `close(error)`. When the error frame lacked a top-level "error" key the `?.`
 * yielded null, and `close(null)` completes the callbackFlow as a NORMAL success,
 * silently swallowing the upstream error (user sees a truncated-but-"successful"
 * response). An `event: error` frame MUST always terminate with a non-null Throwable.
 */
class ClaudeProviderStreamErrorTest {

    private lateinit var provider: ClaudeProvider

    @Before
    fun setUp() {
        provider = ClaudeProvider(OkHttpClient())
    }

    private fun invokeResolveStreamError(frame: JsonObject): HttpException {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "resolveStreamError",
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(provider, frame) as HttpException
    }

    @Test
    fun `error frame without top-level error key still resolves a non-null exception`() {
        // Previously close(null) -> flow completes as success, error swallowed.
        val frame = buildJsonObject {
            put("type", "error")
            put("message", "Overloaded")
        }

        val result = invokeResolveStreamError(frame)

        assertNotNull(result)
        assertTrue(result.message!!.isNotBlank())
        assertTrue(result.message!!.contains("Overloaded"))
    }

    @Test
    fun `well-formed error frame preserves the nested error message`() {
        val frame = buildJsonObject {
            put("type", "error")
            put("error", buildJsonObject {
                put("type", "overloaded_error")
                put("message", "Overloaded")
            })
        }

        val result = invokeResolveStreamError(frame)

        assertEquals("Overloaded", result.message)
    }

    @Test
    fun `empty error frame still resolves a non-null exception with non-blank message`() {
        val frame = buildJsonObject {
            put("type", "error")
        }

        val result = invokeResolveStreamError(frame)

        assertNotNull(result)
        assertTrue(result.message!!.isNotBlank())
    }
}
