package me.rerere.ai.util

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRequestFailureTest {
    @Test
    fun `non-stream failures retain status and structured provider message`() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.test/v1/chat/completions").build())
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .body("""{"error":{"message":"tools[0].function.parameters must be an object"}}"""
                .toResponseBody("application/json".toMediaType()))
            .build()

        val error = providerRequestFailure(response)

        assertTrue(error.message.orEmpty().contains("HTTP 400 Bad Request"))
        assertTrue(error.message.orEmpty().contains("tools[0].function.parameters"))
        assertFalse(error.message.orEmpty().contains("{\"error\""))
    }
}
