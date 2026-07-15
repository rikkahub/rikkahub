package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.hermesvoice.MobileHermesResponse
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class VoiceAgentPlaybackCoordinationTest {
    @Test
    fun `coordinator evaluates announcer lifecycle clock outside ownership lock`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val observations = mutableListOf<Pair<String, Boolean>>()
        var operation = "construction"
        var coordinator: VoiceAgentCoordinator? = null
        val subject = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            hermesAnnouncementNowMs = {
                coordinator?.let { owner ->
                    observations += operation to Thread.holdsLock(coordinatorToolJobsLock(owner))
                }
                observations.size.toLong()
            },
            scope = this,
            dispatcher = StandardTestDispatcher(),
        )
        coordinator = subject
        val sessionId = subject.nextSessionId()
        audio.activatePlaybackSession(sessionId)

        operation = "turn-active"
        subject.onGeminiEvent(sessionId, GeminiLiveEvent.InputTranscript("active"))
        operation = "turn-complete"
        subject.onGeminiEvent(sessionId, GeminiLiveEvent.TurnComplete)
        operation = "retirement"
        subject.invalidateActiveSession()

        assertTrue(observations.any { it.first == "turn-active" })
        assertTrue(observations.any { it.first == "turn-complete" })
        assertTrue(observations.any { it.first == "retirement" })
        assertTrue(
            "announcer clock ran under toolJobsLock: $observations",
            observations.all { (_, lockHeld) -> !lockHeld },
        )
    }

    @Test
    fun `unscoped rejected playback completion keeps Hermes final blocked`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val audioDelegate = FakeVoiceAudioEngine()
        val audio = object : VoiceAudioEngine by audioDelegate {
            override fun markPlaybackTurnComplete(sessionId: Long?): Boolean = false
        }
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            diagnostics = diagnostics,
            hermesAnnouncementQuietWindowMs = 0L,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("keep the turn active"))
        coordinator.onGeminiEvent(
            voiceToolCall(callId = "call-rejected", name = "ask_hermes", arg = "reject boundary")
        )
        assertEquals("call-rejected" to "reject boundary", toolApi.awaitRequest("call-rejected"))
        toolApi.complete(response(callId = "call-rejected", answer = "must stay queued"))
        coordinator.awaitToolJobsWithTimeout()

        coordinator.onGeminiEvent(GeminiLiveEvent.TurnComplete)
        delay(20)

        assertTrue(gemini.textTurns.isEmpty())
        assertTrue(
            diagnostics.events.value.any {
                it.name == "stale_gemini_turn_complete" &&
                    it.detail == "sessionId=none, playbackAccepted=false"
            }
        )
    }

    @Test
    fun `scoped rejected playback completion keeps Hermes final blocked`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val audioDelegate = FakeVoiceAudioEngine()
        val audio = object : VoiceAudioEngine by audioDelegate {
            override fun markPlaybackTurnComplete(sessionId: Long?): Boolean = false
        }
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            diagnostics = diagnostics,
            hermesAnnouncementQuietWindowMs = 0L,
            scope = this,
        )
        val sessionId = coordinator.nextSessionId()
        audioDelegate.activatePlaybackSession(sessionId)
        gemini.activateOutboundSession(sessionId)
        assertTrue(
            coordinator.attachHermesBridge(
                coordinator.createHermesSessionBridge(sessionId),
                sessionId = sessionId,
            )
        )

        coordinator.onGeminiEvent(sessionId, GeminiLiveEvent.InputTranscript("keep scoped turn active"))
        coordinator.onGeminiEvent(
            sessionId,
            voiceToolCall(callId = "call-scoped-rejected", name = "ask_hermes", arg = "reject scoped"),
        )
        assertEquals("call-scoped-rejected" to "reject scoped", toolApi.awaitRequest("call-scoped-rejected"))
        toolApi.complete(response(callId = "call-scoped-rejected", answer = "must stay scoped"))
        coordinator.awaitToolJobsWithTimeout()

        coordinator.onGeminiEvent(sessionId, GeminiLiveEvent.TurnComplete)
        delay(20)

        assertTrue(gemini.textTurns.isEmpty())
        assertTrue(
            diagnostics.events.value.any {
                it.name == "stale_gemini_turn_complete" &&
                    it.detail == "sessionId=$sessionId, playbackAccepted=false"
            }
        )
    }

    @Test
    fun `coordinator forwards exact playback epochs across multiple responses`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            diagnostics = diagnostics,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("response-one"))
        coordinator.onGeminiEvent(GeminiLiveEvent.TurnComplete)
        audio.completePlaybackDrain()
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("response-two"))
        coordinator.onGeminiEvent(GeminiLiveEvent.TurnComplete)
        audio.completePlaybackDrain()

        assertEquals(2, audio.markPlaybackTurnCompleteCalls)
        assertEquals(
            listOf(
                "voice_playback_active" to "generation=1",
                "voice_playback_drain_started" to "generation=1",
                "voice_playback_drained" to "generation=1",
                "voice_playback_active" to "generation=2",
                "voice_playback_drain_started" to "generation=2",
                "voice_playback_drained" to "generation=2",
            ),
            diagnostics.events.value
                .filter { it.name.startsWith("voice_playback_") }
                .map { it.name to it.detail },
        )
    }

    @Test
    fun `stale turn completion cannot close the current playback session`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            diagnostics = diagnostics,
            scope = this,
        )
        val staleSessionId = coordinator.nextSessionId()
        audio.activatePlaybackSession(staleSessionId)
        val currentSessionId = coordinator.nextSessionId()
        audio.activatePlaybackSession(currentSessionId)

        coordinator.onGeminiEvent(staleSessionId, GeminiLiveEvent.TurnComplete)
        assertEquals(0, audio.markPlaybackTurnCompleteCalls)

        coordinator.onGeminiEvent(currentSessionId, GeminiLiveEvent.TurnComplete)
        assertEquals(1, audio.markPlaybackTurnCompleteCalls)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "stale_gemini_event" && it.detail == "TurnComplete"
            }
        )
        assertTrue(
            diagnostics.events.value.none { it.name == "voice_playback_drain_started" }
        )
    }

    @Test
    fun `in flight stale turn completion cannot clear replacement session gates`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            hermesAnnouncementQuietWindowMs = 0L,
            scope = CoroutineScope(coroutineContext + Dispatchers.Default),
        )
        val oldSessionId = coordinator.nextSessionId()
        audio.activatePlaybackSession(oldSessionId)
        gemini.activateOutboundSession(oldSessionId)
        coordinator.attachHermesBridge(
            coordinator.createHermesSessionBridge(oldSessionId),
            sessionId = oldSessionId,
        )
        coordinator.onGeminiEvent(
            oldSessionId,
            voiceToolCall(callId = "call-race", name = "ask_hermes", arg = "race"),
        )
        assertEquals("call-race" to "race", toolApi.awaitRequest("call-race"))

        val blockedTurnComplete = audio.blockNextPlaybackTurnComplete()
        val staleCallback = launch(Dispatchers.Default) {
            coordinator.onGeminiEvent(oldSessionId, GeminiLiveEvent.TurnComplete)
        }
        assertTrue(blockedTurnComplete.started.await(500, TimeUnit.MILLISECONDS))

        coordinator.prepareFor(SessionTransition.Reconnect)
        audio.invalidatePlaybackSession()
        val replacementSessionId = coordinator.nextSessionId()
        audio.activatePlaybackSession(replacementSessionId)
        gemini.activateOutboundSession(replacementSessionId)
        coordinator.attachHermesBridge(
            coordinator.createHermesSessionBridge(replacementSessionId),
            sessionId = replacementSessionId,
        )
        coordinator.onGeminiEvent(
            replacementSessionId,
            GeminiLiveEvent.InputTranscript("replacement turn active"),
        )

        blockedTurnComplete.release.countDown()
        staleCallback.join()
        toolApi.complete(response(callId = "call-race", answer = "race answer"))
        coordinator.awaitToolJobsWithTimeout()
        delay(20)
        assertTrue(gemini.textTurns.isEmpty())

        coordinator.onGeminiEvent(replacementSessionId, GeminiLiveEvent.TurnComplete)
        awaitTextTurnCount(gemini, 1)
        assertEquals(replacementSessionId, gemini.textTurns.single().first)
    }

    @Test
    fun `coordinator release preserves the terminal playback drain callback`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            diagnostics = diagnostics,
            scope = this,
        )

        diagnostics.addListener { event ->
            if (event.name.startsWith("voice_playback_")) error("diagnostic listener failed")
        }
        assertTrue(audio.playPcm16("queued"))
        coordinator.close()

        assertTrue(
            diagnostics.events.value.any {
                it.name == "voice_playback_drained" && it.detail == "generation=1"
            }
        )
    }

    @Test
    fun `Hermes completion waits for turn complete and physical playback`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            hermesAnnouncementBlockedWatchdogMs = 60_000L,
            scope = this,
        )

        coordinator.onGeminiEvent(
            voiceToolCall(callId = "call-safe", name = "ask_hermes", arg = "safe prompt")
        )
        assertEquals("call-safe" to "safe prompt", toolApi.awaitRequest("call-safe"))
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("still-speaking"))
        toolApi.complete(response(callId = "call-safe", answer = "safe answer"))
        coordinator.awaitToolJobsWithTimeout()

        coordinator.onGeminiEvent(GeminiLiveEvent.TurnComplete)
        delay(20)
        assertTrue(gemini.textTurns.isEmpty())

        audio.completePlaybackDrain()
        withTimeout(2_000) {
            while (gemini.textTurns.isEmpty()) {
                delay(5)
            }
        }
        assertEquals(1, gemini.textTurns.size)
    }

    @Test
    fun `Gemini interruption does not release Hermes before the following turn completes`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            hermesAnnouncementQuietWindowMs = 0L,
            scope = this,
        )

        coordinator.onGeminiEvent(
            voiceToolCall(callId = "call-barge", name = "ask_hermes", arg = "barge prompt")
        )
        assertEquals("call-barge" to "barge prompt", toolApi.awaitRequest("call-barge"))
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("old-response"))
        toolApi.complete(response(callId = "call-barge", answer = "barge answer"))
        coordinator.awaitToolJobsWithTimeout()

        coordinator.onGeminiEvent(GeminiLiveEvent.Interrupted())
        audio.awaitSuppressPlaybackCalls(1)
        assertTrue(gemini.textTurns.isEmpty())

        coordinator.onGeminiEvent(GeminiLiveEvent.TurnComplete)
        assertTrue(gemini.textTurns.isEmpty())

        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("new user turn"))
        assertTrue(gemini.textTurns.isEmpty())

        coordinator.onGeminiEvent(GeminiLiveEvent.TurnComplete)
        awaitTextTurnCount(gemini, 1)
        assertEquals(1, gemini.textTurns.size)
    }

    @Test
    fun `scoped interruption requires the following response completion and drain`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            hermesAnnouncementQuietWindowMs = 0L,
            scope = this,
        )
        val sessionId = coordinator.nextSessionId()
        audio.activatePlaybackSession(sessionId)
        gemini.activateOutboundSession(sessionId)
        assertTrue(
            coordinator.attachHermesBridge(
                coordinator.createHermesSessionBridge(sessionId),
                sessionId = sessionId,
            )
        )

        coordinator.onGeminiEvent(
            sessionId,
            voiceToolCall(callId = "call-scoped-barge", name = "ask_hermes", arg = "scoped barge"),
        )
        assertEquals("call-scoped-barge" to "scoped barge", toolApi.awaitRequest("call-scoped-barge"))
        coordinator.onGeminiEvent(sessionId, GeminiLiveEvent.OutputAudio("old-scoped-response"))
        toolApi.complete(response(callId = "call-scoped-barge", answer = "scoped answer"))
        coordinator.awaitToolJobsWithTimeout()

        coordinator.onGeminiEvent(sessionId, GeminiLiveEvent.Interrupted())
        audio.awaitSuppressPlaybackCalls(1)
        coordinator.onGeminiEvent(sessionId, GeminiLiveEvent.TurnComplete)
        delay(20)
        assertTrue(gemini.textTurns.isEmpty())

        coordinator.onGeminiEvent(sessionId, GeminiLiveEvent.InputTranscript("new scoped input"))
        coordinator.onGeminiEvent(sessionId, GeminiLiveEvent.OutputAudio("new scoped response"))
        coordinator.onGeminiEvent(sessionId, GeminiLiveEvent.TurnComplete)
        delay(20)
        assertTrue(gemini.textTurns.isEmpty())

        audio.completePlaybackDrain()
        awaitTextTurnCount(gemini, 1)
        assertEquals(1, gemini.textTurns.size)
        assertEquals(sessionId, gemini.textTurns.single().first)
        assertTrue(gemini.textTurns.single().second.contains("scoped answer"))
    }

    @Test
    fun `session retirement clears the interrupted response boundary`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            diagnostics = diagnostics,
            scope = this,
        )
        val retiredSessionId = coordinator.nextSessionId()
        audio.activatePlaybackSession(retiredSessionId)
        coordinator.onGeminiEvent(retiredSessionId, GeminiLiveEvent.Interrupted())
        audio.awaitSuppressPlaybackCalls(1)

        coordinator.prepareFor(SessionTransition.Reconnect)
        audio.invalidatePlaybackSession()
        val replacementSessionId = coordinator.nextSessionId()
        audio.activatePlaybackSession(replacementSessionId)

        coordinator.onGeminiEvent(retiredSessionId, GeminiLiveEvent.TurnComplete)
        coordinator.onGeminiEvent(replacementSessionId, GeminiLiveEvent.TurnComplete)

        assertEquals(1, audio.markPlaybackTurnCompleteCalls)
        assertTrue(
            diagnostics.events.value.none {
                it.name == "gemini_interrupted_turn_complete_consumed" &&
                    it.detail == "sessionId=$replacementSessionId"
            }
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "stale_gemini_event" && it.detail == "TurnComplete"
            }
        )
    }

    private fun response(callId: String, answer: String): MobileHermesResponse = MobileHermesResponse(
        callId = callId,
        answer = answer,
        model = "hermes-test",
        profileId = "profile",
        profileLabel = "Profile",
    )

    private suspend fun VoiceAgentCoordinator.awaitToolJobsWithTimeout() {
        withTimeout(500) { awaitToolJobs() }
    }

    private suspend fun awaitTextTurnCount(gemini: FakeGeminiLiveVoiceClient, count: Int) {
        withTimeout(2_000) {
            while (gemini.textTurns.size < count) delay(5)
        }
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)
}

private fun coordinatorToolJobsLock(coordinator: VoiceAgentCoordinator): Any =
    VoiceAgentCoordinator::class.java.getDeclaredField("toolJobsLock").run {
        isAccessible = true
        requireNotNull(get(coordinator))
    }
