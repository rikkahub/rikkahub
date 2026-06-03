package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

const val REDACTED = "<redacted>"

// Header names whose values carry credentials and must never reach the log buffer.
// Matched case-insensitively. Covers all providers plus user-supplied customHeaders.
private val SECRET_HEADER_NAMES = setOf(
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

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = redactHeaders(request.headers)
        val requestUrl = redactUrlSecrets(request.url.toString())
        val requestBody = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
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
