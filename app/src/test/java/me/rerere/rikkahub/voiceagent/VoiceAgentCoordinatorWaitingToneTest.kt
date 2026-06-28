package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesJobPollResponse
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentCoordinatorWaitingToneTest {
    @Test
    fun `Hermes queued status starts waiting tone after grace`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val toolApi = FakeVoiceToolApi()
        val coordinator = coordinator(toolApi = toolApi, audio = audio)

        coordinator.onGeminiEvent(askHermes(callId = "call-tone"))
        assertEquals("call-tone" to "slow prompt", toolApi.awaitRequest("call-tone"))

        withTimeout(500) {
            while (audio.playedLocalCuePcm16.isEmpty()) {
                delay(10)
            }
        }

        assertEquals(emptyList<String>(), audio.playedPcm16)
        assertTrue(coordinator.state.value.tool is VoiceToolStatus.QueuedHermes)

        coordinator.closeAndDrain()
    }

    @Test
    fun `Hermes running status keeps waiting tone active until completion`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val toolApi = FakeVoiceToolApi()
        val coordinator = coordinator(toolApi = toolApi, audio = audio)

        coordinator.onGeminiEvent(askHermes(callId = "call-running"))
        assertEquals("call-running" to "slow prompt", toolApi.awaitRequest("call-running"))
        toolApi.scriptPoll(
            callId = "call-running",
            response = runningPoll(callId = "call-running"),
        )
        withTimeout(500) {
            while (coordinator.state.value.tool !is VoiceToolStatus.CallingHermes) {
                delay(10)
            }
        }
        withTimeout(500) {
            while (audio.playedLocalCuePcm16.isEmpty()) {
                delay(10)
            }
        }
        val cueCountAfterRunningCue = audio.playedLocalCuePcm16.size

        toolApi.complete(response(callId = "call-running", answer = "running answer"))
        withTimeout(500) {
            while (coordinator.state.value.toolCalls["call-running"] !is VoiceToolStatus.HermesAnswered) {
                delay(10)
            }
        }
        val cueCountAfterCompletion = audio.playedLocalCuePcm16.size
        delay(120)

        assertTrue(cueCountAfterCompletion >= cueCountAfterRunningCue)
        assertEquals(cueCountAfterCompletion, audio.playedLocalCuePcm16.size)

        coordinator.closeAndDrain()
    }

    @Test
    fun `Hermes completion before grace keeps waiting tone silent`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val toolApi = FakeVoiceToolApi()
        val coordinator = coordinator(toolApi = toolApi, audio = audio)

        coordinator.onGeminiEvent(askHermes(callId = "call-fast"))
        assertEquals("call-fast" to "slow prompt", toolApi.awaitRequest("call-fast"))
        toolApi.complete(response(callId = "call-fast", answer = "fast answer"))
        withTimeout(500) {
            while (coordinator.state.value.tool !is VoiceToolStatus.HermesAnswered) {
                delay(10)
            }
        }
        delay(80)

        assertEquals(emptyList<String>(), audio.playedLocalCuePcm16)

        coordinator.closeAndDrain()
    }

    @Test
    fun `assistant output audio suppresses active waiting tone`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val toolApi = FakeVoiceToolApi()
        val coordinator = coordinator(toolApi = toolApi, audio = audio)

        coordinator.onGeminiEvent(askHermes(callId = "call-suppress"))
        assertEquals("call-suppress" to "slow prompt", toolApi.awaitRequest("call-suppress"))
        withTimeout(500) {
            while (audio.playedLocalCuePcm16.isEmpty()) {
                delay(10)
            }
        }
        val cueCountAfterFirstCue = audio.playedLocalCuePcm16.size

        coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("AQID"))
        coordinator.onGeminiEvent(GeminiLiveEvent.GenerationComplete)
        toolApi.scriptPoll(
            callId = "call-suppress",
            response = runningPoll(callId = "call-suppress"),
        )
        withTimeout(500) {
            while (coordinator.state.value.toolCalls["call-suppress"] !is VoiceToolStatus.CallingHermes) {
                delay(10)
            }
        }
        delay(120)

        assertEquals(listOf("AQID"), audio.playedPcm16)
        assertEquals(cueCountAfterFirstCue, audio.playedLocalCuePcm16.size)

        coordinator.closeAndDrain()
    }

    @Test
    fun `user interruption keeps waiting tone silent across late Hermes updates`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val toolApi = FakeVoiceToolApi()
        val coordinator = coordinator(toolApi = toolApi, audio = audio)

        coordinator.onGeminiEvent(askHermes(callId = "call-interrupted"))
        assertEquals("call-interrupted" to "slow prompt", toolApi.awaitRequest("call-interrupted"))
        withTimeout(500) {
            while (audio.playedLocalCuePcm16.isEmpty()) {
                delay(10)
            }
        }
        val cueCountAfterFirstCue = audio.playedLocalCuePcm16.size

        coordinator.suppressPlayback()
        toolApi.scriptPoll(
            callId = "call-interrupted",
            response = runningPoll(callId = "call-interrupted"),
        )
        withTimeout(500) {
            while (coordinator.state.value.toolCalls["call-interrupted"] !is VoiceToolStatus.CallingHermes) {
                delay(10)
            }
        }
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("AQID"))
        delay(120)

        assertEquals(cueCountAfterFirstCue, audio.playedLocalCuePcm16.size)
        assertEquals(emptyList<String>(), audio.playedPcm16)

        coordinator.closeAndDrain()
    }

    @Test
    fun `close stops waiting tone and unregisters local cue handler`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val toolApi = FakeVoiceToolApi()
        val coordinator = coordinator(toolApi = toolApi, audio = audio)

        coordinator.onGeminiEvent(askHermes(callId = "call-close"))
        assertEquals("call-close" to "slow prompt", toolApi.awaitRequest("call-close"))
        withTimeout(500) {
            while (audio.playedLocalCuePcm16.isEmpty()) {
                delay(10)
            }
        }
        val cueCountAfterFirstCue = audio.playedLocalCuePcm16.size

        coordinator.close()
        toolApi.scriptPoll(
            callId = "call-close",
            response = runningPoll(callId = "call-close"),
        )
        audio.emitLocalCueError("after close")
        delay(120)

        assertEquals(cueCountAfterFirstCue, audio.playedLocalCuePcm16.size)
        assertTrue(audio.invalidateLocalCuePlaybackCalls > 0)
        assertEquals(1, audio.releaseCalls)
        assertEquals(emptyList<VoiceDiagnosticLine>(), coordinator.state.value.diagnostics.filter {
            it.name == "hermes_waiting_tone_failed" && it.detail == "after close"
        })

        coordinator.closeAndDrain()
    }

    @Test
    fun `cancellation stops waiting tone`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val toolApi = FakeVoiceToolApi()
        val coordinator = coordinator(toolApi = toolApi, audio = audio)

        coordinator.onGeminiEvent(askHermes(callId = "call-cancel-tone"))
        assertEquals("call-cancel-tone" to "slow prompt", toolApi.awaitRequest("call-cancel-tone"))
        withTimeout(500) {
            while (audio.playedLocalCuePcm16.isEmpty()) {
                delay(10)
            }
        }
        val cueCountAfterFirstCue = audio.playedLocalCuePcm16.size

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCallCancellation(listOf("call-cancel-tone")))
        delay(120)

        assertEquals(cueCountAfterFirstCue, audio.playedLocalCuePcm16.size)

        coordinator.closeAndDrain()
    }

    @Test
    fun `automatic reconnect prep stops waiting tone`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val toolApi = FakeVoiceToolApi()
        val coordinator = coordinator(toolApi = toolApi, audio = audio)

        coordinator.onGeminiEvent(askHermes(callId = "call-auto-reconnect"))
        assertEquals("call-auto-reconnect" to "slow prompt", toolApi.awaitRequest("call-auto-reconnect"))
        withTimeout(500) {
            while (audio.playedLocalCuePcm16.isEmpty()) {
                delay(10)
            }
        }
        val cueCountAfterFirstCue = audio.playedLocalCuePcm16.size

        coordinator.prepareForAutomaticReconnect()
        toolApi.scriptPoll(
            callId = "call-auto-reconnect",
            response = runningPoll(callId = "call-auto-reconnect"),
        )
        withTimeout(500) {
            while (coordinator.state.value.toolCalls["call-auto-reconnect"] !is VoiceToolStatus.CallingHermes) {
                delay(10)
            }
        }
        delay(120)

        assertEquals(cueCountAfterFirstCue, audio.playedLocalCuePcm16.size)
        assertTrue(audio.invalidateLocalCuePlaybackCalls > 0)

        coordinator.closeAndDrain()
    }

    @Test
    fun `connected session can restart waiting tone after automatic reconnect`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val toolApi = FakeVoiceToolApi()
        val coordinator = coordinator(toolApi = toolApi, audio = audio)

        coordinator.onGeminiEvent(askHermes(callId = "call-reconnected"))
        assertEquals("call-reconnected" to "slow prompt", toolApi.awaitRequest("call-reconnected"))
        withTimeout(500) {
            while (audio.playedLocalCuePcm16.isEmpty()) {
                delay(10)
            }
        }
        coordinator.prepareForAutomaticReconnect()
        val cueCountDuringReconnect = audio.playedLocalCuePcm16.size

        coordinator.updateSessionStatus(VoiceSessionStatus.Connected)
        withTimeout(500) {
            while (audio.playedLocalCuePcm16.size <= cueCountDuringReconnect) {
                delay(10)
            }
        }

        coordinator.closeAndDrain()
    }

    @Test
    fun `session end prep stops waiting tone`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val toolApi = FakeVoiceToolApi()
        val coordinator = coordinator(toolApi = toolApi, audio = audio)

        coordinator.onGeminiEvent(askHermes(callId = "call-session-end"))
        assertEquals("call-session-end" to "slow prompt", toolApi.awaitRequest("call-session-end"))
        withTimeout(500) {
            while (audio.playedLocalCuePcm16.isEmpty()) {
                delay(10)
            }
        }
        val cueCountAfterFirstCue = audio.playedLocalCuePcm16.size

        coordinator.prepareForSessionEnd()
        delay(120)

        assertEquals(cueCountAfterFirstCue, audio.playedLocalCuePcm16.size)
        assertTrue(audio.invalidateLocalCuePlaybackCalls > 0)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)

        coordinator.closeAndDrain()
    }

    @Test
    fun `overlapping Hermes calls keep waiting tone active until last call completes`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val toolApi = FakeVoiceToolApi()
        val coordinator = coordinator(toolApi = toolApi, audio = audio)

        coordinator.onGeminiEvent(askHermes(callId = "call-one"))
        assertEquals("call-one" to "slow prompt", toolApi.awaitRequest("call-one"))
        coordinator.onGeminiEvent(askHermes(callId = "call-two"))
        assertEquals("call-two" to "slow prompt", toolApi.awaitRequest("call-two"))
        withTimeout(500) {
            while (audio.playedLocalCuePcm16.isEmpty()) {
                delay(10)
            }
        }

        toolApi.complete(response(callId = "call-one", answer = "first answer"))
        withTimeout(500) {
            while (coordinator.state.value.toolCalls["call-one"] !is VoiceToolStatus.HermesAnswered) {
                delay(10)
            }
        }
        withTimeout(500) {
            while (audio.playedLocalCuePcm16.size < 2) {
                delay(10)
            }
        }

        toolApi.complete(response(callId = "call-two", answer = "second answer"))
        withTimeout(500) {
            while (coordinator.state.value.toolCalls["call-two"] !is VoiceToolStatus.HermesAnswered) {
                delay(10)
            }
        }
        val cueCountAfterBothComplete = audio.playedLocalCuePcm16.size
        delay(120)

        assertEquals(cueCountAfterBothComplete, audio.playedLocalCuePcm16.size)

        coordinator.closeAndDrain()
    }

    @Test
    fun `reconnect prep stops waiting tone`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val toolApi = FakeVoiceToolApi()
        val coordinator = coordinator(toolApi = toolApi, audio = audio)

        coordinator.onGeminiEvent(askHermes(callId = "call-reconnect"))
        assertEquals("call-reconnect" to "slow prompt", toolApi.awaitRequest("call-reconnect"))
        withTimeout(500) {
            while (audio.playedLocalCuePcm16.isEmpty()) {
                delay(10)
            }
        }
        val cueCountAfterFirstCue = audio.playedLocalCuePcm16.size

        coordinator.prepareForReconnect()
        delay(120)

        assertEquals(cueCountAfterFirstCue, audio.playedLocalCuePcm16.size)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)

        coordinator.closeAndDrain()
    }

    private fun CoroutineScope.coordinator(
        toolApi: FakeVoiceToolApi,
        audio: FakeVoiceAudioEngine,
    ): VoiceAgentCoordinator =
        VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = toolApi,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            hermesJobPollIntervalMs = 50L,
            hermesWaitingToneGraceDelayMs = 20L,
            hermesWaitingToneRepeatIntervalMs = 30L,
            scope = this,
        ).also { coordinator ->
            coordinator.updateSessionStatus(VoiceSessionStatus.Connected)
        }

    private fun askHermes(callId: String): GeminiLiveEvent.ToolCall =
        GeminiLiveEvent.ToolCall(
            callId = callId,
            name = VoiceAgentToolNames.ASK_HERMES,
            prompt = "slow prompt",
        )

    private fun response(callId: String, answer: String): MobileHermesResponse =
        MobileHermesResponse(
            callId = callId,
            answer = answer,
            model = "hermes-agent",
            profileId = "default",
            profileLabel = "Default",
            elapsedMs = 123,
        )

    private fun runningPoll(callId: String): MobileHermesJobPollResponse =
        MobileHermesJobPollResponse(
            jobId = "job-running",
            callId = callId,
            status = "running",
            createdAt = "2026-06-11T00:00:00.000Z",
        )

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)
}
