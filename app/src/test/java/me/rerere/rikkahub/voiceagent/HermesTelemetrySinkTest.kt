package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.hermes.HermesJobCompletion
import me.rerere.rikkahub.voiceagent.hermes.HermesJobFailure
import me.rerere.rikkahub.voiceagent.hermes.HermesPollFailure
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueEvent
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesTelemetrySinkTest {

    private val diagnostics = VoiceDiagnostics()
    private val requestHashes = mutableListOf<String>()
    private val responseHashes = mutableListOf<String>()
    private val toolFailures = mutableListOf<String>()
    private val queueEventLogs = mutableListOf<String>()
    private val artifacts = mutableListOf<Pair<VoiceE2EArtifact, String>>()

    private fun sink(
        expectedHash: String? = null,
        writeArtifact: (VoiceE2EArtifact, String) -> Unit = { artifact, content -> artifacts += artifact to content },
        logQueueEvent: (String) -> Unit = { queueEventLogs += it },
    ) = HermesTelemetrySink(
        diagnostics = diagnostics,
        hermesResponseExpectedHash = expectedHash,
        logHermesRequestHash = { requestHashes += it },
        logHermesResponseHash = { responseHashes += it },
        logHermesToolFailure = { toolFailures += it },
        logHermesQueueEvent = logQueueEvent,
        writeVoiceE2EArtifact = writeArtifact,
    )

    private fun diagnosticNames(): List<String> = diagnostics.events.value.map { it.name }

    @Test
    fun `writeQueueEvent logs the detail line and writes the JSON artifact`() {
        val event = HermesQueueEvent(type = "job_created", callId = "c1", jobId = "j1", status = "queued")
        sink().writeQueueEvent(event)
        assertEquals(listOf("type=job_created callId=c1 jobId=j1 status=queued sent=n/a"), queueEventLogs)
        assertEquals(listOf(VoiceE2EArtifact.HermesEvents to event.toJson()), artifacts)
    }

    @Test
    fun `writeQueueEvent survives a throwing log sink and records the failure diagnostic`() {
        sink(logQueueEvent = { error("logcat down") })
            .writeQueueEvent(HermesQueueEvent(type = "t", callId = "c", jobId = "j"))
        assertTrue("hermes_queue_event_log_failed" in diagnosticNames())
        assertEquals(1, artifacts.size) // artifact still written
    }

    @Test
    fun `recordJobCompletion records the response hash diagnostic and answer artifact`() {
        sink().recordJobCompletion(
            HermesJobCompletion(callId = "c1", jobId = "j1", answer = "answer", elapsedMs = 5L, serverElapsedMs = null)
        )
        assertEquals(1, responseHashes.size)
        assertTrue("hermes_tool_response_hash" in diagnosticNames())
        assertEquals(VoiceE2EArtifact.HermesAnswer, artifacts.single().first)
        assertEquals("answer", artifacts.single().second)
    }

    @Test
    fun `recordJobFailure sanitizes the message for the e2e log`() {
        sink().recordJobFailure(
            HermesJobFailure(callId = "c1", jobId = "j1", message = "Voice Lab request failed 500: detail", elapsedMs = 2L)
        )
        assertEquals(
            listOf("callId=c1, jobId=j1, elapsedMs=2, message=Voice Lab request failed 500"),
            toolFailures,
        )
    }

    @Test
    fun `recordPollFailure records the diagnostic`() {
        sink().recordPollFailure(HermesPollFailure(callId = "c1", jobId = "j1", attempt = 3, message = "m"))
        assertTrue("hermes_job_poll_failed" in diagnosticNames())
    }

    @Test
    fun `recordRequestHash logs and diagnoses`() {
        sink().recordRequestHash(callId = "c1", prompt = "p")
        assertEquals(1, requestHashes.size)
        assertTrue("hermes_tool_request_hash" in diagnosticNames())
    }

    @Test
    fun `writeArtifactSafely records a diagnostic with callId on failure`() {
        sink(writeArtifact = { _, _ -> error("disk full") })
            .writeArtifactSafely(VoiceE2EArtifact.HermesAnswer, "content", callId = "c9")
        val event = diagnostics.events.value.single { it.name == "voice_e2e_artifact_write_failed" }
        assertTrue(event.detail.contains("callId=c9"))
    }
}
