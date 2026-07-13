package me.rerere.rikkahub.voiceagent.debug

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class HermesTextDebugFailure(val wireName: String) {
    MissingInput("missing_input"),
    InvalidConversationId("invalid_conversation_id"),
    Timeout("timeout"),
    WrongAnswer("wrong_answer"),
    HttpEvidenceMissing("http_evidence_missing"),
    HttpStatus("http_status"),
    Runtime("runtime"),
}

internal sealed interface HermesTextDebugResult {
    fun toLogLine(): String

    data class Success(
        val httpStatus: Int,
        val requestOrigin: String,
    ) : HermesTextDebugResult {
        init {
            require(httpStatus == 200) { "A successful Hermes text check requires HTTP 200" }
            require(sanitizedHttpOrigin(requestOrigin) == requestOrigin) {
                "Hermes text request evidence must contain an origin only"
            }
        }

        override fun toLogLine(): String =
            "debug_hermes_text result=success exact=true " +
                "http_status=$httpStatus request_origin=$requestOrigin"
    }

    data class Failure(val category: HermesTextDebugFailure) : HermesTextDebugResult {
        override fun toLogLine(): String =
            "debug_hermes_text result=failure category=${category.wireName}"
    }
}

internal fun sanitizedHttpOrigin(value: String): String? {
    val url = value.toHttpUrlOrNull() ?: return null
    val host = if (':' in url.host) "[${url.host}]" else url.host
    val defaultPort = when (url.scheme) {
        "http" -> 80
        "https" -> 443
        else -> return null
    }
    val port = if (url.port == defaultPort) "" else ":${url.port}"
    return "${url.scheme}://$host$port"
}
