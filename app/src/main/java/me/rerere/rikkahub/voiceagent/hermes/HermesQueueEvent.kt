package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One Hermes queue telemetry event. Both producers — HermesJobManager job events and
 * the session bridge send events — emit this type; the single consumer renders it to
 * the E2E artifact JSON line ([toJson]) and the logcat detail line ([toLogDetail]).
 */
data class HermesQueueEvent(
    val type: String,
    val callId: String,
    val jobId: String,
    val status: String? = null,
    val elapsedMs: Long? = null,
    val serverElapsedMs: Long? = null,
    val answerChars: Int? = null,
    val sent: Boolean? = null,
) {
    fun toJson(): String = buildJsonObject {
        put("type", type)
        put("callId", callId)
        put("jobId", jobId)
        status?.let { put("status", it) }
        elapsedMs?.let { put("elapsedMs", it) }
        serverElapsedMs?.let { put("serverElapsedMs", it) }
        answerChars?.let { put("answerChars", it) }
        sent?.let { put("sent", it) }
    }.toString()

    fun toLogDetail(): String =
        "type=$type callId=$callId jobId=$jobId status=${status ?: "none"} sent=${sent ?: "n/a"}"
}
