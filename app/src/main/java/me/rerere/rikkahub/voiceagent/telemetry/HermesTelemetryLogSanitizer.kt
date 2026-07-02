package me.rerere.rikkahub.voiceagent.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object HermesTelemetryLogSanitizer {
    fun failureMessage(message: String): String {
        Regex("Voice Lab request failed \\d+").find(message)?.let { return it.value }
        return message.substringBefore(':').take(120).ifBlank { "Hermes tool failed" }
    }

    fun queueEventDetail(content: String): String {
        val event = Json.parseToJsonElement(content).jsonObject
        fun value(name: String): String? = event[name]?.jsonPrimitive?.contentOrNull
        return "type=${value("type") ?: "unknown"} " +
            "callId=${value("callId") ?: "unknown"} " +
            "jobId=${value("jobId") ?: "none"} " +
            "status=${value("status") ?: "none"} " +
            "sent=${value("sent") ?: "n/a"}"
    }
}
