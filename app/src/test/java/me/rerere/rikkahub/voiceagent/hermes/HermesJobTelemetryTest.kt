package me.rerere.rikkahub.voiceagent.hermes

import me.rerere.rikkahub.voiceagent.telemetry.VoiceAttributes
import me.rerere.rikkahub.voiceagent.telemetry.VoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceSpan
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import me.rerere.rikkahub.voiceagent.telemetry.newVoiceTraceContext
import me.rerere.rikkahub.voiceagent.voicelab.HermesJobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesJobTelemetryTest {

    private class RecordingObservability(
        private val callOrder: MutableList<String>,
    ) : VoiceObservability {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun recordEvent(name: String, trace: VoiceTraceContext, attributes: Map<String, Any?>) {
            events += name to attributes
            callOrder += "event"
        }

        override suspend fun <T> withSpan(
            name: String,
            trace: VoiceTraceContext,
            block: suspend (VoiceSpan) -> T,
        ): T = block(NoOpSpan)

        override fun captureException(
            throwable: Throwable,
            trace: VoiceTraceContext,
            attributes: VoiceAttributes,
        ) = Unit
    }

    private object NoOpSpan : VoiceSpan {
        override fun setAttribute(key: String, value: Any?) = Unit
        override fun setAttributes(attributes: VoiceAttributes) = Unit
    }

    private val callOrder = mutableListOf<String>()
    private val observability = RecordingObservability(callOrder)
    private val diagnostics = mutableListOf<Pair<String, String>>()
    private val queueEvents = mutableListOf<HermesQueueEvent>()
    private val answers = mutableListOf<String>()
    private val completions = mutableListOf<HermesJobCompletion>()
    private val failures = mutableListOf<HermesJobFailure>()
    private val pollFailures = mutableListOf<HermesPollFailure>()

    private fun telemetry(
        onJobCompleted: (HermesJobCompletion) -> Unit = {
            completions += it
            callOrder += "callback"
        },
        onJobFailed: (HermesJobFailure) -> Unit = {
            failures += it
            callOrder += "callback"
        },
    ) = HermesJobTelemetry(
        observability = observability,
        traceContext = newVoiceTraceContext(),
        recordDiagnostic = { name, detail ->
            diagnostics += name to detail
            callOrder += "diagnostic"
        },
        writeQueueEvent = {
            queueEvents += it
            callOrder += "queueEvent"
        },
        writeHermesAnswer = {
            answers += it
            callOrder += "answer"
        },
        onJobCompleted = onJobCompleted,
        onJobFailed = onJobFailed,
        onPollFailed = { pollFailures += it },
    )

    @Test
    fun `jobCompleted emits callback, event, diagnostic, queue event, and answer artifact in order`() {
        telemetry().jobCompleted(
            HermesJobCompletion(callId = "c1", jobId = "j1", answer = "hi", elapsedMs = 10L, serverElapsedMs = 7L)
        )
        assertEquals(listOf("c1"), completions.map { it.callId })
        assertEquals("voicelab.mobile.hermes_tool.completed", observability.events.single().first)
        assertEquals("succeeded", observability.events.single().second["hermes_job_status"])
        assertEquals(
            "hermes_job_completed" to "callId=c1, jobId=j1, elapsedMs=10, serverElapsedMs=7, answerChars=2",
            diagnostics.single(),
        )
        assertEquals(
            HermesQueueEvent(
                type = "job_completed", callId = "c1", jobId = "j1", status = "succeeded",
                elapsedMs = 10L, serverElapsedMs = 7L, answerChars = 2,
            ),
            queueEvents.single(),
        )
        assertEquals(listOf("hi"), answers)
        assertEquals(listOf("callback", "event", "diagnostic", "queueEvent", "answer"), callOrder)
    }

    @Test
    fun `jobCompleted omits serverElapsedMs from the diagnostic when null`() {
        telemetry().jobCompleted(
            HermesJobCompletion(callId = "c1", jobId = "j1", answer = "hi", elapsedMs = 10L, serverElapsedMs = null)
        )
        assertEquals("callId=c1, jobId=j1, elapsedMs=10, answerChars=2", diagnostics.single().second)
    }

    @Test
    fun `jobFailed emits event, diagnostic, callback, queue event with none jobId`() {
        telemetry().jobFailed(callId = "c1", jobId = null, statusWire = "expired", message = "boom")
        assertEquals("voicelab.mobile.hermes_tool.failed", observability.events.single().first)
        assertEquals("expired", observability.events.single().second["status"])
        assertEquals("hermes_job_failed" to "callId=c1, message=boom", diagnostics.single())
        assertEquals(listOf("c1"), failures.map { it.callId })
        assertEquals("none", queueEvents.single().jobId)
        assertEquals(listOf("event", "diagnostic", "callback", "queueEvent"), callOrder)
    }

    @Test
    fun `jobCreated logs enum status in diagnostic and wire status in queue event`() {
        telemetry().jobCreated(callId = "c1", jobId = "j1", status = HermesJobStatus.Queued)
        assertEquals("hermes_job_created" to "callId=c1, jobId=j1, status=Queued", diagnostics.single())
        assertEquals("queued", queueEvents.single().status)
        assertEquals(listOf("diagnostic", "queueEvent"), callOrder)
    }

    @Test
    fun `jobSubmitted emits the submitted event with prompt payload`() {
        telemetry().jobSubmitted(callId = "c1", prompt = "why")
        val (name, attributes) = observability.events.single()
        assertEquals("voicelab.mobile.hermes_tool.submitted", name)
        assertEquals("c1", attributes["gemini.tool_call.call_id"])
    }

    @Test
    fun `followUpSent emits the followup event`() {
        telemetry().followUpSent(callId = "c1", jobId = "j1", prompt = "p", answer = "a")
        val (name, attributes) = observability.events.single()
        assertEquals("voicelab.mobile.gemini.followup_sent", name)
        assertEquals(true, attributes["sent"])
    }

    @Test
    fun `throwing sinks never propagate`() {
        val telemetry = HermesJobTelemetry(
            observability = object : VoiceObservability {
                override fun recordEvent(name: String, trace: VoiceTraceContext, attributes: Map<String, Any?>) =
                    error("observability down")

                override suspend fun <T> withSpan(
                    name: String,
                    trace: VoiceTraceContext,
                    block: suspend (VoiceSpan) -> T,
                ): T = block(NoOpSpan)

                override fun captureException(
                    throwable: Throwable,
                    trace: VoiceTraceContext,
                    attributes: VoiceAttributes,
                ) = Unit
            },
            traceContext = newVoiceTraceContext(),
            recordDiagnostic = { _, _ -> error("diagnostics down") },
            writeQueueEvent = { error("artifact down") },
            writeHermesAnswer = { error("answer down") },
            onJobCompleted = { error("callback down") },
            onJobFailed = { error("callback down") },
            onPollFailed = { error("callback down") },
        )
        telemetry.jobCompleted(HermesJobCompletion("c", "j", "a", 1L, null))
        telemetry.jobFailed(callId = "c", jobId = "j", statusWire = "failed", message = "m")
        telemetry.jobSubmitted(callId = "c", prompt = "p")
        telemetry.jobCreated(callId = "c", jobId = "j", status = HermesJobStatus.Running)
        telemetry.pollFailed(HermesPollFailure("c", "j", 1, "m"))
        telemetry.followUpSent(callId = "c", jobId = "j", prompt = "p", answer = "a")
        telemetry.diagnostic("e", "d")
        assertTrue(true) // reaching here without an exception is the assertion
    }
}
