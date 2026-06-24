package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

const val REDACTED = "<redacted>"

// Header names whose values carry credentials and must never reach the log buffer.
// Matched case-insensitively. Covers all providers plus user-supplied customHeaders.
// Shared with the OkHttp HttpLoggingInterceptor (logcat) so both redaction paths stay
// in sync from a single source of truth.
internal val SECRET_HEADER_NAMES = setOf(
    "authorization",
    "x-api-key",
    "x-goog-api-key",
    "api-key",
)

// URL query parameters that carry credentials (e.g. Google's `?key=`).
private val SECRET_QUERY_PARAMS = setOf("key")

fun redactHeaders(headers: Headers): Map<String, String> {
    return headers.names().associateWith { name ->
        if (name.lowercase() in SECRET_HEADER_NAMES) REDACTED else headers[name] ?: ""
    }
}

// AC #1 (issue #97): in-app request logs must NOT store raw AI prompts or document
// contents by default. This OkHttp client carries only AI-provider traffic, so every
// request body is an AI request body — user prompts, document text, base64 image/audio
// payloads, and provider-specific secret fields. Storing the body (even truncated, even
// with known secret fields stripped) still persists raw prompt/document text into the
// in-memory log buffer that LogPage renders and lets the user copy out. Truncation is not
// equivalent to "do not store raw prompts", and field-name redaction cannot cover secrets
// nested in objects/arrays/numbers. So we store safe metadata only — never body content.
//
// Per-field redaction + truncation is intentionally not offered behind an opt-in here:
// that is a separate "verbose logging" product decision outside issue #97's scope, and
// keeping a single safe default avoids a fail-open path on unrecognized self-hosted hosts.
// Called only for a present request body. byteCount is the body's known content length, or
// null when the length is unknown (e.g. streamed) — we report "unknown" rather than read the
// body to measure it, because reading raw AI/document content back is exactly what AC #1 bans.
fun bodyMetadataForLog(byteCount: Long?, contentType: String?): String {
    val size = byteCount?.let { "$it bytes" } ?: "unknown bytes"
    val type = contentType?.takeIf { it.isNotBlank() } ?: "unknown"
    return "<redacted request body: $size, content-type=$type>"
}

fun redactUrlSecrets(url: String): String {
    val httpUrl = url.toHttpUrlOrNull() ?: return url
    if (httpUrl.queryParameterNames.none { it.lowercase() in SECRET_QUERY_PARAMS }) return url
    val builder = httpUrl.newBuilder()
    httpUrl.queryParameterNames.forEach { name ->
        if (name.lowercase() in SECRET_QUERY_PARAMS) {
            builder.setQueryParameter(name, REDACTED)
        }
    }
    return builder.build().toString()
}

/**
 * @param enabled read LIVE per request: when it returns false the interceptor is a pure pass-through
 *   (no [LogEntry.RequestLog] recorded), so the in-app request log stays empty until the user opts in
 *   (Advanced > Diagnostics). Defaults to always-on for callers/tests that don't gate it.
 */
class RequestLoggingInterceptor(
    private val enabled: () -> Boolean = { true },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!enabled()) return chain.proceed(chain.request())

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = redactHeaders(request.headers)
        val requestUrl = redactUrlSecrets(request.url.toString())
        val requestBody = request.body?.let { body ->
            // contentLength() can be -1 (unknown, e.g. streamed). NEVER call body.writeTo()
            // here: a one-shot source would be exhausted before chain.proceed() sends it,
            // and writing the body into a buffer reintroduces raw prompt/document retention
            // that AC #1 forbids (see bodyMetadataForLog doc above). Report "unknown" size.
            val byteCount = body.contentLength().takeIf { it >= 0 }
            bodyMetadataForLog(byteCount, body.contentType()?.toString())
        }

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = requestUrl,
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = redactHeaders(response.headers)

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = requestUrl,
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }
}
