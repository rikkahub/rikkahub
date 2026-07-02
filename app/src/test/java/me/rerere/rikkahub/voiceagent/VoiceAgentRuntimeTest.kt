package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.gemini.GeminiContentTurn
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveCodec
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.telemetry.HermesToolResponseHash
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesJobPollResponse
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesResponse
import me.rerere.rikkahub.voiceagent.voicelab.VoiceFailure
import me.rerere.rikkahub.voiceagent.voicelab.VoiceFailureKind
import me.rerere.rikkahub.voiceagent.voicelab.VoiceFailureSource
import me.rerere.rikkahub.voiceagent.voicelab.VoiceLabHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceAgentRuntimeTest {
    @Test
    fun `coordinator publishes trace id in UI state`() = runTest {
        val trace = VoiceTraceContext(traceId = "trace-123", voiceSessionId = "session-456")
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = FakeVoiceAudioEngine(),
            traceContext = trace,
            scope = this,
        )

        assertEquals("trace-123", coordinator.state.value.traceId)
    }

    @Test
    fun `batched Hermes tool call continues after interruption and sends response to Gemini`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCalls(
                listOf(GeminiLiveEvent.ToolCall(callId = "call-1", name = "ask_hermes", prompt = "Look this up"))
            )
        )

        assertEquals("call-1" to "Look this up", toolApi.awaitRequest("call-1"))
        withTimeout(500) {
            while (coordinator.state.value.tool != VoiceToolStatus.QueuedHermes("call-1", "job-1")) {
                delay(10)
            }
        }
        assertEquals(VoiceToolStatus.QueuedHermes("call-1", "job-1"), coordinator.state.value.tool)

        coordinator.onGeminiEvent(GeminiLiveEvent.Interrupted())
        assertEquals(VoiceAudioStatus.PlaybackSuppressed, coordinator.state.value.audio)
        audio.awaitSuppressPlaybackCalls(1)
        assertEquals(VoiceToolStatus.QueuedHermes("call-1", "job-1"), coordinator.state.value.tool)
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("late-audio"))
        assertEquals(emptyList<String>(), audio.playedPcm16)

        withContext(Dispatchers.Default) {
            Thread.sleep(25)
        }
        toolApi.complete(response(callId = "call-1", answer = "Hermes answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-1")), gemini.toolResponses)
        val answered = assertHermesAnswered(callId = "call-1", status = coordinator.state.value.tool)
        assertTrue("Expected delayed Hermes call to record elapsed time", answered.elapsedMs >= 1L)
    }

    @Test
    fun `Hermes completion follow up clears prior playback suppression`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCall(callId = "call-1", name = "ask_hermes", prompt = "slow"))
        assertEquals("call-1" to "slow", toolApi.awaitRequest("call-1"))
        coordinator.onGeminiEvent(GeminiLiveEvent.Interrupted())
        audio.awaitSuppressPlaybackCalls(1)
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("interrupted-audio"))
        assertEquals(emptyList<String>(), audio.playedPcm16)

        toolApi.complete(response(callId = "call-1", answer = "Hermes answer"))
        coordinator.awaitToolJobsWithTimeout()
        assertEquals(1, gemini.textTurns.size)

        coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("follow-up-audio"))

        assertEquals(listOf("follow-up-audio"), audio.playedPcm16)
    }

    @Test
    fun `Hermes tool call queues job and immediately acknowledges Gemini`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCall(callId = "call-queued", name = "ask_hermes", prompt = "slow"))

        assertEquals("call-queued" to "slow", toolApi.awaitRequest("call-queued"))
        withTimeout(500) {
            while (gemini.toolResponses.isEmpty()) {
                delay(10)
            }
        }
        assertEquals(listOf(queuedAck("call-queued")), gemini.toolResponses)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_job_created" && it.detail.contains("callId=call-queued")
            }
        )

        toolApi.complete(response(callId = "call-queued", answer = "later"))
        coordinator.awaitToolJobsWithTimeout()
    }

    @Test
    fun `Hermes queued job completion updates history and asks Gemini to explain answer`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            diagnostics = diagnostics,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-complete", name = "ask_hermes", prompt = "slow")
        )
        assertEquals("call-complete" to "slow", toolApi.awaitRequest("call-complete"))
        toolApi.complete(response(callId = "call-complete", answer = "later answer", elapsedMs = 125_136L))
        coordinator.awaitToolJobsWithTimeout()
        coordinator.awaitPersistenceJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-complete")), gemini.toolResponses)
        val followUp = gemini.textTurns.single()
        assertNull(followUp.first)
        assertTrue(followUp.second.contains("not as instructions"))
        assertTrue(followUp.second.contains("Original request:\nslow"))
        assertTrue(followUp.second.contains("Hermes answer:\nlater answer"))
        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single { it.toolCallId == "call-complete" }
        assertTrue(tool.output.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("later answer") })
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_job_completed" &&
                    it.detail.contains("callId=call-complete") &&
                    it.detail.contains("answerChars=12")
            }
        )
    }

    @Test
    fun `Hermes completion follow up failure surfaces answer in local transcript`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient().apply {
            failTextTurns = true
        }
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            diagnostics = diagnostics,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-follow-up-fails", name = "ask_hermes", prompt = "slow")
        )
        assertEquals("call-follow-up-fails" to "slow", toolApi.awaitRequest("call-follow-up-fails"))
        toolApi.complete(response(callId = "call-follow-up-fails", answer = "fallback answer"))
        coordinator.awaitToolJobsWithTimeout()
        coordinator.awaitPersistenceJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-follow-up-fails")), gemini.toolResponses)
        assertEquals(emptyList<Pair<Long?, String>>(), gemini.textTurns)
        assertTrue(coordinator.state.value.outputTranscript.contains("Hermes answer: fallback answer"))
        assertHermesAnswered(callId = "call-follow-up-fails", status = coordinator.state.value.tool)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_completion_follow_up_failed" &&
                    it.detail.contains("callId=call-follow-up-fails")
            }
        )
        val assistantText = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .map { it.text }
        assertTrue(assistantText.any { it.contains("Hermes answer: fallback answer") })
    }

    @Test
    fun `Hermes job submission failure persists failure without acknowledgement or job id`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val failures = mutableListOf<String>()
        toolApi.failSubmit(
            callId = "call-submit-fails",
            error = IllegalStateException("Voice Lab request failed 429: private prompt detail"),
        )
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            logHermesToolFailure = failures::add,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-submit-fails", name = "ask_hermes", prompt = "slow")
        )
        coordinator.awaitToolJobsWithTimeout()
        coordinator.awaitPersistenceJobsWithTimeout()

        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertEquals(emptyList<Pair<Long?, String>>(), gemini.textTurns)
        assertEquals(emptyList<Pair<String, String>>(), toolApi.requests)
        assertEquals(
            VoiceToolStatus.HermesFailed(
                callId = "call-submit-fails",
                message = "Voice Lab request failed 429: private prompt detail",
            ),
            coordinator.state.value.tool,
        )
        assertEquals(1, failures.size)
        assertTrue(failures.single().contains("callId=call-submit-fails"))
        assertTrue(failures.single().contains("message=Voice Lab request failed 429"))
        assertFalse(failures.single().contains("private prompt detail"))
        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single { it.toolCallId == "call-submit-fails" }
        assertEquals("failed", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertNull(tool.metadata?.get("voice_tool_job_id"))
    }

    @Test
    fun `Hermes queued job polls queued running and succeeded statuses`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            hermesJobPollIntervalMs = 1,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-poll", name = "ask_hermes", prompt = "slow")
        )
        assertEquals("call-poll" to "slow", toolApi.awaitRequest("call-poll"))

        toolApi.scriptPoll("call-poll", jobPoll(callId = "call-poll", jobId = "job-1", status = "queued"))
        withTimeout(500) {
            while (coordinator.state.value.tool != VoiceToolStatus.QueuedHermes("call-poll", "job-1")) {
                delay(10)
            }
        }

        toolApi.scriptPoll("call-poll", jobPoll(callId = "call-poll", jobId = "job-1", status = "running"))
        withTimeout(500) {
            while (coordinator.state.value.tool !is VoiceToolStatus.CallingHermes) {
                delay(10)
            }
        }

        toolApi.complete(response(callId = "call-poll", answer = "done"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-poll")), gemini.toolResponses)
        assertHermesAnswered(callId = "call-poll", status = coordinator.state.value.tool)
    }

    @Test
    fun `Hermes returned failed status persists failure without second Gemini response`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-job-failed", name = "ask_hermes", prompt = "slow")
        )
        assertEquals("call-job-failed" to "slow", toolApi.awaitRequest("call-job-failed"))
        toolApi.failJob(callId = "call-job-failed", message = "Hermes request failed")
        coordinator.awaitToolJobsWithTimeout()
        coordinator.awaitPersistenceJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-job-failed")), gemini.toolResponses)
        assertEquals(
            VoiceToolStatus.HermesFailed(callId = "call-job-failed", message = "Hermes request failed"),
            coordinator.state.value.tool,
        )
        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single { it.toolCallId == "call-job-failed" }
        assertEquals("failed", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("job-1", tool.metadata?.get("voice_tool_job_id")?.jsonPrimitive?.content)
        assertTrue(tool.output.text().contains("Hermes request failed"))
    }

    @Test
    fun `Hermes returned expired status without error uses stable local message`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-expired", name = "ask_hermes", prompt = "slow")
        )
        assertEquals("call-expired" to "slow", toolApi.awaitRequest("call-expired"))
        toolApi.expireJob(callId = "call-expired")
        coordinator.awaitToolJobsWithTimeout()
        coordinator.awaitPersistenceJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-expired")), gemini.toolResponses)
        assertEquals(
            VoiceToolStatus.HermesFailed(
                callId = "call-expired",
                message = "Hermes job was no longer available.",
            ),
            coordinator.state.value.tool,
        )
        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single { it.toolCallId == "call-expired" }
        assertTrue(tool.output.text().contains("Hermes job was no longer available."))
        assertEquals("job-1", tool.metadata?.get("voice_tool_job_id")?.jsonPrimitive?.content)
    }

    @Test
    fun `Hermes poll transport failures are retried before completion`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            hermesJobPollIntervalMs = 1,
            hermesJobPollRetryDelayMs = 1,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-retry", name = "ask_hermes", prompt = "slow")
        )
        assertEquals("call-retry" to "slow", toolApi.awaitRequest("call-retry"))
        repeat(5) {
            toolApi.scriptPollFailure("call-retry", IllegalStateException("temporary network failure"))
        }
        toolApi.complete(response(callId = "call-retry", answer = "done"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-retry")), gemini.toolResponses)
        assertHermesAnswered(callId = "call-retry", status = coordinator.state.value.tool)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_job_poll_failed" && it.detail.contains("attempt=1")
            }
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_job_poll_failed" && it.detail.contains("attempt=5")
            }
        )
    }

    @Test
    fun `Hermes local poll timeout cancels remote job`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            hermesJobPollIntervalMs = 1,
            hermesJobMaxElapsedMs = 0,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-local-timeout", name = "ask_hermes", prompt = "slow")
        )
        assertEquals("call-local-timeout" to "slow", toolApi.awaitRequest("call-local-timeout"))
        toolApi.scriptPoll(
            "call-local-timeout",
            jobPoll(callId = "call-local-timeout", jobId = "job-1", status = "queued"),
        )
        coordinator.awaitToolJobsWithTimeout()

        toolApi.awaitRemoteCancelled("call-local-timeout")
        assertEquals(listOf(queuedAck("call-local-timeout")), gemini.toolResponses)
        assertEquals(emptyList<Pair<Long?, String>>(), gemini.textTurns)
        assertEquals(
            VoiceToolStatus.HermesFailed(
                callId = "call-local-timeout",
                message = "Hermes job polling timed out.",
            ),
            coordinator.state.value.tool,
        )
    }

    @Test
    fun `Hermes malformed succeeded poll persists failure without leaking answer`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            hermesJobPollIntervalMs = 1,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-malformed", name = "ask_hermes", prompt = "slow")
        )
        assertEquals("call-malformed" to "slow", toolApi.awaitRequest("call-malformed"))
        toolApi.scriptPoll(
            "call-malformed",
            MobileHermesJobPollResponse(
                jobId = "job-1",
                callId = "call-malformed",
                status = "succeeded",
                answer = null,
                createdAt = "2026-06-11T00:00:00.000Z",
                completedAt = "2026-06-11T00:00:01.000Z",
            ),
        )
        coordinator.awaitToolJobsWithTimeout()
        coordinator.awaitPersistenceJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-malformed")), gemini.toolResponses)
        assertEquals(
            VoiceToolStatus.HermesFailed(
                callId = "call-malformed",
                message = "Hermes job succeeded without an answer",
            ),
            coordinator.state.value.tool,
        )
        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single { it.toolCallId == "call-malformed" }
        assertEquals("job-1", tool.metadata?.get("voice_tool_job_id")?.jsonPrimitive?.content)
        assertTrue(tool.output.text().contains("Hermes job succeeded without an answer"))
    }

    @Test
    fun `Hermes unknown poll status persists failure with job id`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            hermesJobPollIntervalMs = 1,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-unknown", name = "ask_hermes", prompt = "slow")
        )
        assertEquals("call-unknown" to "slow", toolApi.awaitRequest("call-unknown"))
        toolApi.scriptPoll("call-unknown", jobPoll(callId = "call-unknown", jobId = "job-1", status = "mystery"))
        coordinator.awaitToolJobsWithTimeout()
        coordinator.awaitPersistenceJobsWithTimeout()

        assertEquals(
            VoiceToolStatus.HermesFailed(
                callId = "call-unknown",
                message = "Unknown Hermes job status: mystery",
            ),
            coordinator.state.value.tool,
        )
        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single { it.toolCallId == "call-unknown" }
        assertEquals("job-1", tool.metadata?.get("voice_tool_job_id")?.jsonPrimitive?.content)
    }

    @Test
    fun `Hermes poll timeout persists a failed tool record`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            hermesJobPollIntervalMs = 1,
            hermesJobMaxElapsedMs = 10,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-timeout", name = "ask_hermes", prompt = "slow")
        )
        assertEquals("call-timeout" to "slow", toolApi.awaitRequest("call-timeout"))
        toolApi.scriptQueuedPolls(callId = "call-timeout", count = 20)
        coordinator.awaitToolJobsWithTimeout()
        coordinator.awaitPersistenceJobsWithTimeout()

        assertEquals(
            VoiceToolStatus.HermesFailed(
                callId = "call-timeout",
                message = "Hermes job polling timed out.",
            ),
            coordinator.state.value.tool,
        )
        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single { it.toolCallId == "call-timeout" }
        assertTrue(tool.output.text().contains("Hermes job polling timed out."))
    }

    @Test
    fun `unsupported batched tool is ignored without calling Hermes or sending response`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCalls(
                listOf(GeminiLiveEvent.ToolCall(callId = "call-2", name = "unsupported_tool", prompt = "ignored"))
            )
        )
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(emptyList<Pair<String, String>>(), toolApi.requests)
        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)
        assertTrue(diagnostics.events.value.any { it.name == "unsupported_tool_call" && it.detail.contains("call-2") })
    }

    @Test
    fun `pending Hermes tool status shows queued job while request is waiting`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-timer", name = "ask_hermes", prompt = "wait")
        )
        assertEquals("call-timer" to "wait", toolApi.awaitRequest("call-timer"))

        withTimeout(500) {
            while (coordinator.state.value.tool != VoiceToolStatus.QueuedHermes("call-timer", "job-1")) {
                delay(10)
            }
        }
        assertEquals(VoiceToolStatus.QueuedHermes("call-timer", "job-1"), coordinator.state.value.tool)

        toolApi.complete(response(callId = "call-timer", answer = "done"))
        coordinator.awaitToolJobsWithTimeout()
    }

    @Test
    fun `diagnostic events are exposed in ui state for alpha visibility`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            scope = this,
        )

        diagnostics.record("manual_probe", "visible detail")

        withTimeout(500) {
            while (coordinator.state.value.diagnostics.none { it.name == "manual_probe" }) {
                delay(10)
            }
        }
        assertTrue(
            coordinator.state.value.diagnostics.any {
                it.name == "manual_probe" && it.detail == "visible detail"
            }
        )
    }

    @Test
    fun `unsupported only tool call from codec records diagnostic without Hermes response`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            scope = this,
        )
        val rawUnsupportedToolCall = """
            {
              "toolCall":{
                "functionCalls":[
                  {
                    "id":"call-ignored",
                    "name":"unsupported_tool",
                    "args":{"prompt":"Do not use this prompt"}
                  }
                ]
              }
            }
        """.trimIndent()
        val event = GeminiLiveCodec().parseServerMessage(rawUnsupportedToolCall)
        assertEquals(
            GeminiLiveEvent.ToolCalls(
                calls = emptyList(),
                unsupportedCalls = listOf(
                    GeminiLiveEvent.UnsupportedToolCall(
                        callId = "call-ignored",
                        name = "unsupported_tool",
                    )
                ),
            ),
            event,
        )

        coordinator.onGeminiEvent(event)
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(emptyList<Pair<String, String>>(), toolApi.requests)
        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "unsupported_tool_call" &&
                    it.detail.contains("call-ignored") &&
                    it.detail.contains("unsupported_tool")
            }
        )
    }

    @Test
    fun `mixed tool call from codec sends Hermes call and records unsupported diagnostic`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            scope = this,
        )
        val rawMixedToolCall = """
            {
              "toolCall":{
                "functionCalls":[
                  {
                    "id":"call-unsupported",
                    "name":"unsupported_tool",
                    "args":{"prompt":"Do not use this prompt"}
                  },
                  {
                    "id":"call-supported",
                    "name":"ask_hermes",
                    "args":{"prompt":"Use this prompt"}
                  }
                ]
              }
            }
        """.trimIndent()
        val event = GeminiLiveCodec().parseServerMessage(rawMixedToolCall)
        assertEquals(
            GeminiLiveEvent.ToolCalls(
                calls = listOf(
                    GeminiLiveEvent.ToolCall(
                        callId = "call-supported",
                        name = "ask_hermes",
                        prompt = "Use this prompt",
                    )
                ),
                unsupportedCalls = listOf(
                    GeminiLiveEvent.UnsupportedToolCall(
                        callId = "call-unsupported",
                        name = "unsupported_tool",
                    )
                ),
            ),
            event,
        )

        coordinator.onGeminiEvent(event)
        assertEquals("call-supported" to "Use this prompt", toolApi.awaitRequest("call-supported"))
        toolApi.complete(response(callId = "call-supported", answer = "Supported answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-supported")), gemini.toolResponses)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "unsupported_tool_call" &&
                    it.detail.contains("call-unsupported") &&
                    it.detail.contains("unsupported_tool")
            }
        )
    }

    @Test
    fun `close keeps in-flight Hermes call running without remote cancellation`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCall(callId = "call-close", name = "ask_hermes", prompt = "wait"))
        assertEquals("call-close" to "wait", toolApi.awaitRequest("call-close"))

        coordinator.onGeminiEvent(GeminiLiveEvent.Interrupted())
        withTimeout(500) {
            while (coordinator.state.value.tool != VoiceToolStatus.QueuedHermes("call-close", "job-1")) {
                delay(10)
            }
        }
        assertEquals(VoiceToolStatus.QueuedHermes("call-close", "job-1"), coordinator.state.value.tool)
        assertEquals(false, toolApi.wasCancelled("call-close"))

        coordinator.close()
        toolApi.complete(response(callId = "call-close", answer = "late answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertFalse(toolApi.wasCancelled("call-close"))
        assertFalse(toolApi.wasRemoteCancelled("call-close"))
        assertEquals(listOf(queuedAck("call-close")), gemini.toolResponses)
        assertEquals(1, audio.releaseCalls)
    }

    @Test
    fun `tool call cancellation cancels only matching Hermes call and suppresses its response`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCalls(
                listOf(
                    GeminiLiveEvent.ToolCall(callId = "call-a", name = "ask_hermes", prompt = "First"),
                    GeminiLiveEvent.ToolCall(callId = "call-b", name = "ask_hermes", prompt = "Second"),
                )
            )
        )
        assertEquals("call-a" to "First", toolApi.awaitRequest("call-a"))
        assertEquals("call-b" to "Second", toolApi.awaitRequest("call-b"))
        withTimeout(500) {
            while (
                coordinator.state.value.toolCalls["call-a"] !is VoiceToolStatus.QueuedHermes ||
                coordinator.state.value.toolCalls["call-b"] !is VoiceToolStatus.QueuedHermes
            ) {
                delay(10)
            }
        }
        val queuedCallB = coordinator.state.value.toolCalls.getValue("call-b")

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCallCancellation(listOf("call-a")))
        toolApi.awaitRemoteCancelled("call-a")

        assertEquals(queuedCallB, coordinator.state.value.tool)
        assertEquals(
            mapOf("call-b" to queuedCallB),
            coordinator.state.value.toolCalls,
        )
        assertEquals(false, toolApi.wasCancelled("call-b"))

        toolApi.complete(response(callId = "call-b", answer = "Second answer"))
        coordinator.awaitToolJobsWithTimeout()
        toolApi.complete(response(callId = "call-a", answer = "First late answer"))

        assertEquals(setOf(queuedAck("call-a"), queuedAck("call-b")), gemini.toolResponses.toSet())
        assertEquals(false, toolApi.wasCancelled("call-b"))
        assertHermesAnswered(callId = "call-b", status = coordinator.state.value.tool)
        assertEquals(setOf("call-b"), coordinator.state.value.toolCalls.keys)
        assertHermesAnswered(callId = "call-b", status = coordinator.state.value.toolCalls.getValue("call-b"))
        assertTrue(
            diagnostics.events.value.any {
                it.name == "tool_call_cancellation" && it.detail.contains("call-a")
            }
        )
    }

    @Test
    fun `tool call cancellation received before tool call prevents Hermes request`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCallCancellation(listOf("call-before-start")))
        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(
                callId = "call-before-start",
                name = "ask_hermes",
                prompt = "do not start",
            )
        )
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(emptyList<Pair<String, String>>(), toolApi.requests)
        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)
        assertEquals(emptyMap<String, VoiceToolStatus>(), coordinator.state.value.toolCalls)
        toolApi.complete(response(callId = "call-close-idempotent", answer = "late answer"))
        coordinator.awaitToolJobsWithTimeout()
    }

    @Test
    fun `close while Hermes submit is in flight lets background job finish`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val blockedSubmit = toolApi.blockSubmit("call-close-inflight")
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-close-inflight", name = "ask_hermes", prompt = "slow")
        )
        assertEquals("call-close-inflight" to "slow", toolApi.awaitRequest("call-close-inflight"))
        assertTrue(blockedSubmit.started.await(500, TimeUnit.MILLISECONDS))

        coordinator.close()
        blockedSubmit.release.countDown()

        toolApi.complete(response(callId = "call-close-inflight", answer = "late answer"))
        coordinator.awaitToolJobsWithTimeout()
        assertFalse(toolApi.wasCancelled("call-close-inflight"))
        assertFalse(toolApi.wasRemoteCancelled("call-close-inflight"))
        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertHermesAnswered(callId = "call-close-inflight", status = coordinator.state.value.tool)
    }

    @Test
    fun `duplicate tool call id after queued acknowledgement keeps original job`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-replay", name = "ask_hermes", prompt = "old")
        )
        assertEquals("call-replay" to "old", toolApi.awaitRequest("call-replay"))

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-replay", name = "ask_hermes", prompt = "new")
        )
        delay(50)
        assertFalse(("call-replay" to "new") in toolApi.requests)

        toolApi.complete(response(callId = "call-replay", answer = "old answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-replay")), gemini.toolResponses)
        assertHermesAnswered(callId = "call-replay", status = coordinator.state.value.tool)
    }

    @Test
    fun `duplicate tool call id while submit is in flight keeps original job`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val blockedSubmit = toolApi.blockSubmit("call-submit-inflight")
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-submit-inflight", name = "ask_hermes", prompt = "old")
        )
        assertEquals("call-submit-inflight" to "old", toolApi.awaitRequest("call-submit-inflight"))
        assertTrue(blockedSubmit.started.await(500, TimeUnit.MILLISECONDS))

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-submit-inflight", name = "ask_hermes", prompt = "new")
        )
        delay(50)
        assertFalse(("call-submit-inflight" to "new") in toolApi.requests)
        assertTrue(diagnostics.events.value.any { it.name == "duplicate_tool_call_active" })

        blockedSubmit.release.countDown()
        toolApi.complete(response(callId = "call-submit-inflight", answer = "old answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-submit-inflight")), gemini.toolResponses)
        assertHermesAnswered(callId = "call-submit-inflight", status = coordinator.state.value.tool)
    }

    @Test
    fun `duplicate tool call id is ignored when old send is already in progress`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val oldSend = gemini.blockNextToolResponse("call-replay-send")
        val toolApi = FakeVoiceToolApi()
        val artifacts = mutableMapOf<VoiceE2EArtifact, String>()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            writeVoiceE2EArtifact = { name, content -> artifacts[name] = content },
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-replay-send", name = "ask_hermes", prompt = "old")
        )
        assertEquals("call-replay-send" to "old", toolApi.awaitRequest("call-replay-send"))
        toolApi.complete(response(callId = "call-replay-send", answer = "old answer"))
        assertTrue(oldSend.started.await(500, TimeUnit.MILLISECONDS))

        val replayJob = launch(Dispatchers.Default) {
            coordinator.onGeminiEvent(
                GeminiLiveEvent.ToolCall(callId = "call-replay-send", name = "ask_hermes", prompt = "new")
            )
        }
        withTimeout(500) {
            replayJob.join()
        }
        assertFalse(("call-replay-send" to "new") in toolApi.requests)

        oldSend.release.countDown()
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(
            listOf(queuedAck("call-replay-send")),
            gemini.toolResponses,
        )
        assertEquals("old", artifacts[VoiceE2EArtifact.HermesCall])
        assertHermesAnswered(callId = "call-replay-send", status = coordinator.state.value.tool)
    }

    @Test
    fun `batched Hermes tool calls send both responses and record per-call success diagnostics`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCalls(
                listOf(
                    GeminiLiveEvent.ToolCall(callId = "call-a", name = "ask_hermes", prompt = "First"),
                    GeminiLiveEvent.ToolCall(callId = "call-b", name = "ask_hermes", prompt = "Second"),
                )
            )
        )
        assertEquals("call-a" to "First", toolApi.awaitRequest("call-a"))
        assertEquals("call-b" to "Second", toolApi.awaitRequest("call-b"))
        withTimeout(500) {
            while (
                coordinator.state.value.toolCalls.keys != setOf("call-a", "call-b") ||
                coordinator.state.value.toolCalls.values.any { it !is VoiceToolStatus.QueuedHermes }
            ) {
                kotlinx.coroutines.delay(10)
            }
        }
        val callBQueued = coordinator.state.value.toolCalls.getValue("call-b")
        assertHermesQueued(callId = "call-a", status = coordinator.state.value.toolCalls.getValue("call-a"))
        assertHermesQueued(callId = "call-b", status = callBQueued)

        toolApi.complete(response(callId = "call-a", answer = "First answer"))
        withTimeout(500) {
            while (coordinator.state.value.toolCalls["call-a"] !is VoiceToolStatus.HermesAnswered) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(callBQueued, coordinator.state.value.tool)
        assertEquals(setOf("call-a", "call-b"), coordinator.state.value.toolCalls.keys)
        assertHermesAnswered(callId = "call-a", status = coordinator.state.value.toolCalls.getValue("call-a"))
        assertEquals(callBQueued, coordinator.state.value.toolCalls.getValue("call-b"))

        toolApi.complete(response(callId = "call-b", answer = "Second answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(
            setOf(queuedAck("call-a"), queuedAck("call-b")),
            gemini.toolResponses.toSet(),
        )
        assertEquals(setOf("call-a", "call-b"), coordinator.state.value.toolCalls.keys)
        assertHermesAnswered(callId = "call-a", status = coordinator.state.value.toolCalls.getValue("call-a"))
        assertHermesAnswered(callId = "call-b", status = coordinator.state.value.toolCalls.getValue("call-b"))
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_job_completed" &&
                    it.detail.contains("callId=call-a") &&
                    it.detail.contains("elapsedMs=")
            }
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_job_completed" &&
                    it.detail.contains("callId=call-b") &&
                    it.detail.contains("elapsedMs=")
            }
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_tool_started" && it.detail.contains("callId=call-a")
            }
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_tool_started" && it.detail.contains("callId=call-b")
            }
        )
    }

    @Test
    fun `coordinator writes queue e2e events for multiple Hermes jobs`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val artifacts = Collections.synchronizedList(mutableListOf<Pair<VoiceE2EArtifact, String>>())
        val queueLogs = Collections.synchronizedList(mutableListOf<String>())
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            writeVoiceE2EArtifact = { name, content -> artifacts += name to content },
            logHermesQueueEvent = queueLogs::add,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCalls(
                listOf(
                    GeminiLiveEvent.ToolCall(callId = "call-a", name = "ask_hermes", prompt = "First"),
                    GeminiLiveEvent.ToolCall(callId = "call-b", name = "ask_hermes", prompt = "Second"),
                )
            )
        )
        assertEquals("call-a" to "First", toolApi.awaitRequest("call-a"))
        assertEquals("call-b" to "Second", toolApi.awaitRequest("call-b"))

        toolApi.complete(response(callId = "call-a", answer = "First answer\nfor review"))
        toolApi.complete(response(callId = "call-b", answer = "Second answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(
            setOf(queuedAck("call-a"), queuedAck("call-b")),
            gemini.toolResponses.toSet(),
        )
        assertEquals(2, gemini.textTurns.size)

        val rows = artifacts
            .filter { it.first == VoiceE2EArtifact.HermesEvents }
            .map { (_, content) ->
                assertFalse("HermesEvents rows must not contain literal LF", content.contains('\n'))
                assertFalse("HermesEvents rows must not contain literal CR", content.contains('\r'))
                Json.parseToJsonElement(content).jsonObject
            }

        assertEquals(6, rows.size)
        assertEquals(setOf("call-a", "call-b"), rows.map { it.string("callId") }.toSet())
        assertEquals(
            setOf("job-1", "job-2"),
            rows.filter { it.string("type") != "late_text_turn_sent" }.map { it.string("jobId") }.toSet(),
        )

        listOf("call-a", "call-b").forEach { callId ->
            val callRows = rows.filter { it.string("callId") == callId }
            assertEquals(
                listOf("job_created", "job_completed", "late_text_turn_sent"),
                callRows.map { it.string("type") },
            )
            val jobId = callRows.single { it.string("type") == "job_created" }.string("jobId")
            assertEquals(
                setOf(jobId),
                callRows.filter { it.string("type") != "late_text_turn_sent" }.map { it.string("jobId") }.toSet(),
            )
            assertEquals("none", callRows.single { it.string("type") == "late_text_turn_sent" }.string("jobId"))
            val completedRow = callRows.single { it.string("type") == "job_completed" }
            assertEquals("succeeded", completedRow.string("status"))
            assertFalse("HermesEvents rows must not duplicate raw answers", completedRow.containsKey("answer"))
            assertTrue(callRows.single { it.string("type") == "late_text_turn_sent" }.boolean("sent"))
            assertTrue(queueLogs.contains("type=job_created callId=$callId jobId=$jobId status=queued sent=n/a"))
            assertTrue(queueLogs.contains("type=job_completed callId=$callId jobId=$jobId status=succeeded sent=n/a"))
            assertTrue(queueLogs.contains("type=late_text_turn_sent callId=$callId jobId=none status=none sent=true"))
        }
        assertEquals(rows.size, queueLogs.size)
        assertEquals(
            "First answer\nfor review".length,
            rows.single { it.string("type") == "job_completed" && it.string("callId") == "call-a" }
                .int("answerChars"),
        )
        assertEquals(
            "Second answer".length,
            rows.single { it.string("type") == "job_completed" && it.string("callId") == "call-b" }
                .int("answerChars"),
        )
    }

    @Test
    fun `Hermes request hash diagnostic is emitted without raw prompt`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val toolApi = FakeVoiceToolApi()
        val expectedPromptHash = HermesToolResponseHash.sha256HexNormalized("private prompt")
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-request-hash", name = "ask_hermes", prompt = "private prompt")
        )

        val hashEvent = diagnostics.events.value.single { it.name == "hermes_tool_request_hash" }
        assertTrue(hashEvent.detail.contains("callId=call-request-hash"))
        assertTrue(hashEvent.detail.contains("promptChars=14"))
        assertTrue(hashEvent.detail.contains("normalizedChars=14"))
        assertTrue(hashEvent.detail.contains("promptHash=$expectedPromptHash"))
        assertFalse(diagnostics.events.value.any { it.detail.contains("private prompt") })

        assertEquals("call-request-hash" to "private prompt", toolApi.awaitRequest("call-request-hash"))
        toolApi.complete(response(callId = "call-request-hash", answer = "done"))
        coordinator.awaitToolJobsWithTimeout()
    }

    @Test
    fun `Hermes response hash diagnostic is emitted without raw answer`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val expectedHash = HermesToolResponseHash.sha256HexNormalized("alpha beta")
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            hermesResponseExpectedHash = expectedHash,
            logHermesResponseHash = {},
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-hash", name = "ask_hermes", prompt = "private prompt")
        )
        assertEquals("call-hash" to "private prompt", toolApi.awaitRequest("call-hash"))
        toolApi.complete(response(callId = "call-hash", answer = " \nalpha\t  beta\r\n", elapsedMs = 321))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-hash")), gemini.toolResponses)
        val hashEvent = diagnostics.events.value.single { it.name == "hermes_tool_response_hash" }
        assertTrue(hashEvent.detail.contains("callId=call-hash"))
        assertTrue(hashEvent.detail.contains("actualHash=$expectedHash"))
        assertTrue(hashEvent.detail.contains("expectedHashMatch=true"))
        assertTrue(hashEvent.detail.contains("serverElapsedMs=321"))
        assertFalse(hashEvent.detail.contains("alpha"))
        assertFalse(hashEvent.detail.contains("beta"))

        val successEvent = diagnostics.events.value.single { it.name == "hermes_job_completed" }
        assertTrue(successEvent.detail.contains("callId=call-hash"))
        assertTrue(successEvent.detail.contains("answerChars=16"))
        assertFalse(successEvent.detail.contains("alpha"))
        assertFalse(successEvent.detail.contains("beta"))
    }

    @Test
    fun `coordinator writes private e2e artifacts for transcript tool call and Hermes answer`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val artifacts = mutableMapOf<VoiceE2EArtifact, String>()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            writeVoiceE2EArtifact = { name, content -> artifacts[name] = content },
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("Please ask "))
        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("Hermes."))
        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(
                callId = "call-report",
                name = "ask_hermes",
                prompt = "Is Hermes connected to G-Brain? Answer yes or no.",
            )
        )
        assertEquals(
            "call-report" to "Is Hermes connected to G-Brain? Answer yes or no.",
            toolApi.awaitRequest("call-report"),
        )
        toolApi.complete(response(callId = "call-report", answer = "Yes.", elapsedMs = 123L))
        coordinator.awaitToolJobsWithTimeout()
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputTranscript("Yes, Hermes is connected."))

        assertEquals("Please ask Hermes.", artifacts[VoiceE2EArtifact.InputTranscript])
        assertEquals("Is Hermes connected to G-Brain? Answer yes or no.", artifacts[VoiceE2EArtifact.HermesCall])
        assertEquals("Yes.", artifacts[VoiceE2EArtifact.HermesAnswer])
        assertEquals("Yes, Hermes is connected.", artifacts[VoiceE2EArtifact.OutputTranscript])
    }

    @Test
    fun `Hermes response answer writer receives raw answer for private manual review artifact`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val writtenArtifacts = mutableMapOf<VoiceE2EArtifact, String>()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            writeVoiceE2EArtifact = { name, content -> writtenArtifacts[name] = content },
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-manual-answer", name = "ask_hermes", prompt = "private prompt")
        )
        assertEquals("call-manual-answer" to "private prompt", toolApi.awaitRequest("call-manual-answer"))
        toolApi.complete(response(callId = "call-manual-answer", answer = "raw private answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals("raw private answer", writtenArtifacts[VoiceE2EArtifact.HermesAnswer])
        assertFalse(diagnostics.events.value.any { it.detail.contains("raw private answer") })
    }

    @Test
    fun `Hermes response hash diagnostic emits actual hash without expected hash`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        var loggedDetail: String? = null
        val expectedHash = HermesToolResponseHash.sha256HexNormalized("alpha beta")
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            hermesResponseExpectedHash = "",
            logHermesResponseHash = { loggedDetail = it },
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-no-hash", name = "ask_hermes", prompt = "private prompt")
        )
        assertEquals("call-no-hash" to "private prompt", toolApi.awaitRequest("call-no-hash"))
        toolApi.complete(response(callId = "call-no-hash", answer = " \nalpha\t  beta\r\n"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-no-hash")), gemini.toolResponses)
        val hashEvent = diagnostics.events.value.single { it.name == "hermes_tool_response_hash" }
        assertTrue(hashEvent.detail.contains("callId=call-no-hash"))
        assertTrue(hashEvent.detail.contains("actualHash=$expectedHash"))
        assertFalse(hashEvent.detail.contains("expectedHashMatch="))
        assertFalse(hashEvent.detail.contains("alpha"))
        assertFalse(hashEvent.detail.contains("beta"))
        assertEquals(hashEvent.detail, loggedDetail)

        val successEvent = diagnostics.events.value.single { it.name == "hermes_job_completed" }
        assertTrue(successEvent.detail.contains("callId=call-no-hash"))
        assertTrue(successEvent.detail.contains("answerChars=16"))
        assertFalse(successEvent.detail.contains("alpha beta"))
    }

    @Test
    fun `Hermes response hash logger failure does not fail tool call`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val expectedHash = HermesToolResponseHash.sha256HexNormalized("alpha beta")
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            hermesResponseExpectedHash = expectedHash,
            logHermesResponseHash = { error("logger failed") },
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-log-fails", name = "ask_hermes", prompt = "private prompt")
        )
        assertEquals("call-log-fails" to "private prompt", toolApi.awaitRequest("call-log-fails"))
        toolApi.complete(response(callId = "call-log-fails", answer = "alpha beta"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-log-fails")), gemini.toolResponses)
        assertHermesAnswered(callId = "call-log-fails", status = coordinator.state.value.tool)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_tool_response_hash" &&
                    it.detail.contains("callId=call-log-fails") &&
                    it.detail.contains("expectedHashMatch=true")
            }
        )
        val successEvent = diagnostics.events.value.single { it.name == "hermes_job_completed" }
        assertTrue(successEvent.detail.contains("callId=call-log-fails"))
        assertTrue(successEvent.detail.contains("answerChars=10"))
        assertFalse(successEvent.detail.contains("alpha beta"))
        assertFalse(
            diagnostics.events.value.any {
                it.name == "hermes_job_failed" && it.detail.contains("logger failed")
            }
        )
    }

    @Test
    fun `batched failure remains aggregate summary after another call succeeds`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCalls(
                listOf(
                    GeminiLiveEvent.ToolCall(callId = "call-fail", name = "ask_hermes", prompt = "First"),
                    GeminiLiveEvent.ToolCall(callId = "call-ok", name = "ask_hermes", prompt = "Second"),
                )
            )
        )
        assertEquals("call-fail" to "First", toolApi.awaitRequest("call-fail"))
        assertEquals("call-ok" to "Second", toolApi.awaitRequest("call-ok"))

        toolApi.failJob(callId = "call-fail", message = "Hermes failed")
        withTimeout(500) {
            while (coordinator.state.value.toolCalls["call-fail"] !is VoiceToolStatus.HermesFailed) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(VoiceToolStatus.QueuedHermes("call-ok", "job-2"), coordinator.state.value.tool)

        toolApi.complete(response(callId = "call-ok", answer = "Second answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(
            VoiceToolStatus.HermesFailed(callId = "call-fail", message = "Hermes failed"),
            coordinator.state.value.tool,
        )
        assertEquals(setOf("call-fail", "call-ok"), coordinator.state.value.toolCalls.keys)
        assertEquals(
            VoiceToolStatus.HermesFailed(callId = "call-fail", message = "Hermes failed"),
            coordinator.state.value.toolCalls.getValue("call-fail"),
        )
        assertHermesAnswered(callId = "call-ok", status = coordinator.state.value.toolCalls.getValue("call-ok"))
        assertEquals(setOf(queuedAck("call-fail"), queuedAck("call-ok")), gemini.toolResponses.toSet())
    }

    @Test
    fun `Hermes failure updates failed tool status and does not crash`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCall(callId = "call-3", name = "ask_hermes", prompt = "fail"))
        assertEquals("call-3" to "fail", toolApi.awaitRequest("call-3"))

        toolApi.failJob(callId = "call-3", message = "Hermes offline")
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(
            VoiceToolStatus.HermesFailed(callId = "call-3", message = "Hermes offline"),
            coordinator.state.value.tool,
        )
        assertEquals(listOf(queuedAck("call-3")), gemini.toolResponses)
        assertTrue(
            diagnostics.events.value.any { it.name == "hermes_job_failed" && it.detail.contains("Hermes offline") }
        )
    }

    @Test
    fun `Hermes failure log sink receives safe auth failure detail`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val capturedFailures = mutableListOf<String>()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            logHermesToolFailure = { capturedFailures += it },
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(
                callId = "call-auth-fail",
                name = "ask_hermes",
                prompt = "private prompt",
            )
        )
        assertEquals("call-auth-fail" to "private prompt", toolApi.awaitRequest("call-auth-fail"))

        toolApi.fail(
            VoiceLabHttpException(
                statusCode = 403,
                safePreview = "{\"prompt\":\"private prompt\",\"answer\":\"private answer\"}",
                failure = VoiceFailure(
                    kind = VoiceFailureKind.Auth,
                    safeMessage = "Voice Lab request failed 403",
                    safeSummary = "Voice Lab request failed 403",
                    retryable = false,
                    source = VoiceFailureSource.VoiceLab,
                ),
            )
        )
        coordinator.awaitToolJobsWithTimeout()

        val failureDetail = capturedFailures.single()
        assertTrue(failureDetail.contains("callId=call-auth-fail"))
        assertTrue(failureDetail.contains("elapsedMs="))
        assertTrue(failureDetail.contains("Voice Lab request failed 403"))
        assertFalse(failureDetail.contains("private prompt"))
        assertFalse(failureDetail.contains("private answer"))
        assertFalse(failureDetail.contains("\"prompt\""))
        assertFalse(failureDetail.contains("\"answer\""))
    }

    @Test
    fun `failed Gemini tool response send does not cancel Hermes background job`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient().apply {
            failToolResponses += "call-send-fails"
        }
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-send-fails", name = "ask_hermes", prompt = "send fails")
        )
        assertEquals("call-send-fails" to "send fails", toolApi.awaitRequest("call-send-fails"))

        toolApi.complete(response(callId = "call-send-fails", answer = "answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertFalse(toolApi.wasRemoteCancelled("call-send-fails"))
        assertHermesAnswered(callId = "call-send-fails", status = coordinator.state.value.tool)
    }

    @Test
    fun `transcripts and output audio update state and playback`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("hello "))
        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("world"))
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputTranscript("answer "))
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputTranscript("text"))
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("base64-pcm"))

        assertEquals("hello world", coordinator.state.value.inputTranscript)
        assertEquals("answer text", coordinator.state.value.outputTranscript)
        assertEquals(VoiceAudioStatus.AssistantSpeaking, coordinator.state.value.audio)
        assertEquals(listOf("base64-pcm"), audio.playedPcm16)
    }

    @Test
    fun `late non tool events after close do not mutate state or play audio`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            scope = this,
        )

        coordinator.close()
        val stateAfterClose = coordinator.state.value

        coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("late-pcm"))
        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("late input"))
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputTranscript("late output"))
        coordinator.onGeminiEvent(GeminiLiveEvent.Error(message = "late error", raw = "{}"))

        assertEquals(stateAfterClose, coordinator.state.value)
        assertEquals(emptyList<String>(), audio.playedPcm16)
        assertEquals(1, audio.releaseCalls)
    }

    @Test
    fun `close is idempotent and clears visible state`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            scope = this,
        )

        coordinator.close()
        coordinator.close()

        assertTrue(gemini.closeCalls >= 1)
        assertEquals(1, audio.releaseCalls)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)
        assertEquals(emptyMap<String, VoiceToolStatus>(), coordinator.state.value.toolCalls)
    }

    @Test
    fun `close does not wait for in flight Gemini tool response send before releasing resources`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val blockedSend = gemini.blockToolResponse("call-close-send")
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-close-send", name = "ask_hermes", prompt = "close race")
        )
        assertEquals("call-close-send" to "close race", toolApi.awaitRequest("call-close-send"))

        toolApi.complete(response(callId = "call-close-send", answer = "answer before close"))
        assertTrue(blockedSend.started.await(500, TimeUnit.MILLISECONDS))

        val closeJob = launch(Dispatchers.Default) {
            coordinator.close()
        }
        assertFalse(blockedSend.release.await(50, TimeUnit.MILLISECONDS))
        withTimeout(500) {
            closeJob.join()
        }
        assertTrue(gemini.closeCalls >= 1)
        assertEquals(1, audio.releaseCalls)

        blockedSend.release.countDown()
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-close-send")), gemini.toolResponses)
    }

    @Test
    fun `close does not deadlock when tool response send emits event synchronously`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val blockedSend = gemini.blockToolResponse("call-close-reentry")
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            scope = this,
            dispatcher = Dispatchers.Default,
        )
        gemini.onBeforeToolResponseRecorded = {
            coordinator.onGeminiEvent(GeminiLiveEvent.Error(message = "late send error", raw = "{}"))
        }

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-close-reentry", name = "ask_hermes", prompt = "close race")
        )
        assertEquals("call-close-reentry" to "close race", toolApi.awaitRequest("call-close-reentry"))

        toolApi.complete(response(callId = "call-close-reentry", answer = "answer before close"))
        assertTrue(blockedSend.started.await(500, TimeUnit.MILLISECONDS))

        val closeJob = launch(Dispatchers.Default) {
            coordinator.close()
        }
        assertEquals(0, audio.releaseCalls)

        blockedSend.release.countDown()
        withTimeout(500) {
            closeJob.join()
        }

        assertEquals(listOf(queuedAck("call-close-reentry")), gemini.toolResponses)
        assertEquals(1, gemini.closeCalls)
        assertEquals(1, audio.releaseCalls)
    }

    @Test
    fun `close waits for in flight non tool event before releasing audio`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val blockedPlayback = audio.blockNextPlayback()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        val eventJob = launch(Dispatchers.Default) {
            coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("blocked-pcm"))
        }
        assertTrue(blockedPlayback.started.await(500, TimeUnit.MILLISECONDS))

        val closeJob = launch(Dispatchers.Default) {
            coordinator.close()
        }
        assertFalse(blockedPlayback.release.await(50, TimeUnit.MILLISECONDS))
        assertEquals(0, audio.releaseCalls)

        blockedPlayback.release.countDown()
        withTimeout(500) {
            eventJob.join()
            closeJob.join()
        }

        assertEquals(listOf("blocked-pcm"), audio.playedPcm16)
        assertEquals(1, audio.releaseCalls)
    }

    @Test
    fun `public interrupt suppresses playback while output audio write is in flight`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val blockedPlayback = audio.blockNextPlayback()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        val eventJob = launch(Dispatchers.Default) {
            coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("blocked-interrupt-pcm"))
        }
        assertTrue(blockedPlayback.started.await(500, TimeUnit.MILLISECONDS))

        val interruptJob = launch(Dispatchers.Default) {
            coordinator.suppressPlayback()
        }

        withTimeout(500) {
            interruptJob.join()
        }
        audio.awaitSuppressPlaybackCalls(1)
        assertEquals(VoiceAudioStatus.PlaybackSuppressed, coordinator.state.value.audio)

        blockedPlayback.release.countDown()
        withTimeout(500) {
            eventJob.join()
        }
        assertEquals(VoiceAudioStatus.PlaybackSuppressed, coordinator.state.value.audio)
    }

    @Test
    fun `public interrupt updates state before blocking audio suppression returns`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val blockedSuppression = audio.blockNextSuppression()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.suppressPlayback()

        assertEquals(VoiceAudioStatus.PlaybackSuppressed, coordinator.state.value.audio)
        assertTrue(blockedSuppression.started.await(500, TimeUnit.MILLISECONDS))

        blockedSuppression.release.countDown()
    }

    @Test
    fun `close suppresses Hermes result while waiting for in flight non tool event`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val blockedPlayback = audio.blockNextPlayback()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            diagnostics = diagnostics,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-close-wait", name = "ask_hermes", prompt = "close wait")
        )
        assertEquals("call-close-wait" to "close wait", toolApi.awaitRequest("call-close-wait"))

        val eventJob = launch(Dispatchers.Default) {
            coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("blocked-close-pcm"))
        }
        assertTrue(blockedPlayback.started.await(500, TimeUnit.MILLISECONDS))

        val closeJob = launch(Dispatchers.Default) {
            coordinator.close()
        }
        diagnostics.awaitEvent("coordinator_closing")
        toolApi.complete(response(callId = "call-close-wait", answer = "late answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-close-wait")), gemini.toolResponses)
        assertEquals(0, audio.releaseCalls)

        blockedPlayback.release.countDown()
        withTimeout(500) {
            eventJob.join()
            closeJob.join()
        }

        assertEquals(listOf("blocked-close-pcm"), audio.playedPcm16)
        assertEquals(1, audio.releaseCalls)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)
        assertEquals(emptyMap<String, VoiceToolStatus>(), coordinator.state.value.toolCalls)
    }

    @Test
    fun `non tool lifecycle events record diagnostics without crashing`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.SetupComplete)
        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCallCancellation(listOf("call-1", "call-2")))
        coordinator.onGeminiEvent(
            GeminiLiveEvent.SessionResumptionUpdate(
                newHandle = "resume-handle",
                resumable = true,
            )
        )
        coordinator.onGeminiEvent(GeminiLiveEvent.Ignored("""{"serverContent":{}}"""))

        assertTrue(diagnostics.events.value.any { it.name == "gemini_setup_complete" })
        assertTrue(
            diagnostics.events.value.any {
                it.name == "tool_call_cancellation" &&
                    it.detail.contains("call-1") &&
                    it.detail.contains("call-2")
            }
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_resumption_update" &&
                    it.detail.contains("resumable=true") &&
                    it.detail.contains("resume-handle")
            }
        )
        assertTrue(diagnostics.events.value.any { it.name == "gemini_event_ignored" })
    }

    @Test
    fun `Gemini error updates session error state`() = runTest {
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.Error(message = "Gemini failed", raw = "{}"))

        assertEquals(VoiceSessionStatus.Error("Gemini failed"), coordinator.state.value.session)
        assertEquals("Gemini failed", coordinator.state.value.error)
    }

    @Test
    fun `Gemini WebSocket close records structured diagnostic and visible error`() = runTest {
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.WebSocketClosed(code = 1001, reason = "going away"))

        assertEquals(
            VoiceSessionStatus.Error("Gemini WebSocket closed: 1001 going away"),
            coordinator.state.value.session,
        )
        assertTrue(
            coordinator.state.value.diagnostics.any {
                it.name == "gemini_ws_closed" && it.detail == "code=1001, reason=going away"
            }
        )
    }

    @Test
    fun `Gemini WebSocket close after session end is ignored instead of shown as error`() = runTest {
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )
        coordinator.updateSessionStatus(VoiceSessionStatus.Connected)

        coordinator.prepareForSessionEnd()
        coordinator.onGeminiEvent(GeminiLiveEvent.WebSocketClosed(code = 1008, reason = "The operation was aborted."))

        assertEquals(VoiceSessionStatus.Connected, coordinator.state.value.session)
        assertEquals(null, coordinator.state.value.error)
        assertTrue(
            coordinator.state.value.diagnostics.any {
                it.name == "stale_gemini_event" && it.detail == "WebSocketClosed"
            }
        )
    }

    @Test
    fun `asynchronous audio error records diagnostic and visible error`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            scope = this,
        )

        audio.emitError("Audio focus lost: -1")

        assertEquals(VoiceSessionStatus.Error("Audio focus lost: -1"), coordinator.state.value.session)
        assertTrue(
            coordinator.state.value.diagnostics.any {
                it.name == "audio_error" && it.detail == "Audio focus lost: -1"
            }
        )
    }

    @Test
    fun `coordinator persists transcript fragments as turns and Hermes tool records`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("hel"))
        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("lo"))
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputTranscript("h"))
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputTranscript("i"))
        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCall(callId = "call-persist", name = "ask_hermes", prompt = "look up"))
        assertEquals("call-persist" to "look up", toolApi.awaitRequest("call-persist"))
        toolApi.complete(response(callId = "call-persist", answer = "tool answer", elapsedMs = 321L))
        coordinator.awaitToolJobsWithTimeout()
        coordinator.awaitPersistenceJobsWithTimeout()
        conversationStore.awaitUpdateCount(6)

        val messages = conversationStore.conversation.value.currentMessages
        assertEquals(3, messages.size)
        val textParts = messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>()
        assertTrue(textParts.any { it.text == "hello" })
        val userText = textParts.single { it.text == "hello" }
        val assistantText = textParts.single { it.metadata?.get("voice_event_id")?.jsonPrimitive?.content == "assistant-2" }
        val tool = messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>().single()
        val sessionId = userText.metadata?.get("voice_session_id")?.jsonPrimitive?.content
        assertTrue(!sessionId.isNullOrBlank())
        assertEquals("voice_agent", userText.metadata?.get("voice_source")?.jsonPrimitive?.content)
        assertEquals(sessionId, assistantText.metadata?.get("voice_session_id")?.jsonPrimitive?.content)
        assertEquals(sessionId, tool.metadata?.get("voice_session_id")?.jsonPrimitive?.content)
        assertEquals("user-1", userText.metadata?.get("voice_event_id")?.jsonPrimitive?.content)
        assertEquals("assistant-2", assistantText.metadata?.get("voice_event_id")?.jsonPrimitive?.content)
        assertEquals("call-persist", tool.metadata?.get("voice_event_id")?.jsonPrimitive?.content)
        assertEquals("call-persist", tool.metadata?.get("voice_call_id")?.jsonPrimitive?.content)
        assertTrue(!tool.metadata?.get("voice_created_at")?.jsonPrimitive?.content.isNullOrBlank())
        assertEquals("call-persist", tool.toolCallId)
        assertTrue(tool.output.filterIsInstance<UIMessagePart.Text>().any { it.text == "tool answer" })
        assertTrue(
            coordinator.state.value.diagnostics.any {
                it.name == "input_transcript_delta" && it.detail.contains("chars=3")
            }
        )
        assertTrue(
            coordinator.state.value.diagnostics.any {
                it.name == "output_transcript_delta" && it.detail.contains("chars=1")
            }
        )
        assertTrue(
            coordinator.state.value.diagnostics.any {
                it.name == "hermes_job_completed" &&
                    it.detail.contains("elapsedMs=") &&
                    it.detail.contains("serverElapsedMs=321") &&
                    it.detail.contains("answerChars=11")
            }
        )
        assertFalse(
            coordinator.state.value.diagnostics.any { it.detail.contains("tool answer") }
        )
        assertTrue(coordinator.state.value.diagnostics.any { it.name == "conversation_persist_saved" })
    }

    @Test
    fun `coordinator persists live user transcript as partial and marks session closed before final on close`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("hel"))
        conversationStore.awaitUpdateCount(1)

        val liveUserText = conversationStore.conversation.value.currentMessages.single()
            .parts
            .filterIsInstance<UIMessagePart.Text>()
            .single()
        assertEquals("hel", liveUserText.text)
        assertEquals("partial", liveUserText.metadata?.get("voice_status")?.jsonPrimitive?.content)

        coordinator.closeAndDrain()

        val closedUserText = conversationStore.conversation.value.currentMessages.single()
            .parts
            .filterIsInstance<UIMessagePart.Text>()
            .single()
        assertEquals("hel", closedUserText.text)
        assertEquals("session-closed-before-final", closedUserText.metadata?.get("voice_status")?.jsonPrimitive?.content)
    }

    @Test
    fun `coordinator persists assistant transcript as partial until Gemini generation completes`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.OutputTranscript("hel"))
        conversationStore.awaitUpdateCount(1)

        val partialAssistantText = conversationStore.conversation.value.currentMessages.single()
            .parts
            .filterIsInstance<UIMessagePart.Text>()
            .single()
        assertEquals("hel", partialAssistantText.text)
        assertEquals("partial", partialAssistantText.metadata?.get("voice_status")?.jsonPrimitive?.content)

        coordinator.onGeminiEvent(GeminiLiveEvent.GenerationComplete)
        conversationStore.awaitUpdateCount(2)

        val completeAssistantText = conversationStore.conversation.value.currentMessages.single()
            .parts
            .filterIsInstance<UIMessagePart.Text>()
            .single()
        assertEquals("hel", completeAssistantText.text)
        assertEquals("complete", completeAssistantText.metadata?.get("voice_status")?.jsonPrimitive?.content)
    }

    @Test
    fun `tool cancellation persists accepted Hermes record as canceled`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-cancel-persist", name = "ask_hermes", prompt = "cancel me")
        )
        assertEquals("call-cancel-persist" to "cancel me", toolApi.awaitRequest("call-cancel-persist"))

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCallCancellation(listOf("call-cancel-persist")))
        toolApi.awaitRemoteCancelled("call-cancel-persist")
        conversationStore.awaitHermesToolStatus("call-cancel-persist", "canceled")

        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-cancel-persist", tool.toolCallId)
        assertEquals("canceled", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("Hermes job canceled.", tool.output.text())
    }

    @Test
    fun `reconnect cleanup leaves accepted Hermes record running`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-reconnect-persist", name = "ask_hermes", prompt = "old")
        )
        assertEquals("call-reconnect-persist" to "old", toolApi.awaitRequest("call-reconnect-persist"))

        coordinator.prepareForReconnect()
        toolApi.complete(response(callId = "call-reconnect-persist", answer = "old answer"))
        coordinator.awaitToolJobsWithTimeout()
        coordinator.awaitPersistenceJobsWithTimeout()

        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-reconnect-persist", tool.toolCallId)
        assertEquals("complete", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("old answer", tool.output.text())
        assertFalse(toolApi.wasCancelled("call-reconnect-persist"))
        assertFalse(toolApi.wasRemoteCancelled("call-reconnect-persist"))
    }

    @Test
    fun `close leaves accepted Hermes record running`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-close-persist", name = "ask_hermes", prompt = "close")
        )
        assertEquals("call-close-persist" to "close", toolApi.awaitRequest("call-close-persist"))

        coordinator.close()
        toolApi.complete(response(callId = "call-close-persist", answer = "close answer"))
        coordinator.awaitToolJobsWithTimeout()
        coordinator.awaitPersistenceJobsWithTimeout()

        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-close-persist", tool.toolCallId)
        assertEquals("complete", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("close answer", tool.output.text())
        assertFalse(toolApi.wasCancelled("call-close-persist"))
        assertFalse(toolApi.wasRemoteCancelled("call-close-persist"))
    }

    @Test
    fun `close after queued Gemini acknowledgement send starts lets Hermes record complete`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val blockedSend = gemini.blockToolResponse("call-close-sending-persist")
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-close-sending-persist", name = "ask_hermes", prompt = "close")
        )
        assertEquals("call-close-sending-persist" to "close", toolApi.awaitRequest("call-close-sending-persist"))
        toolApi.complete(response(callId = "call-close-sending-persist", answer = "sent answer"))
        assertTrue(blockedSend.started.await(500, TimeUnit.MILLISECONDS))

        val closeJob = launch(Dispatchers.Default) {
            coordinator.close()
        }
        assertFalse(blockedSend.release.await(50, TimeUnit.MILLISECONDS))
        blockedSend.release.countDown()
        withTimeout(500) {
            closeJob.join()
        }
        conversationStore.awaitHermesToolStatus("call-close-sending-persist", "complete")

        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-close-sending-persist", tool.toolCallId)
        assertEquals("complete", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("sent answer", tool.output.text())
    }

    @Test
    fun `reconnect after Gemini tool response send starts does not wait and lets Hermes record complete`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val blockedSend = gemini.blockToolResponse("call-reconnect-sending-persist")
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-reconnect-sending-persist", name = "ask_hermes", prompt = "old")
        )
        assertEquals("call-reconnect-sending-persist" to "old", toolApi.awaitRequest("call-reconnect-sending-persist"))
        toolApi.complete(response(callId = "call-reconnect-sending-persist", answer = "sent answer"))
        assertTrue(blockedSend.started.await(500, TimeUnit.MILLISECONDS))

        val reconnectJob = launch(Dispatchers.Default) {
            coordinator.prepareForReconnect()
        }
        withTimeout(500) {
            reconnectJob.join()
        }
        assertFalse(blockedSend.release.await(50, TimeUnit.MILLISECONDS))

        blockedSend.release.countDown()
        coordinator.awaitToolJobsWithTimeout()
        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-reconnect-sending-persist", tool.toolCallId)
        assertEquals("complete", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("sent answer", tool.output.text())
    }

    @Test
    fun `forced close after Gemini tool response send starts lets Hermes record complete`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val blockedSend = gemini.blockToolResponse("call-forced-close-sending-persist")
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-forced-close-sending-persist", name = "ask_hermes", prompt = "close")
        )
        assertEquals("call-forced-close-sending-persist" to "close", toolApi.awaitRequest("call-forced-close-sending-persist"))
        toolApi.complete(response(callId = "call-forced-close-sending-persist", answer = "sent answer"))
        assertTrue(blockedSend.started.await(500, TimeUnit.MILLISECONDS))

        val closeJob = launch(Dispatchers.Default) {
            coordinator.close(waitForStartedSends = false)
        }
        withTimeout(500) {
            closeJob.join()
        }
        assertFalse(blockedSend.release.await(50, TimeUnit.MILLISECONDS))

        blockedSend.release.countDown()
        coordinator.awaitToolJobsWithTimeout()
        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-forced-close-sending-persist", tool.toolCallId)
        assertEquals("complete", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("sent answer", tool.output.text())
    }

    @Test
    fun `Gemini cancellation after queued acknowledgement send starts persists Hermes record as canceled`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val blockedSend = gemini.blockToolResponse("call-cancel-sending-persist")
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-cancel-sending-persist", name = "ask_hermes", prompt = "cancel")
        )
        assertEquals("call-cancel-sending-persist" to "cancel", toolApi.awaitRequest("call-cancel-sending-persist"))
        toolApi.complete(response(callId = "call-cancel-sending-persist", answer = "sent answer"))
        assertTrue(blockedSend.started.await(500, TimeUnit.MILLISECONDS))

        val cancelJob = launch(Dispatchers.Default) {
            coordinator.onGeminiEvent(GeminiLiveEvent.ToolCallCancellation(listOf("call-cancel-sending-persist")))
        }
        assertFalse(blockedSend.release.await(50, TimeUnit.MILLISECONDS))
        blockedSend.release.countDown()
        withTimeout(500) {
            cancelJob.join()
        }
        coordinator.awaitPersistenceJobsWithTimeout()

        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-cancel-sending-persist", tool.toolCallId)
        assertEquals("canceled", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("Hermes job canceled.", tool.output.text())
    }

    @Test
    fun `coordinator snapshots transcript text before delayed persistence writes`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val firstUpdate = conversationStore.blockNextUpdate()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("hel"))
        assertTrue(firstUpdate.started.await(500, TimeUnit.MILLISECONDS))
        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("lo"))

        firstUpdate.release.countDown()
        conversationStore.awaitUpdateCount(2)

        assertEquals("hel", conversationStore.updateAt(0).currentMessages.single().parts.text())
        assertEquals("hello", conversationStore.updateAt(1).currentMessages.single().parts.text())
    }

    @Test
    fun `reconnect cleanup keeps in flight Hermes call alive`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCall(callId = "call-reconnect", name = "ask_hermes", prompt = "old"))
        assertEquals("call-reconnect" to "old", toolApi.awaitRequest("call-reconnect"))

        coordinator.prepareForReconnect()
        toolApi.complete(response(callId = "call-reconnect", answer = "old answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf(queuedAck("call-reconnect")), gemini.toolResponses)
        assertHermesAnswered(callId = "call-reconnect", status = coordinator.state.value.tool)
        assertFalse(toolApi.wasCancelled("call-reconnect"))
        assertFalse(toolApi.wasRemoteCancelled("call-reconnect"))
    }

    @Test
    fun `stale session events after reconnect do not mutate coordinator state`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            scope = this,
        )
        val staleSessionId = coordinator.nextSessionId()

        coordinator.prepareForReconnect()
        coordinator.onGeminiEvent(staleSessionId, GeminiLiveEvent.InputTranscript("late input"))
        coordinator.onGeminiEvent(
            staleSessionId,
            GeminiLiveEvent.ToolCall(callId = "call-stale-session", name = "ask_hermes", prompt = "stale"),
        )
        delay(50)

        assertEquals("", coordinator.state.value.inputTranscript)
        assertEquals(emptyList<Pair<String, String>>(), toolApi.requests)
        assertTrue(diagnostics.events.value.any { it.name == "stale_gemini_event" })
    }

    @Test
    fun `session starts forwards capture audio and closes resources`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(
                    systemInstruction = "system voice prompt",
                    turns = listOf(GeminiContentTurn(role = "user", text = "prior turn")),
                )
            ),
            scope = this,
        )

        session.start()
        gemini.awaitConnect()

        assertEquals(listOf("gemini-flash"), sessionApi.createdSessions)
        assertEquals("token-1", gemini.connectedToken)
        assertEquals("wss://voice.test/live", gemini.connectedWebsocketUrl)
        assertEquals("gemini-live-test", gemini.connectedProviderModel)
        assertEquals(
            "system voice prompt\n\nPrevious RikkaHub conversation context:\nUser: prior turn",
            gemini.connectedSystemInstruction,
        )
        assertEquals(emptyList<GeminiContentTurn>(), gemini.connectedContextTurns)
        assertEquals(1, audio.startCaptureCalls)

        audio.emitCapture(byteArrayOf(1, 2, 3))
        assertEquals(listOf(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))), gemini.audioMessages)

        session.setMuted(true)
        assertEquals(listOf(1L), gemini.audioStreamEndSessionIds)
        assertEquals(1, audio.stopCaptureCalls)

        session.end()
        withTimeout(500) {
            while (gemini.closeCalls < 1 || audio.releaseCalls < 1) {
                delay(10)
            }
        }

        assertTrue(gemini.closeCalls >= 1)
        assertEquals(1, audio.releaseCalls)
        assertEquals(VoiceSessionStatus.Ended, session.state.value.session)
    }

    @Test
    fun `session metadata json is written through start connect and end lifecycle`() = runTest {
        val root = Files.createTempDirectory("voice-e2e-runtime-session").toFile()
        val artifactScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val artifactWriter = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                traceId = "VA000123",
                scope = artifactScope,
            )
            val sessionApi = FakeVoiceSessionApi()
            val blockedSession = sessionApi.blockNextSession()
            val session = VoiceAgentCallSession(
                modelId = "gemini-flash",
                sessionApi = sessionApi,
                toolApi = FakeVoiceToolApi(),
                gemini = FakeGeminiLiveVoiceClient(),
                audio = FakeVoiceAudioEngine(),
                conversationStore = FakeVoiceConversationStore(),
                contextProvider = FakeVoiceAgentContextProvider(
                    VoiceContext(systemInstruction = "system", turns = emptyList())
                ),
                traceContext = VoiceTraceContext(traceId = "VA000123", voiceSessionId = "VA000123"),
                voiceE2EArtifacts = artifactWriter,
                sessionMetadata = VoiceE2ESessionMetadata(
                    voiceTraceId = "VA000123",
                    voiceSessionId = "VA000123",
                    conversationId = "conversation-123",
                    packageName = "me.rerere.rikkahub",
                    versionName = "2.2.6",
                    versionCode = "162",
                    debuggable = true,
                    voiceModelId = "gemini-flash",
                    providerModel = null,
                    status = "created",
                    startedAtEpochMs = 1_700_000_000_000,
                    sentryDsnConfigured = true,
                    sentryTracingEnabled = true,
                    sentryPropagationCreated = true,
                ),
                nowMs = { 1_700_000_000_999 },
                scope = this,
            )
            val sessionJson = File(VoiceE2EArtifactPaths.rootDirectory(root), "VA000123/session.json")

            session.start()
            withTimeout(500) {
                blockedSession.started.await()
            }
            artifactWriter.drain()

            val started = Json.parseToJsonElement(sessionJson.readText()).jsonObject
            assertEquals("started", started.string("status"))
            assertEquals("VA000123", started.string("voiceTraceId"))
            assertEquals("VA000123", started.string("voiceSessionId"))
            assertEquals("conversation-123", started.string("conversationId"))
            assertEquals("me.rerere.rikkahub", started.string("packageName"))
            assertEquals("2.2.6", started.string("versionName"))
            assertEquals("162", started.string("versionCode"))
            assertTrue(started.boolean("debuggable"))
            assertEquals("gemini-flash", started.string("voiceModelId"))
            assertEquals("1700000000000", started.getValue("startedAtEpochMs").jsonPrimitive.content)
            assertTrue(started.boolean("sentryDsnConfigured"))
            assertTrue(started.boolean("sentryTracingEnabled"))
            assertTrue(started.boolean("sentryPropagationCreated"))

            blockedSession.release.complete(Unit)
            withTimeout(500) {
                while (session.state.value.session != VoiceSessionStatus.Connected) {
                    delay(10)
                }
            }
            artifactWriter.drain()

            val connected = Json.parseToJsonElement(sessionJson.readText()).jsonObject
            assertEquals("connected", connected.string("status"))
            assertEquals("gemini-live-test", connected.string("providerModel"))

            session.endAndDrain()

            val ended = Json.parseToJsonElement(sessionJson.readText()).jsonObject
            assertEquals("ended", ended.string("status"))
            assertEquals("user_end", ended.string("closeStatus"))
            assertEquals("1700000000999", ended.getValue("endedAtEpochMs").jsonPrimitive.content)
        } finally {
            artifactScope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `closeNow persists final session metadata before writer scope cancellation`() = runTest {
        val root = Files.createTempDirectory("voice-e2e-close-now-session").toFile()
        val artifactScope = CoroutineScope(SupervisorJob())
        try {
            val artifactWriter = VoiceE2EArtifactWriter.create(
                enabled = true,
                rootDirectory = root,
                traceId = "VA000124",
                scope = artifactScope,
            )
            val gemini = FakeGeminiLiveVoiceClient().apply {
                onClose = { artifactScope.cancel() }
            }
            val session = VoiceAgentCallSession(
                modelId = "gemini-flash",
                sessionApi = FakeVoiceSessionApi(),
                toolApi = FakeVoiceToolApi(),
                gemini = gemini,
                audio = FakeVoiceAudioEngine(),
                conversationStore = FakeVoiceConversationStore(),
                contextProvider = FakeVoiceAgentContextProvider(
                    VoiceContext(systemInstruction = "system", turns = emptyList())
                ),
                traceContext = VoiceTraceContext(traceId = "VA000124", voiceSessionId = "VA000124"),
                voiceE2EArtifacts = artifactWriter,
                sessionMetadata = VoiceE2ESessionMetadata(
                    voiceTraceId = "VA000124",
                    voiceSessionId = "VA000124",
                    conversationId = "conversation-124",
                    packageName = "me.rerere.rikkahub",
                    versionName = "2.2.6",
                    versionCode = "162",
                    debuggable = true,
                    voiceModelId = "gemini-flash",
                    providerModel = null,
                    status = "created",
                    startedAtEpochMs = 1_700_000_000_000,
                    sentryDsnConfigured = true,
                    sentryTracingEnabled = true,
                    sentryPropagationCreated = true,
                ),
                nowMs = { 1_700_000_001_999 },
                scope = this,
            )

            session.closeNow()
            artifactScope.cancel()

            val sessionJson = File(VoiceE2EArtifactPaths.rootDirectory(root), "VA000124/session.json")
            val ended = Json.parseToJsonElement(sessionJson.readText()).jsonObject
            assertEquals("ended", ended.string("status"))
            assertEquals("close_now", ended.string("closeStatus"))
            assertEquals("1700000001999", ended.getValue("endedAtEpochMs").jsonPrimitive.content)
        } finally {
            artifactScope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `session does not overwrite Gemini startup error with connected`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient().apply {
            connectEvent = GeminiLiveEvent.Error(message = "Failed to send Gemini setup message", raw = "{}")
        }
        val audio = FakeVoiceAudioEngine()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = this,
        )

        session.start()
        gemini.awaitConnect()

        assertEquals(VoiceSessionStatus.Error("Failed to send Gemini setup message"), session.state.value.session)
        assertEquals(0, audio.startCaptureCalls)
        assertEquals(1, gemini.closeCalls)
    }

    @Test
    fun `coordinator clears previous visible error when Gemini setup completes`() = runTest {
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.Error(message = "Failed to connect", raw = "{}"))
        coordinator.onGeminiEvent(GeminiLiveEvent.SetupComplete)

        assertEquals(VoiceSessionStatus.Connected, coordinator.state.value.session)
        assertNull(coordinator.state.value.error)
    }

    @Test
    fun `session closes Gemini when capture startup fails after connect`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine().apply {
            startCaptureError = IllegalStateException("microphone unavailable")
        }
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = this,
        )

        session.start()
        gemini.awaitConnect()

        assertEquals(VoiceSessionStatus.Error("microphone unavailable"), session.state.value.session)
        assertEquals(1, audio.startCaptureCalls)
        assertEquals(1, audio.stopCaptureCalls)
        assertEquals(1, gemini.closeCalls)
        audio.emitCapture(byteArrayOf(1, 2, 3))
        assertEquals(emptyList<String>(), gemini.audioMessages)
    }

    @Test
    fun `session closes Gemini and stops capture on async Gemini error`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = toolApi,
            gemini = gemini,
            audio = audio,
            conversationStore = conversationStore,
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(
                    systemInstruction = "system",
                    turns = listOf(GeminiContentTurn(role = "user", text = "existing context")),
                )
            ),
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        assertEquals(1, audio.startCaptureCalls)
        audio.emitCapture(byteArrayOf(1, 2, 3))
        assertEquals(listOf(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))), gemini.audioMessages)
        gemini.eventHandlers.single()(
            GeminiLiveEvent.ToolCall(callId = "call-async-error", name = "ask_hermes", prompt = "pending")
        )
        assertEquals("call-async-error" to "pending", toolApi.awaitRequest("call-async-error"))

        gemini.eventHandlers.single()(
            GeminiLiveEvent.Error(message = "Failed to send Gemini context message", raw = "{}")
        )

        assertEquals(VoiceSessionStatus.Error("Failed to send Gemini context message"), session.state.value.session)
        assertEquals(1, audio.stopCaptureCalls)
        assertEquals(1, audio.suppressPlaybackCalls)
        assertEquals(1, gemini.closeCalls)
        conversationStore.awaitUpdateCount(2)
        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-async-error", tool.toolCallId)
        assertEquals("queued", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertFalse(toolApi.wasCancelled("call-async-error"))
        toolApi.complete(response(callId = "call-async-error", answer = "late answer"))
        conversationStore.awaitUpdateCount(3)
        audio.emitCapture(byteArrayOf(4, 5, 6))
        assertEquals(listOf(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))), gemini.audioMessages)
    }

    @Test
    fun `session records transition diagnostic when async Gemini error stops session`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            diagnostics = diagnostics,
            scope = this,
        )

        session.start()
        gemini.awaitConnect()

        gemini.eventHandlers.single()(
            GeminiLiveEvent.Error(message = "Gemini failed", raw = "{}")
        )

        assertEquals(VoiceSessionStatus.Error("Gemini failed"), session.state.value.session)
        assertEquals(1, audio.stopCaptureCalls)
        assertEquals(1, audio.suppressPlaybackCalls)
        assertEquals(1, gemini.closeCalls)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_transition_failed" &&
                    it.detail == "reason=gemini_error, closeGemini=true"
            }
        )
    }

    @Test
    fun `session records transition diagnostic when WebSocket close stops session`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val gemini = FakeGeminiLiveVoiceClient().apply {
            connectEvent = GeminiLiveEvent.WebSocketClosed(code = 1001, reason = "going away")
        }
        val audio = FakeVoiceAudioEngine()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            diagnostics = diagnostics,
            scope = this,
        )

        session.start()
        gemini.awaitConnect()

        assertEquals(
            VoiceSessionStatus.Error("Gemini WebSocket closed: 1001 going away"),
            session.state.value.session,
        )
        assertEquals(1, audio.stopCaptureCalls)
        assertEquals(1, audio.suppressPlaybackCalls)
        assertEquals(1, gemini.closeCalls)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_transition_failed" &&
                    it.detail == "reason=websocket_closed, closeGemini=true"
            }
        )
    }

    @Test
    fun `session records transition diagnostic when WebSocket failure stops session`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val gemini = FakeGeminiLiveVoiceClient()
        val blockedConnect = gemini.blockNextConnectCompletion()
        val audio = FakeVoiceAudioEngine()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            diagnostics = diagnostics,
            scope = this,
        )

        session.start()
        gemini.awaitConnect()

        gemini.eventHandlers.single()(
            GeminiLiveEvent.WebSocketFailure(message = "network dropped")
        )
        blockedConnect.release.complete(Unit)

        assertEquals(
            VoiceSessionStatus.Error("Gemini WebSocket failed: network dropped"),
            session.state.value.session,
        )
        assertEquals(1, audio.stopCaptureCalls)
        assertEquals(1, audio.suppressPlaybackCalls)
        assertEquals(1, gemini.closeCalls)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_transition_failed" &&
                    it.detail == "reason=websocket_failure, closeGemini=true"
            }
        )
    }

    @Test
    fun `startup failure during automatic reconnect is terminal`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            diagnostics = diagnostics,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 1),
            scope = this,
        )

        session.start()
        gemini.awaitConnectCount(1)
        sessionApi.failNextSession(IllegalStateException("token mint failed during reconnect"))

        gemini.eventHandlers.single()(GeminiLiveEvent.WebSocketFailure(message = "network dropped"))
        withTimeout(500) {
            while (session.state.value.session !is VoiceSessionStatus.Error) {
                delay(10)
            }
        }

        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(
            VoiceSessionStatus.Error("token mint failed during reconnect"),
            session.state.value.session,
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_transition_failed" &&
                    it.detail == "reason=startup_failure, closeGemini=false"
            }
        )
        assertEquals(1, diagnostics.events.value.count { it.name == "session_reconnect_scheduled" })
        assertEquals(1, diagnostics.events.value.count { it.name == "session_reconnect_attempting" })
        delay(50)
        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(
            VoiceSessionStatus.Error("token mint failed during reconnect"),
            session.state.value.session,
        )
        assertEquals(1, diagnostics.events.value.count { it.name == "session_reconnect_scheduled" })
        assertEquals(1, diagnostics.events.value.count { it.name == "session_reconnect_attempting" })
    }

    @Test
    fun `manual reconnect uses one cleanup sequence before new session starts`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            diagnostics = diagnostics,
            scope = this,
        )

        session.start()
        gemini.awaitConnectCount(1)

        val blockedNextSession = sessionApi.blockNextSession()
        session.reconnect()

        assertEquals(1, audio.stopCaptureCalls)
        assertEquals(1, audio.suppressPlaybackCalls)
        assertEquals(1, gemini.closeCalls)

        withTimeout(500) {
            blockedNextSession.started.await()
        }
        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(1, audio.stopCaptureCalls)
        assertEquals(1, audio.suppressPlaybackCalls)
        assertEquals(1, gemini.closeCalls)

        blockedNextSession.release.complete(Unit)
        gemini.awaitConnectCount(2)

        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(1, audio.stopCaptureCalls)
        assertEquals(1, audio.suppressPlaybackCalls)
        assertEquals(1, gemini.closeCalls)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_transition_manual_reconnect" &&
                    it.detail == "reason=manual_reconnect"
            }
        )
    }

    @Test
    fun `post connected WebSocket failure during connected publish automatically reconnects`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        var failureInjected = false
        diagnostics.addListener { event ->
            if (!failureInjected && event.name == "session_status" && event.detail == "connected") {
                failureInjected = true
                gemini.eventHandlers.single()(
                    GeminiLiveEvent.WebSocketFailure(message = "drop during connected publish")
                )
            }
        }
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            diagnostics = diagnostics,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 250),
            scope = this,
        )

        session.start()
        gemini.awaitConnectCount(1)
        delay(50)

        assertEquals(VoiceSessionStatus.Reconnecting, session.state.value.session)
        assertEquals(1, audio.startCaptureCalls)

        gemini.awaitConnectCount(2)

        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(2, audio.startCaptureCalls)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertNull(session.state.value.error)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_reconnect_scheduled" &&
                    it.detail == "reason=websocket_failure, attempt=1, maxAttempts=3, delayMs=250"
            }
        )
        assertFalse(
            diagnostics.events.value.any {
                it.name == "session_transition_failed" &&
                    it.detail == "reason=websocket_failure, closeGemini=true"
            }
        )
    }

    @Test
    fun `setup complete does not publish connected before resources activate`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val gemini = FakeGeminiLiveVoiceClient().also {
            it.connectEvent = GeminiLiveEvent.SetupComplete
        }
        val audio = FakeVoiceAudioEngine()
        val blockedStartCapture = audio.blockNextStartCapture()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            diagnostics = diagnostics,
            scope = sessionScope,
        )

        try {
            session.start()
            gemini.awaitConnect()
            assertTrue(blockedStartCapture.started.await(500, TimeUnit.MILLISECONDS))

            assertTrue(diagnostics.events.value.any { it.name == "gemini_setup_complete" })
            assertFalse(
                "Visible Connected must wait until connected resources activate",
                session.state.value.session == VoiceSessionStatus.Connected,
            )

            blockedStartCapture.release.countDown()
            withTimeout(500) {
                while (session.state.value.session != VoiceSessionStatus.Connected) {
                    delay(10)
                }
            }
            assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        } finally {
            blockedStartCapture.release.countDown()
            sessionScope.cancel()
        }
    }

    @Test
    fun `connected status waits until connected resources activate`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val blockedStartCapture = audio.blockNextStartCapture()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = sessionScope,
        )

        try {
            session.start()
            gemini.awaitConnect()
            assertTrue(blockedStartCapture.started.await(500, TimeUnit.MILLISECONDS))

            assertFalse(
                "Visible Connected must wait until capture startup completes",
                session.state.value.session == VoiceSessionStatus.Connected,
            )

            blockedStartCapture.release.countDown()
            withTimeout(500) {
                while (session.state.value.session != VoiceSessionStatus.Connected) {
                    delay(10)
                }
            }
            assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        } finally {
            blockedStartCapture.release.countDown()
            sessionScope.cancel()
        }
    }

    @Test
    fun `automatic reconnect cleanup does not hold reconnect state lock during blocking resource cleanup`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val blockedStopCapture = audio.blockNextStopCapture()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 250),
            scope = this,
        )

        session.start()
        gemini.awaitConnectCount(1)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        val firstCallback = gemini.eventHandlers.single()

        val firstFailure = launch(Dispatchers.Default) {
            firstCallback(GeminiLiveEvent.WebSocketFailure(message = "network dropped one"))
        }
        assertTrue(blockedStopCapture.started.await(500, TimeUnit.MILLISECONDS))

        val secondFailureReturned = CountDownLatch(1)
        val secondFailure = launch(Dispatchers.Default) {
            firstCallback(GeminiLiveEvent.WebSocketFailure(message = "network dropped two"))
            secondFailureReturned.countDown()
        }

        try {
            assertTrue(
                "Reconnect state decisions must not wait behind blocking cleanup",
                secondFailureReturned.await(100, TimeUnit.MILLISECONDS),
            )
        } finally {
            blockedStopCapture.release.countDown()
            firstFailure.join()
            secondFailure.join()
        }
    }

    @Test
    fun `manual reconnect waits for stale automatic cleanup before opening replacement session`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val blockedStopCapture = audio.blockNextStopCapture()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            diagnostics = diagnostics,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 250),
            scope = this,
        )

        session.start()
        gemini.awaitConnectCount(1)
        val firstCallback = gemini.eventHandlers.single()
        val automaticFailure = launch(Dispatchers.Default) {
            firstCallback(GeminiLiveEvent.WebSocketFailure(message = "network dropped"))
        }
        assertTrue(blockedStopCapture.started.await(500, TimeUnit.MILLISECONDS))

        val blockedManualSession = sessionApi.blockNextSession()
        val manualReconnect = launch(Dispatchers.Default) {
            session.reconnect()
        }
        try {
            delay(100)
            assertFalse(
                "Manual reconnect must not open a replacement session while stale cleanup is blocked",
                blockedManualSession.started.isCompleted,
            )
            assertEquals(1, sessionApi.createdSessions.size)
            assertEquals(1, gemini.eventHandlers.size)

            blockedStopCapture.release.countDown()
            automaticFailure.join()
            manualReconnect.join()
            withTimeout(500) {
                blockedManualSession.started.await()
            }
            assertEquals(2, sessionApi.createdSessions.size)
            assertEquals(1, audio.suppressPlaybackCalls)
            assertEquals(1, gemini.closeCalls)

            blockedManualSession.release.complete(Unit)
        } finally {
            blockedStopCapture.release.countDown()
            blockedManualSession.release.complete(Unit)
        }
        gemini.awaitConnectCount(2)
        delay(300)

        assertEquals(2, audio.stopCaptureCalls)
        assertEquals(1, audio.suppressPlaybackCalls)
        assertEquals(1, gemini.closeCalls)
        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(2, gemini.eventHandlers.size)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertEquals(0, diagnostics.events.value.count { it.name == "session_reconnect_attempting" })
    }

    @Test
    fun `session records startup failure diagnostic when capture fails after Gemini starts`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine().apply {
            startCaptureError = IllegalStateException("capture startup failed")
        }
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            diagnostics = diagnostics,
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        withTimeout(500) {
            while (session.state.value.session !is VoiceSessionStatus.Error) {
                delay(10)
            }
        }

        assertEquals(VoiceSessionStatus.Error("capture startup failed"), session.state.value.session)
        assertEquals(1, audio.stopCaptureCalls)
        assertEquals(1, audio.suppressPlaybackCalls)
        assertEquals(1, gemini.closeCalls)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_transition_failed" &&
                    it.detail == "reason=startup_failure, closeGemini=true"
            }
        )
    }

    @Test
    fun `session records startup failure diagnostic without Gemini close before Gemini starts`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = ThrowingVoiceAgentContextProvider(
                IllegalStateException("context startup failed")
            ),
            diagnostics = diagnostics,
            scope = this,
        )

        session.start()
        withTimeout(500) {
            while (session.state.value.session !is VoiceSessionStatus.Error) {
                delay(10)
            }
        }

        assertEquals(VoiceSessionStatus.Error("context startup failed"), session.state.value.session)
        assertEquals(0, gemini.eventHandlers.size)
        assertEquals(1, audio.stopCaptureCalls)
        assertEquals(1, audio.suppressPlaybackCalls)
        assertEquals(0, gemini.closeCalls)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_transition_failed" &&
                    it.detail == "reason=startup_failure, closeGemini=false"
            }
        )
    }

    @Test
    fun `session reconnect records transition diagnostic and preserves cleanup`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            diagnostics = diagnostics,
            scope = this,
        )

        session.start()
        gemini.awaitConnectCount(1)

        session.reconnect()
        gemini.awaitConnectCount(2)

        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(1, audio.stopCaptureCalls)
        assertEquals(1, audio.suppressPlaybackCalls)
        assertEquals(1, gemini.closeCalls)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_transition_manual_reconnect" &&
                    it.detail == "reason=manual_reconnect"
            }
        )
    }

    @Test
    fun `session reconnect marks in flight tool send stale before outbound invalidation waits`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val blockedSend = gemini.blockToolResponse("call-vm-reconnect-order")
        blockedSend.timeoutMillis = 5_000
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = toolApi,
            gemini = gemini,
            audio = audio,
            conversationStore = conversationStore,
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = CoroutineScope(coroutineContext + Dispatchers.Default),
        )

        session.start()
        gemini.awaitConnectCount(1)
        gemini.eventHandlers.single()(
            GeminiLiveEvent.ToolCall(callId = "call-vm-reconnect-order", name = "ask_hermes", prompt = "old")
        )
        assertEquals("call-vm-reconnect-order" to "old", toolApi.awaitRequest("call-vm-reconnect-order"))
        toolApi.complete(response(callId = "call-vm-reconnect-order", answer = "sent answer"))
        assertTrue(withContext(Dispatchers.Default) { blockedSend.started.await(500, TimeUnit.MILLISECONDS) })

        val reconnectJob = launch(Dispatchers.Default) {
            session.reconnect()
        }
        delay(50)
        blockedSend.release.countDown()
        withTimeout(500) {
            reconnectJob.join()
        }
        conversationStore.awaitHermesToolStatus("call-vm-reconnect-order", "complete")

        val toolStatuses = conversationStore.updatesSnapshot()
            .flatMap { it.currentMessages }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolCallId == "call-vm-reconnect-order" }
            .mapNotNull { it.metadata?.get("voice_tool_status")?.jsonPrimitive?.content }

        assertTrue(toolStatuses.toString(), "complete" in toolStatuses)
        assertTrue(toolStatuses.toString(), "failed" !in toolStatuses)
    }

    @Test
    fun `session reconnect suppresses stale capture frames before capture stops`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        audio.emitCapture(byteArrayOf(1, 2, 3))
        assertEquals(1, gemini.audioMessages.size)

        session.reconnect()
        audio.emitCapture(byteArrayOf(4, 5, 6))

        assertEquals(listOf(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))), gemini.audioMessages)
    }

    @Test
    fun `session reconnect cancels delayed old start before opening new Gemini session`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val blockedSession = sessionApi.blockNextSession()
        val gemini = FakeGeminiLiveVoiceClient()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = FakeVoiceAudioEngine(),
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = this,
        )

        session.start()
        withTimeout(500) {
            blockedSession.started.await()
        }

        session.reconnect()
        gemini.awaitConnectCount(1)

        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(1, gemini.eventHandlers.size)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
    }

    @Test
    fun `session end is terminal and later start does not reopen session`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = FakeVoiceAudioEngine(),
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = this,
        )

        session.start()
        gemini.awaitConnectCount(1)
        session.end()
        withTimeout(500) {
            while (gemini.closeCalls < 1) {
                delay(10)
            }
        }

        session.start()
        delay(50)

        assertEquals(1, gemini.eventHandlers.size)
        assertEquals(VoiceSessionStatus.Ended, session.state.value.session)
    }

    @Test
    fun `session ignores delayed Gemini callbacks from previous session after reconnect`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val toolApi = FakeVoiceToolApi()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = toolApi,
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = this,
        )

        session.start()
        gemini.awaitConnectCount(1)
        val oldCallback = gemini.eventHandlers.single()

        session.reconnect()
        gemini.awaitConnectCount(2)

        oldCallback(GeminiLiveEvent.ToolCall(callId = "stale-call", name = "ask_hermes", prompt = "stale"))
        delay(50)

        assertEquals(emptyList<Pair<String, String>>(), toolApi.requests)
        assertEquals(VoiceToolStatus.Idle, session.state.value.tool)
    }

    @Test
    fun `session ignores stale failure callback from previous Gemini session after reconnect`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            diagnostics = diagnostics,
            scope = this,
        )

        session.start()
        gemini.awaitConnectCount(1)
        val oldCallback = gemini.eventHandlers.single()

        session.reconnect()
        gemini.awaitConnectCount(2)
        val stopCaptureCallsAfterReconnect = audio.stopCaptureCalls
        val suppressPlaybackCallsAfterReconnect = audio.suppressPlaybackCalls
        val closeCallsAfterReconnect = gemini.closeCalls

        oldCallback(GeminiLiveEvent.WebSocketFailure(message = "stale failure"))
        delay(50)

        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertEquals(stopCaptureCallsAfterReconnect, audio.stopCaptureCalls)
        assertEquals(suppressPlaybackCallsAfterReconnect, audio.suppressPlaybackCalls)
        assertEquals(closeCallsAfterReconnect, gemini.closeCalls)
        assertFalse(
            diagnostics.events.value.any {
                it.name == "session_transition_failed" &&
                    it.detail.contains("stale failure")
            }
        )
        assertFalse(
            diagnostics.events.value.any {
                it.name == "session_transition_failed" &&
                    it.detail == "reason=websocket_failure, closeGemini=true"
            }
        )
    }

    @Test
    fun `session reconnect immediately invalidates previous Gemini callback`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        gemini.blockNextConnectCompletion()
        val toolApi = FakeVoiceToolApi()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = toolApi,
            gemini = gemini,
            audio = FakeVoiceAudioEngine(),
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = this,
        )

        session.start()
        gemini.awaitConnectCount(1)
        val oldCallback = gemini.eventHandlers.single()

        session.reconnect()
        oldCallback(GeminiLiveEvent.ToolCall(callId = "stale-reconnect", name = "ask_hermes", prompt = "stale"))
        delay(50)

        assertEquals(emptyList<Pair<String, String>>(), toolApi.requests)
        assertEquals(VoiceToolStatus.Idle, session.state.value.tool)
    }

    @Test
    fun `session reconnect invalidates previous Gemini callback while output audio is blocked`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val blockedPlayback = audio.blockNextPlayback()
        val toolApi = FakeVoiceToolApi()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = toolApi,
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = this,
        )

        session.start()
        gemini.awaitConnectCount(1)
        val oldCallback = gemini.eventHandlers.single()
        val eventJob = launch(Dispatchers.Default) {
            oldCallback(GeminiLiveEvent.OutputAudio("blocked-audio"))
        }
        assertTrue(blockedPlayback.started.await(500, TimeUnit.MILLISECONDS))

        session.reconnect()
        assertEquals(1, audio.suppressPlaybackCalls)
        oldCallback(GeminiLiveEvent.ToolCall(callId = "stale-blocked-reconnect", name = "ask_hermes", prompt = "stale"))
        delay(50)

        assertEquals(emptyList<Pair<String, String>>(), toolApi.requests)
        assertEquals(VoiceToolStatus.Idle, session.state.value.tool)

        blockedPlayback.release.countDown()
        withTimeout(500) {
            eventJob.join()
        }
        assertEquals(emptyList<String>(), audio.playedPcm16)
        assertFalse(session.state.value.audio == VoiceAudioStatus.AssistantSpeaking)
    }

    @Test
    fun `session reconnect does not persist stale tool send completion during close gap`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val blockedSend = gemini.blockToolResponse("call-vm-reconnect-sending")
        blockedSend.timeoutMillis = 5_000
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = toolApi,
            gemini = gemini,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            diagnostics = diagnostics,
            scope = CoroutineScope(coroutineContext + Dispatchers.Default),
        )

        session.start()
        gemini.awaitConnectCount(1)
        val oldCallback = gemini.eventHandlers.single()
        oldCallback(GeminiLiveEvent.ToolCall(callId = "call-vm-reconnect-sending", name = "ask_hermes", prompt = "old"))
        assertEquals("call-vm-reconnect-sending" to "old", toolApi.awaitRequest("call-vm-reconnect-sending"))
        toolApi.complete(response(callId = "call-vm-reconnect-sending", answer = "sent answer"))
        assertTrue(withContext(Dispatchers.Default) { blockedSend.started.await(500, TimeUnit.MILLISECONDS) })
        val closeHookCalled = CountDownLatch(1)
        gemini.onClose = {
            blockedSend.release.countDown()
            closeHookCalled.countDown()
        }

        session.reconnect()
        assertTrue(closeHookCalled.await(500, TimeUnit.MILLISECONDS))
        conversationStore.awaitHermesToolStatus("call-vm-reconnect-sending", "complete")

        val toolStatuses = conversationStore.updatesSnapshot()
            .flatMap { it.currentMessages }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolCallId == "call-vm-reconnect-sending" }
            .mapNotNull { it.metadata?.get("voice_tool_status")?.jsonPrimitive?.content }
        val diagnosticNames = diagnostics.events.value.map { it.name }

        assertTrue("statuses=$toolStatuses diagnostics=$diagnosticNames", "complete" in toolStatuses)
        assertTrue("statuses=$toolStatuses diagnostics=$diagnosticNames", "failed" !in toolStatuses)
    }

    @Test
    fun `session reconnect announces detached Hermes completion through new session bridge`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = toolApi,
            gemini = gemini,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = CoroutineScope(coroutineContext + Dispatchers.Default),
        )

        session.start()
        gemini.awaitConnectCount(1)
        val oldCallback = gemini.eventHandlers.single()
        oldCallback(GeminiLiveEvent.ToolCall(callId = "call-detached-complete", name = "ask_hermes", prompt = "old"))
        assertEquals("call-detached-complete" to "old", toolApi.awaitRequest("call-detached-complete"))
        val blockedNextSession = sessionApi.blockNextSession()

        session.reconnect()
        withTimeout(500) {
            blockedNextSession.started.await()
        }
        toolApi.complete(response(callId = "call-detached-complete", answer = "detached answer"))
        conversationStore.awaitHermesToolStatus("call-detached-complete", "complete")
        assertEquals(emptyList<Pair<Long?, String>>(), gemini.textTurns)

        blockedNextSession.release.complete(Unit)
        gemini.awaitConnectCount(2)
        withTimeout(500) {
            while (gemini.textTurns.none { it.first == 3L && it.second.contains("detached answer") }) {
                delay(10)
            }
        }

        assertTrue(
            gemini.textTurns.any { (sessionId, text) ->
                sessionId == 3L &&
                    text.contains("Original request:\nold") &&
                    text.contains("Hermes answer:\ndetached answer")
            }
        )
    }

    @Test
    fun `session closeNow Hermes job survives canceled session scope`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = toolApi,
            gemini = gemini,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = sessionScope,
        )

        session.start()
        gemini.awaitConnectCount(1)
        gemini.eventHandlers.single()(
            GeminiLiveEvent.ToolCall(callId = "call-service-scope-cancel", name = "ask_hermes", prompt = "survive")
        )
        assertEquals("call-service-scope-cancel" to "survive", toolApi.awaitRequest("call-service-scope-cancel"))

        session.closeNow()
        sessionScope.cancel()
        toolApi.complete(response(callId = "call-service-scope-cancel", answer = "survived scope cancellation"))
        conversationStore.awaitHermesToolStatus("call-service-scope-cancel", "complete")

        assertFalse(toolApi.wasCancelled("call-service-scope-cancel"))
        assertFalse(toolApi.wasRemoteCancelled("call-service-scope-cancel"))
    }

    @Test
    fun `session end persists pending tool as failed before Gemini close gap`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = toolApi,
            gemini = gemini,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = CoroutineScope(coroutineContext + Dispatchers.Default),
        )

        session.start()
        gemini.awaitConnectCount(1)
        val oldCallback = gemini.eventHandlers.single()
        oldCallback(GeminiLiveEvent.ToolCall(callId = "call-vm-end-pending", name = "ask_hermes", prompt = "end"))
        assertEquals("call-vm-end-pending" to "end", toolApi.awaitRequest("call-vm-end-pending"))
        val closeHookCalled = CountDownLatch(1)
        gemini.onClose = {
            toolApi.complete(response(callId = "call-vm-end-pending", answer = "late answer"))
            closeHookCalled.countDown()
        }

        session.end()
        assertTrue(closeHookCalled.await(500, TimeUnit.MILLISECONDS))
        conversationStore.awaitHermesToolStatus("call-vm-end-pending", "complete")

        val toolStatuses = conversationStore.updatesSnapshot()
            .flatMap { it.currentMessages }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolCallId == "call-vm-end-pending" }
            .mapNotNull { it.metadata?.get("voice_tool_status")?.jsonPrimitive?.content }

        assertTrue(toolStatuses.toString(), "complete" in toolStatuses)
        assertTrue(toolStatuses.toString(), "failed" !in toolStatuses)
    }

    @Test
    fun `session closeNow persists pending tool as failed before Gemini close gap`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = toolApi,
            gemini = gemini,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = CoroutineScope(coroutineContext + Dispatchers.Default),
        )

        session.start()
        gemini.awaitConnectCount(1)
        val oldCallback = gemini.eventHandlers.single()
        oldCallback(GeminiLiveEvent.ToolCall(callId = "call-vm-cleared-pending", name = "ask_hermes", prompt = "clear"))
        assertEquals("call-vm-cleared-pending" to "clear", toolApi.awaitRequest("call-vm-cleared-pending"))
        val closeHookCalled = CountDownLatch(1)
        gemini.onClose = {
            toolApi.complete(response(callId = "call-vm-cleared-pending", answer = "late answer"))
            closeHookCalled.countDown()
        }

        session.closeNow()
        assertTrue(closeHookCalled.await(500, TimeUnit.MILLISECONDS))
        conversationStore.awaitHermesToolStatus("call-vm-cleared-pending", "complete")

        val toolStatuses = conversationStore.updatesSnapshot()
            .flatMap { it.currentMessages }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolCallId == "call-vm-cleared-pending" }
            .mapNotNull { it.metadata?.get("voice_tool_status")?.jsonPrimitive?.content }

        assertTrue(toolStatuses.toString(), "complete" in toolStatuses)
        assertTrue(toolStatuses.toString(), "failed" !in toolStatuses)
    }

    @Test
    fun `session end immediately invalidates previous Gemini callback`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        gemini.blockNextConnectCompletion()
        val toolApi = FakeVoiceToolApi()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = toolApi,
            gemini = gemini,
            audio = FakeVoiceAudioEngine(),
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = this,
        )

        session.start()
        gemini.awaitConnectCount(1)
        val oldCallback = gemini.eventHandlers.single()

        session.end()
        oldCallback(GeminiLiveEvent.ToolCall(callId = "stale-end", name = "ask_hermes", prompt = "stale"))
        delay(50)

        assertEquals(emptyList<Pair<String, String>>(), toolApi.requests)
        assertEquals(VoiceToolStatus.Idle, session.state.value.tool)
    }

    private fun response(
        callId: String,
        answer: String,
        elapsedMs: Long? = null,
    ): MobileHermesResponse = MobileHermesResponse(
        callId = callId,
        answer = answer,
        model = "hermes-test",
        profileId = "profile",
        profileLabel = "Profile",
        elapsedMs = elapsedMs,
    )

    private fun jobPoll(
        callId: String,
        jobId: String,
        status: String,
    ): MobileHermesJobPollResponse = MobileHermesJobPollResponse(
        jobId = jobId,
        callId = callId,
        status = status,
        createdAt = "2026-06-11T00:00:00.000Z",
    )

    private fun queuedAck(callId: String): Pair<String, String> = callId to HERMES_QUEUED_ACKNOWLEDGEMENT

    private fun assertHermesAnswered(
        callId: String,
        status: VoiceToolStatus,
    ): VoiceToolStatus.HermesAnswered {
        assertTrue(
            "Expected HermesAnswered($callId) but was $status",
            status is VoiceToolStatus.HermesAnswered,
        )
        val answered = status as VoiceToolStatus.HermesAnswered
        assertEquals(callId, answered.callId)
        assertTrue("Expected non-negative elapsed time", answered.elapsedMs >= 0L)
        return answered
    }

    private fun assertHermesQueued(
        callId: String,
        status: VoiceToolStatus,
    ): VoiceToolStatus.QueuedHermes {
        assertTrue(
            "Expected QueuedHermes($callId) but was $status",
            status is VoiceToolStatus.QueuedHermes,
        )
        val queued = status as VoiceToolStatus.QueuedHermes
        assertEquals(callId, queued.callId)
        assertTrue("Expected non-empty job id", queued.jobId.isNotBlank())
        return queued
    }

    private suspend fun VoiceAgentCoordinator.awaitToolJobsWithTimeout() {
        withTimeout(500) {
            awaitToolJobs()
        }
    }

    private suspend fun VoiceAgentCoordinator.awaitPersistenceJobsWithTimeout() {
        withTimeout(500) {
            awaitPersistenceJobs()
        }
    }

    private fun List<UIMessagePart>.text(): String =
        filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.content.toInt()

    private suspend fun VoiceDiagnostics.awaitEvent(name: String) {
        withTimeout(500) {
            while (events.value.none { it.name == name }) {
                delay(10)
            }
        }
    }

    private suspend fun FakeVoiceConversationStore.awaitHermesToolStatus(callId: String, status: String) {
        withTimeout(500) {
            while (
                conversation.value.currentMessages
                    .flatMap { it.parts }
                    .filterIsInstance<UIMessagePart.Tool>()
                    .filter { it.toolCallId == callId }
                    .none { it.metadata?.get("voice_tool_status")?.jsonPrimitive?.content == status }
            ) {
                delay(10)
            }
        }
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)
}

private class ThrowingVoiceAgentContextProvider(
    private val error: Throwable,
) : VoiceAgentContextProvider {
    override fun build(conversation: Conversation): VoiceContext {
        throw error
    }
}
