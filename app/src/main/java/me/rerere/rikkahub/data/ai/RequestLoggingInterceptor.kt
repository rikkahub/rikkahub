package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.rikkahub.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            val request = chain.request()
            return try {
                chain.proceed(request).also { response ->
                    recordDebugEvidence(request = request, responseCode = response.code)
                }
            } catch (error: Exception) {
                recordDebugEvidence(request = request, responseCode = null)
                throw error
            }
        }

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
            recordDebugEvidence(request = request, responseCode = null)
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
        recordDebugEvidence(request = request, responseCode = response.code)

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = request.url.toString(),
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

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { get(it) ?: "" }
    }

    private fun recordDebugEvidence(request: okhttp3.Request, responseCode: Int?) {
        if (BuildConfig.DEBUG) {
            DebugHttpResponseEvidenceStore.record(request = request, responseCode = responseCode)
        }
    }
}
