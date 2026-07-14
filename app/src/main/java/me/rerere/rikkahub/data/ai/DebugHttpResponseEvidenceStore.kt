package me.rerere.rikkahub.data.ai

import okhttp3.Request

internal data class DebugHttpResponseEvidence(
    val timestamp: Long,
    val origin: String,
    val endpointPath: String,
    val method: String,
    val responseCode: Int?,
)

internal object DebugHttpResponseEvidenceStore {
    private const val MAX_ENTRIES = 50
    private val entries = ArrayDeque<DebugHttpResponseEvidence>()

    fun record(request: Request, responseCode: Int?) {
        val url = request.url
        if (!url.encodedPath.endsWith("/chat/completions")) return
        val host = if (':' in url.host) "[${url.host}]" else url.host
        val defaultPort = when (url.scheme) {
            "http" -> 80
            "https" -> 443
            else -> return
        }
        val port = if (url.port == defaultPort) "" else ":${url.port}"
        val evidence = DebugHttpResponseEvidence(
            timestamp = System.currentTimeMillis(),
            origin = "${url.scheme}://$host$port",
            endpointPath = "/chat/completions",
            method = request.method,
            responseCode = responseCode,
        )

        synchronized(entries) {
            entries.addFirst(evidence)
            while (entries.size > MAX_ENTRIES) {
                entries.removeLast()
            }
        }
    }

    fun snapshot(): List<DebugHttpResponseEvidence> = synchronized(entries) {
        entries.toList()
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
        }
    }
}
