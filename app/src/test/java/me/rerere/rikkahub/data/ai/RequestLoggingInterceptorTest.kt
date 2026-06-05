package me.rerere.rikkahub.data.ai

import me.rerere.common.android.Logging
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RequestLoggingInterceptorTest {

    @Before
    fun setup() {
        // Logging.recentLogs is a shared static buffer; clear it so a prior test's entries
        // don't leak into the assertions below.
        Logging.clear()
    }

    // Regression guard for AC #1 at the interceptor->Logging seam (issue #97). The helper-only
    // tests cannot catch a reintroduction of raw-body capture at the intercept() call site
    // (e.g. buffer.readUtf8()); this drives a real body through the interceptor and asserts the
    // persisted log entry holds metadata only. On the pre-fix master code that wrote the body
    // into the log buffer, the sentinel assertions below would fail.
    @Test
    fun `intercept stores body metadata only never raw prompt or document content`() {
        val rawBody = """
            {
              "model": "gpt-5",
              "messages": [
                {"role": "user", "content": "SECRET-PROMPT-summarize-my-diary"},
                {"role": "user", "content": "DOCUMENT-TEXT-confidential-clause"}
              ],
              "image": "data:image/png;base64,BASE64-IMAGE-PAYLOAD",
              "api_key": {"value": "sk-NESTED-LEAK"}
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer oauth-secret")
            .post(rawBody.toRequestBody("application/json".toMediaType()))
            .build()

        RequestLoggingInterceptor().intercept(StubChain(request))

        val logged = Logging.getRequestLogs().single()
        val body = logged.requestBody ?: error("request body metadata missing")

        listOf(
            "SECRET-PROMPT", "DOCUMENT-TEXT", "BASE64-IMAGE-PAYLOAD",
            "sk-NESTED-LEAK", "summarize-my-diary",
        ).forEach { sentinel ->
            assertFalse("Raw content leaked into log buffer: $sentinel", body.contains(sentinel))
        }

        // Only safe metadata: redacted placeholder + size + content type.
        assertTrue(body.contains("redacted"))
        assertTrue(body.contains(rawBody.toByteArray().size.toString()))
        assertTrue(body.contains("application/json"))

        // Secret request header value must not leak either.
        assertFalse(logged.requestHeaders.values.any { it.contains("oauth-secret") })
    }

    private class StubChain(private val request: Request) : Interceptor.Chain {
        override fun request(): Request = request

        override fun proceed(request: Request): Response =
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
    }
}
