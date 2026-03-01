package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okio.Buffer

class RequestLoggingInterceptor : Interceptor {
    companion object {
        private const val MAX_LOGGED_RESPONSE_BODY_BYTES = 64L * 1024L
        private const val TRUNCATED_NOTICE = "\n\n[response body truncated]"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toMap()
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
                    url = request.url.toString(),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toMap()
        val responseBody = response.extractBodyForLog()

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = request.url.toString(),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                responseBody = responseBody,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { get(it) ?: "" }
    }

    private fun Response.extractBodyForLog(): String? {
        val responseBody = body
        val mediaType = responseBody.contentType()

        if (!mediaType.isLikelyText()) return null
        if (mediaType?.subtype?.contains("event-stream", ignoreCase = true) == true) {
            return "[streaming response omitted]"
        }

        val peekedBody = runCatching { peekBody(MAX_LOGGED_RESPONSE_BODY_BYTES) }.getOrNull() ?: return null
        val charset = mediaType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val text = runCatching { peekedBody.source().readString(charset) }.getOrNull() ?: return null

        val contentLength = responseBody.contentLength()
        val isTruncated = (contentLength >= 0 && contentLength > MAX_LOGGED_RESPONSE_BODY_BYTES) ||
            (contentLength == -1L && peekedBody.contentLength() >= MAX_LOGGED_RESPONSE_BODY_BYTES)

        return if (isTruncated) {
            text + TRUNCATED_NOTICE
        } else {
            text
        }
    }

    private fun MediaType?.isLikelyText(): Boolean {
        val mediaType = this ?: return true
        if (mediaType.type.equals("text", ignoreCase = true)) return true
        val subtype = mediaType.subtype
        return subtype.contains("json", ignoreCase = true) ||
            subtype.contains("xml", ignoreCase = true) ||
            subtype.contains("html", ignoreCase = true) ||
            subtype.contains("x-www-form-urlencoded", ignoreCase = true) ||
            subtype.contains("javascript", ignoreCase = true) ||
            subtype.contains("graphql", ignoreCase = true) ||
            subtype.contains("yaml", ignoreCase = true)
    }
}
