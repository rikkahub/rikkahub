package me.rerere.rikkahub.data.ai

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for credential leakage into logcat.
 *
 * The app's shared OkHttp client attaches okhttp's [HttpLoggingInterceptor] at HEADERS
 * level. Without redaction it prints `Authorization: Bearer <token>` (and provider API
 * keys) verbatim to logcat, harvestable via adb/READ_LOGS. DataSourceModule now redacts
 * every header in [SECRET_HEADER_NAMES]; this proves the configured interceptor never
 * emits a secret value while still showing which auth header was sent.
 */
class LogcatHeaderRedactionTest {

    private class FakeChain(
        private val request: Request,
        private val response: Response,
    ) : Interceptor.Chain {
        override fun request() = request
        override fun proceed(request: Request) = response
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun readTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun writeTimeoutMillis() = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
    }

    // Mirror of the interceptor configured in DataSourceModule, with a capturing logger.
    private fun loggedLinesFor(request: Request): List<String> {
        val lines = mutableListOf<String>()
        val interceptor = HttpLoggingInterceptor { lines.add(it) }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
            SECRET_HEADER_NAMES.forEach { redactHeader(it) }
        }
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_2)
            .code(200)
            .message("OK")
            .body("".toResponseBody(null))
            .build()
        interceptor.intercept(FakeChain(request, response))
        return lines
    }

    @Test
    fun authorizationAndApiKeysAreRedactedInLogcatOutput() {
        val request = Request.Builder()
            .url("https://api.firecrawl.dev/v2/search")
            .header("Authorization", "Bearer super-secret-token-value")
            .header("x-api-key", "ck-super-secret")
            .header("x-goog-api-key", "g-super-secret")
            .header("anthropic-version", "2023-06-01")
            .build()

        val log = loggedLinesFor(request).joinToString("\n")

        // No secret value reaches the log buffer.
        assertFalse("bearer token leaked to logcat", log.contains("super-secret-token-value"))
        assertFalse("openai-style key leaked to logcat", log.contains("ck-super-secret"))
        assertFalse("google key leaked to logcat", log.contains("g-super-secret"))

        // Header names are still visible (you can see WHICH auth was sent), and benign
        // headers pass through, so the logging stays useful for debugging.
        assertTrue("Authorization header name should still be logged", log.contains("Authorization"))
        assertTrue("benign header should pass through", log.contains("2023-06-01"))
    }
}
