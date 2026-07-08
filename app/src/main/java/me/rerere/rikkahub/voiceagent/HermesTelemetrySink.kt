package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.hermes.HermesJobCompletion
import me.rerere.rikkahub.voiceagent.hermes.HermesJobFailure
import me.rerere.rikkahub.voiceagent.hermes.HermesPollFailure
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueEvent
import me.rerere.rikkahub.voiceagent.telemetry.HermesTelemetryLogSanitizer
import me.rerere.rikkahub.voiceagent.telemetry.HermesToolResponseHash
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics

/**
 * E2E artifact and hash/log sinks for Hermes job telemetry. Pure sinks: no
 * coordinator state, every entry point is safe against throwing log/artifact
 * destinations. The single consumer for [HermesQueueEvent] from both producers
 * (HermesJobManager job events and session-bridge send events).
 */
class HermesTelemetrySink(
    private val diagnostics: VoiceDiagnostics,
    private val hermesResponseExpectedHash: String?,
    private val logHermesRequestHash: (String) -> Unit,
    private val logHermesResponseHash: (String) -> Unit,
    private val logHermesToolFailure: (String) -> Unit,
    private val logHermesQueueEvent: (String) -> Unit,
    private val artifactSink: VoiceE2EArtifactSink,
) {
    fun recordJobCompletion(completion: HermesJobCompletion) {
        recordResponseHash(
            callId = completion.callId,
            answer = completion.answer,
            expectedHash = hermesResponseExpectedHash,
            elapsedMs = completion.elapsedMs,
            serverElapsedMs = completion.serverElapsedMs,
        )
    }

    fun recordJobFailure(failure: HermesJobFailure) {
        val jobDetail = failure.jobId?.let { ", jobId=$it" }.orEmpty()
        val e2eDetail = "callId=${failure.callId}$jobDetail, elapsedMs=${failure.elapsedMs}, " +
            "message=${HermesTelemetryLogSanitizer.failureMessage(failure.message)}"
        runCatching {
            logHermesToolFailure(e2eDetail)
        }
    }

    fun recordPollFailure(failure: HermesPollFailure) {
        diagnostics.record(
            "hermes_job_poll_failed",
            "callId=${failure.callId}, jobId=${failure.jobId}, attempt=${failure.attempt}, message=${failure.message}",
        )
    }

    fun recordRequestHash(callId: String, prompt: String) {
        val detail = HermesToolResponseHash.requestDiagnosticDetail(callId = callId, prompt = prompt)
        diagnostics.record("hermes_tool_request_hash", detail)
        runCatching {
            logHermesRequestHash(detail)
        }.onFailure { error ->
            val message = error.message ?: error.javaClass.simpleName
            diagnostics.record("hermes_tool_request_hash_log_failed", "callId=$callId, message=$message")
        }
    }

    fun writeQueueEvent(event: HermesQueueEvent) {
        logQueueEventSafely(event.toLogDetail())
        artifactSink.writeArtifactSafely(
            artifact = VoiceE2EArtifact.HermesEvents,
            content = event.toJson(),
            callId = event.callId,
        )
    }

    private fun recordResponseHash(
        callId: String,
        answer: String,
        expectedHash: String?,
        elapsedMs: Long,
        serverElapsedMs: Long?,
    ) {
        val detail = HermesToolResponseHash.diagnosticDetail(
            callId = callId,
            answer = answer,
            expectedSha256 = expectedHash?.takeIf { it.isNotBlank() },
            elapsedMs = elapsedMs,
            serverElapsedMs = serverElapsedMs,
        )
        diagnostics.record("hermes_tool_response_hash", detail)
        runCatching {
            logHermesResponseHash(detail)
        }.onFailure { error ->
            val message = error.message ?: error.javaClass.simpleName
            diagnostics.record("hermes_tool_response_hash_log_failed", "callId=$callId, message=$message")
        }
        artifactSink.writeArtifactSafely(artifact = VoiceE2EArtifact.HermesAnswer, content = answer, callId = callId)
    }

    private fun logQueueEventSafely(detail: String) {
        runCatching {
            logHermesQueueEvent(detail)
        }.onFailure { error ->
            diagnostics.record(
                "hermes_queue_event_log_failed",
                error.message ?: error.javaClass.simpleName,
            )
        }
    }
}
