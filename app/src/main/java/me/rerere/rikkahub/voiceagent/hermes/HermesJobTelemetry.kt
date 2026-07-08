package me.rerere.rikkahub.voiceagent.hermes

import me.rerere.rikkahub.voiceagent.hermesCompletionFollowUpText
import me.rerere.rikkahub.voiceagent.telemetry.VoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import me.rerere.rikkahub.voiceagent.telemetry.voiceTextPayload
import me.rerere.rikkahub.voiceagent.voicelab.HermesJobStatus

internal val HermesJobStatus.wireName: String
    get() = when (this) {
        HermesJobStatus.Accepted -> "accepted"
        HermesJobStatus.Queued -> "queued"
        HermesJobStatus.Running -> "running"
        HermesJobStatus.Succeeded -> "succeeded"
        HermesJobStatus.Failed -> "failed"
        HermesJobStatus.Expired -> "expired"
        HermesJobStatus.Canceled -> "canceled"
    }

/**
 * All Hermes job telemetry fan-out: observability events, diagnostics, queue-event
 * artifact lines, the answer artifact, and the three host callbacks. Every method is
 * safe by construction — a throwing sink can never affect job or announcement state.
 */
class HermesJobTelemetry(
    private val observability: VoiceObservability,
    private val traceContext: VoiceTraceContext,
    private val recordDiagnostic: (String, String) -> Unit,
    private val writeQueueEvent: (HermesQueueEvent) -> Unit,
    private val writeHermesAnswer: (String) -> Unit,
    private val onJobCompleted: (HermesJobCompletion) -> Unit,
    private val onJobFailed: (HermesJobFailure) -> Unit,
    private val onPollFailed: (HermesPollFailure) -> Unit,
) {
    fun diagnostic(event: String, detail: String) {
        runCatching { recordDiagnostic(event, detail) }
    }

    fun jobSubmitted(callId: String, prompt: String) {
        recordEventSafely(
            name = "voicelab.mobile.hermes_tool.submitted",
            attributes = mapOf(
                "callId" to callId,
                "gemini.tool_call.call_id" to callId,
            ) + voiceTextPayload(key = "gemini.tool_call.prompt", text = prompt),
        )
    }

    fun jobCreated(callId: String, jobId: String, status: HermesJobStatus) {
        diagnostic("hermes_job_created", "callId=$callId, jobId=$jobId, status=$status")
        writeQueueEventSafely(
            HermesQueueEvent(type = "job_created", callId = callId, jobId = jobId, status = status.wireName)
        )
    }

    fun jobCompleted(completion: HermesJobCompletion) {
        runCatching { onJobCompleted(completion) }
        recordEventSafely(
            name = "voicelab.mobile.hermes_tool.completed",
            attributes = mapOf(
                "callId" to completion.callId,
                "jobId" to completion.jobId,
                "gemini.tool_call.call_id" to completion.callId,
                "hermes_job_id" to completion.jobId,
                "hermes_job_status" to "succeeded",
                "elapsedMs" to completion.elapsedMs,
                "serverElapsedMs" to completion.serverElapsedMs,
            ) + voiceTextPayload(key = "hermes.response.answer", text = completion.answer),
        )
        diagnostic(
            "hermes_job_completed",
            "callId=${completion.callId}, jobId=${completion.jobId}, elapsedMs=${completion.elapsedMs}" +
                "${completion.serverElapsedMs?.let { ", serverElapsedMs=$it" }.orEmpty()}" +
                ", answerChars=${completion.answer.length}",
        )
        writeQueueEventSafely(
            HermesQueueEvent(
                type = "job_completed",
                callId = completion.callId,
                jobId = completion.jobId,
                status = "succeeded",
                elapsedMs = completion.elapsedMs,
                serverElapsedMs = completion.serverElapsedMs,
                answerChars = completion.answer.length,
            )
        )
        runCatching { writeHermesAnswer(completion.answer) }
    }

    fun jobFailed(callId: String, jobId: String?, statusWire: String, message: String) {
        recordEventSafely(
            name = "voicelab.mobile.hermes_tool.failed",
            attributes = mapOf(
                "callId" to callId,
                "jobId" to jobId,
                "gemini.tool_call.call_id" to callId,
                "hermes_job_id" to jobId,
                "hermes_job_status" to statusWire,
                "status" to statusWire,
                "message" to message,
            ),
        )
        diagnostic(
            "hermes_job_failed",
            "callId=$callId${jobId?.let { ", jobId=$it" }.orEmpty()}, message=$message",
        )
        runCatching { onJobFailed(HermesJobFailure(callId = callId, jobId = jobId, message = message, elapsedMs = 0L)) }
        writeQueueEventSafely(
            HermesQueueEvent(type = "job_failed", callId = callId, jobId = jobId ?: "none", status = statusWire)
        )
    }

    fun pollFailed(failure: HermesPollFailure) {
        runCatching { onPollFailed(failure) }
    }

    fun followUpSent(callId: String, jobId: String?, prompt: String, answer: String) {
        recordEventSafely(
            name = "voicelab.mobile.gemini.followup_sent",
            attributes = mapOf(
                "callId" to callId,
                "jobId" to jobId,
                "gemini.tool_call.call_id" to callId,
                "hermes_job_id" to jobId,
                "sent" to true,
            ) + voiceTextPayload(
                key = "gemini.followup_text",
                text = hermesCompletionFollowUpText(prompt = prompt, answer = answer),
            ),
        )
    }

    private fun writeQueueEventSafely(event: HermesQueueEvent) {
        runCatching { writeQueueEvent(event) }
    }

    private fun recordEventSafely(name: String, attributes: Map<String, Any?>) {
        runCatching {
            observability.recordEvent(name = name, trace = traceContext, attributes = attributes)
        }
    }
}
