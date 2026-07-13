package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.telemetry.RecordedVoiceEvent
import me.rerere.rikkahub.voiceagent.telemetry.RecordingVoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceAgentReconnectTest {
    @Test
    fun `post connected WebSocket failure automatically reconnects without terminal error`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val observability = RecordingVoiceObservability()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            audio = audio,
            diagnostics = diagnostics,
            observability = observability,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 1),
        )

        session.start()
        gemini.awaitConnectCount(1)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        val firstCallback = gemini.eventHandlers.single()

        firstCallback(GeminiLiveEvent.WebSocketFailure(message = "network dropped"))
        gemini.awaitConnectCount(2)

        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(2, audio.startCaptureCalls)
        assertEquals(1, audio.stopCaptureCalls)
        assertEquals(1, audio.suppressPlaybackCalls)
        assertEquals(1, gemini.closeCalls)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertNull(session.state.value.error)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_reconnect_scheduled" &&
                    it.detail == "reason=websocket_failure, attempt=1, maxAttempts=3, delayMs=1"
            }
        )
        assertEquals(
            listOf(
                mapOf(
                    "sessionId" to 1L,
                    "session.reconnect.reason" to "websocket_failure",
                    "session.reconnect.attempt" to 1,
                    "session.reconnect.delay_ms" to 1L,
                )
            ),
            observability.events
                .filter { it.name == "hermes_voice.mobile.session.reconnect_scheduled" }
                .map { it.attributes },
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_reconnect_attempting" &&
                    it.detail == "attempt=1"
            }
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_reconnect_connected" &&
                    it.detail == "attempt=1"
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
    fun `post connected WebSocket close automatically reconnects without terminal error`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val observability = RecordingVoiceObservability()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            audio = audio,
            diagnostics = diagnostics,
            observability = observability,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 1),
        )

        session.start()
        gemini.awaitConnectCount(1)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        val firstCallback = gemini.eventHandlers.single()

        firstCallback(GeminiLiveEvent.WebSocketClosed(code = 1001, reason = "going away"))
        gemini.awaitConnectCount(2)

        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertNull(session.state.value.error)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "gemini_ws_closed" &&
                    it.detail == "code=1001, reason=going away"
            }
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_reconnect_scheduled" &&
                    it.detail == "reason=websocket_closed, attempt=1, maxAttempts=3, delayMs=1"
            }
        )
        assertEquals(
            listOf(
                mapOf(
                    "sessionId" to 1L,
                    "session.reconnect.reason" to "websocket_closed",
                    "session.reconnect.attempt" to 1,
                    "session.reconnect.delay_ms" to 1L,
                )
            ),
            observability.events
                .filter { it.name == "hermes_voice.mobile.session.reconnect_scheduled" }
                .map { it.attributes },
        )
        assertFalse(
            diagnostics.events.value.any {
                it.name == "session_transition_failed" &&
                    it.detail == "reason=websocket_closed, closeGemini=true"
            }
        )
    }

    @Test
    fun `post connected Gemini error remains terminal and does not auto reconnect`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            diagnostics = diagnostics,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 1),
        )

        session.start()
        gemini.awaitConnectCount(1)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        val firstCallback = gemini.eventHandlers.single()

        firstCallback(GeminiLiveEvent.Error(message = "Gemini protocol failed", raw = "{}"))

        assertEquals(1, sessionApi.createdSessions.size)
        assertEquals(VoiceSessionStatus.Error("Gemini protocol failed"), session.state.value.session)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_transition_failed" &&
                    it.detail == "reason=gemini_error, closeGemini=true"
            }
        )
        assertFalse(
            diagnostics.events.value.any {
                it.name == "session_reconnect_scheduled"
            }
        )
    }

    @Test
    fun `automatic reconnect exhaustion surfaces terminal WebSocket failure`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val observability = RecordingVoiceObservability()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            audio = audio,
            diagnostics = diagnostics,
            observability = observability,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 0, delayMs = 1),
        )

        session.start()
        gemini.awaitConnectCount(1)

        gemini.eventHandlers.single()(GeminiLiveEvent.WebSocketFailure(message = "drop-1"))

        assertEquals(1, sessionApi.createdSessions.size)
        assertEquals(VoiceSessionStatus.Error("Gemini WebSocket failed: drop-1"), session.state.value.session)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_reconnect_exhausted" &&
                    it.detail.contains("reason=websocket_failure") &&
                    it.detail.contains("attempts=0")
            }
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_transition_failed" &&
                    it.detail == "reason=websocket_failure, closeGemini=true"
            }
        )
        assertEquals(
            1,
            diagnostics.events.value.count {
                it.name == "gemini_ws_failure" && it.detail == "drop-1"
            },
        )
        delay(50)
        assertEquals(1, sessionApi.createdSessions.size)
        assertEquals(VoiceSessionStatus.Error("Gemini WebSocket failed: drop-1"), session.state.value.session)
        assertEquals(
            listOf(
                mapOf(
                    "sessionId" to 1L,
                    "modelId" to "gemini-flash",
                    "session.end_reason" to "websocket_failure",
                    "session.failure.kind" to "websocket_failure",
                    "session.failure.summary" to "drop-1",
                )
            ),
            observability.events
                .filter { it.name == "hermes_voice.mobile.session.failed" }
                .map { it.attributes },
        )
        assertEquals(0, diagnostics.events.value.count { it.name == "session_reconnect_scheduled" })
        assertEquals(0, diagnostics.events.value.count { it.name == "session_reconnect_attempting" })
    }

    @Test
    fun `successful automatic reconnect resets retry budget for later drop`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            diagnostics = diagnostics,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 1, delayMs = 1),
        )

        session.start()
        gemini.awaitConnectCount(1)

        gemini.eventHandlers[0](GeminiLiveEvent.WebSocketFailure(message = "first drop"))
        gemini.awaitConnectCount(2)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)

        gemini.eventHandlers[1](GeminiLiveEvent.WebSocketFailure(message = "second drop"))
        gemini.awaitConnectCount(3)

        assertEquals(3, sessionApi.createdSessions.size)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertEquals(
            2,
            diagnostics.events.value.count {
                it.name == "session_reconnect_scheduled" &&
                    it.detail == "reason=websocket_failure, attempt=1, maxAttempts=1, delayMs=1"
            },
        )
        assertFalse(diagnostics.events.value.any { it.name == "session_reconnect_exhausted" })
        assertFalse(session.state.value.session is VoiceSessionStatus.Error)
    }

    @Test
    fun `WebSocket failure during automatic reconnect connect schedules the next retry`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            diagnostics = diagnostics,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 250),
        )

        session.start()
        gemini.awaitConnectCount(1)
        val firstCallback = gemini.eventHandlers.single()
        gemini.connectEvent = GeminiLiveEvent.WebSocketFailure(message = "drop during reconnect connect")

        firstCallback(GeminiLiveEvent.WebSocketFailure(message = "first drop"))
        gemini.awaitConnectCount(2)
        withTimeout(500) {
            while (
                diagnostics.events.value.none {
                    it.name == "session_reconnect_scheduled" &&
                        it.detail == "reason=websocket_failure, attempt=2, maxAttempts=3, delayMs=250"
                }
            ) {
                delay(10)
            }
        }
        gemini.connectEvent = null
        delay(300)

        gemini.awaitConnectCount(3)
        assertEquals(3, sessionApi.createdSessions.size)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertNull(session.state.value.error)
        assertEquals(
            2,
            diagnostics.events.value.count { it.name == "session_reconnect_scheduled" },
        )
        assertFalse(
            diagnostics.events.value.any {
                it.name == "session_transition_failed" &&
                    it.detail == "reason=websocket_failure, closeGemini=true"
            }
        )
    }

    @Test
    fun `WebSocket failure during automatic reconnect connect exhaustion records terminal failure`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val observability = RecordingVoiceObservability()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            diagnostics = diagnostics,
            observability = observability,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 1, delayMs = 1),
        )

        session.start()
        gemini.awaitConnectCount(1)
        val firstCallback = gemini.eventHandlers.single()
        gemini.connectEvent = GeminiLiveEvent.WebSocketFailure(message = "drop during reconnect connect")

        firstCallback(GeminiLiveEvent.WebSocketFailure(message = "first drop"))
        gemini.awaitConnectCount(2)
        withTimeout(500) {
            while (observability.events.none { it.name == "hermes_voice.mobile.session.failed" }) {
                delay(10)
            }
        }

        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(
            VoiceSessionStatus.Error("Gemini WebSocket failed: drop during reconnect connect"),
            session.state.value.session,
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_reconnect_exhausted" &&
                    it.detail.contains("reason=websocket_failure") &&
                    it.detail.contains("attempts=1")
            }
        )
        assertEquals(
            mapOf(
                "modelId" to "gemini-flash",
                "session.end_reason" to "websocket_failure",
                "session.failure.kind" to "websocket_failure",
                "session.failure.summary" to "drop during reconnect connect",
            ),
            observability.events
                .single { it.name == "hermes_voice.mobile.session.failed" }
                .attributes
                .minus("sessionId"),
        )
        assertTrue(
            (observability.events
                .single { it.name == "hermes_voice.mobile.session.failed" }
                .attributes["sessionId"] as Long) > 1L
        )
    }

    @Test
    fun `WebSocket failure during automatic reconnect activation schedules next retry without publishing connected`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            audio = audio,
            diagnostics = diagnostics,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 250),
        )

        session.start()
        gemini.awaitConnectCount(1)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        val firstCallback = gemini.eventHandlers.single()
        gemini.activateOutboundSessionEvent =
            GeminiLiveEvent.WebSocketFailure(message = "drop during reconnect activation")

        firstCallback(GeminiLiveEvent.WebSocketFailure(message = "first drop"))
        gemini.awaitConnectCount(2)
        withTimeout(500) {
            while (
                diagnostics.events.value.none {
                    it.name == "session_reconnect_scheduled" &&
                        it.detail == "reason=websocket_failure, attempt=2, maxAttempts=3, delayMs=250"
                }
            ) {
                delay(10)
            }
        }

        assertEquals(1, audio.startCaptureCalls)
        assertEquals(VoiceSessionStatus.Reconnecting, session.state.value.session)
        assertFalse(
            diagnostics.events.value.any {
                it.name == "session_reconnect_connected" &&
                    it.detail == "attempt=1"
            }
        )

        gemini.activateOutboundSessionEvent = null
        delay(300)

        gemini.awaitConnectCount(3)
        assertEquals(3, sessionApi.createdSessions.size)
        assertEquals(2, audio.startCaptureCalls)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertNull(session.state.value.error)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_reconnect_connected" &&
                    it.detail == "attempt=2"
            }
        )
    }

    @Test
    fun `WebSocket failure during automatic reconnect activation exhaustion records terminal runtime failure`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val observability = RecordingVoiceObservability()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            diagnostics = diagnostics,
            observability = observability,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 1, delayMs = 1),
        )

        session.start()
        gemini.awaitConnectCount(1)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        val firstCallback = gemini.eventHandlers.single()
        gemini.activateOutboundSessionEvent =
            GeminiLiveEvent.WebSocketFailure(message = "drop during reconnect activation")

        firstCallback(GeminiLiveEvent.WebSocketFailure(message = "first drop"))
        gemini.awaitConnectCount(2)
        withTimeout(500) {
            while (observability.events.none { it.name == "hermes_voice.mobile.session.failed" }) {
                delay(10)
            }
        }

        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(
            VoiceSessionStatus.Error("Gemini WebSocket failed: drop during reconnect activation"),
            session.state.value.session,
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_reconnect_exhausted" &&
                    it.detail.contains("reason=websocket_failure") &&
                    it.detail.contains("attempts=1")
            }
        )
        assertEquals(
            mapOf(
                "modelId" to "gemini-flash",
                "session.end_reason" to "websocket_failure",
                "session.failure.kind" to "websocket_failure",
                "session.failure.summary" to "drop during reconnect activation",
            ),
            observability.events
                .single { it.name == "hermes_voice.mobile.session.failed" }
                .attributes
                .minus("sessionId"),
        )
    }

    @Test
    fun `manual reconnect cancels pending automatic reconnect and starts immediately`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            audio = audio,
            diagnostics = diagnostics,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 250),
        )

        session.start()
        gemini.awaitConnectCount(1)
        gemini.eventHandlers.single()(GeminiLiveEvent.WebSocketFailure(message = "network dropped"))
        assertEquals(VoiceSessionStatus.Reconnecting, session.state.value.session)

        session.reconnect()
        gemini.awaitConnectCount(2)

        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_reconnect_reset" &&
                    it.detail == "reason=manual_reconnect"
            }
        )
        delay(300)
        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(2, gemini.eventHandlers.size)
        assertEquals(0, diagnostics.events.value.count { it.name == "session_reconnect_attempting" })
    }

    @Test
    fun `end cancels pending automatic reconnect without reopening Gemini`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            diagnostics = diagnostics,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 250),
        )

        session.start()
        gemini.awaitConnectCount(1)
        gemini.eventHandlers.single()(GeminiLiveEvent.WebSocketFailure(message = "network dropped"))

        session.end()
        delay(300)

        assertEquals(1, sessionApi.createdSessions.size)
        assertEquals(1, gemini.eventHandlers.size)
        assertEquals(0, diagnostics.events.value.count { it.name == "session_reconnect_attempting" })
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_reconnect_cancelled" &&
                    it.detail == "reason=end"
            }
        )
    }

    @Test
    fun `closeNow cancels pending automatic reconnect without reopening Gemini`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            diagnostics = diagnostics,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 250),
        )

        session.start()
        gemini.awaitConnectCount(1)
        gemini.eventHandlers.single()(GeminiLiveEvent.WebSocketFailure(message = "network dropped"))

        session.closeNow()
        delay(300)

        assertEquals(1, sessionApi.createdSessions.size)
        assertEquals(1, gemini.eventHandlers.size)
        assertEquals(0, diagnostics.events.value.count { it.name == "session_reconnect_attempting" })
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_reconnect_cancelled" &&
                    it.detail == "reason=close"
            }
        )
    }

    @Test
    fun `concurrent WebSocket failures schedule one automatic reconnect`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val reconnectRaceBarrier = CountDownLatch(2)
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            audio = audio,
            diagnostics = diagnostics,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 250),
            nowMs = {
                reconnectRaceBarrier.countDown()
                reconnectRaceBarrier.await(1, TimeUnit.SECONDS)
                1_000L
            },
        )

        session.start()
        gemini.awaitConnectCount(1)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        val firstCallback = gemini.eventHandlers.single()
        val callbackStart = CountDownLatch(1)

        val firstFailure = launch(Dispatchers.Default) {
            callbackStart.await()
            firstCallback(GeminiLiveEvent.WebSocketFailure(message = "network dropped one"))
        }
        val secondFailure = launch(Dispatchers.Default) {
            callbackStart.await()
            firstCallback(GeminiLiveEvent.WebSocketFailure(message = "network dropped two"))
        }
        callbackStart.countDown()
        firstFailure.join()
        secondFailure.join()

        assertEquals(
            1,
            diagnostics.events.value.count { it.name == "session_reconnect_scheduled" },
        )
        assertEquals(VoiceSessionStatus.Reconnecting, session.state.value.session)

        gemini.awaitConnectCount(2)
        delay(300)

        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(2, gemini.eventHandlers.size)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertFalse(
            diagnostics.events.value.any {
                it.name == "session_transition_failed" &&
                    it.detail == "reason=websocket_failure, closeGemini=true"
            }
        )
    }

    @Test
    fun `automatic reconnect clears prior playback suppression for the new session`() = runTest {
        val observability = RecordingVoiceObservability()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            audio = audio,
            observability = observability,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 1),
        )

        session.start()
        gemini.awaitConnectCount(1)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        val firstCallback = gemini.eventHandlers.single()

        session.interrupt()
        audio.awaitSuppressPlaybackCalls(1)

        firstCallback(GeminiLiveEvent.OutputAudio("suppressed-audio"))
        assertTrue(audio.playedPcm16.isEmpty())
        assertTrue(
            observability.events
                .filter { it.name == "hermes_voice.mobile.audio.playback_queued" }
                .isEmpty()
        )

        firstCallback(GeminiLiveEvent.WebSocketFailure(message = "network dropped"))
        gemini.awaitConnectCount(2)
        val reconnectedSessionId = observability.awaitConnectedSessionId(count = 2)
        val secondCallback = gemini.eventHandlers.last()

        secondCallback(GeminiLiveEvent.OutputAudio("reconnected-audio"))

        assertEquals(listOf("reconnected-audio"), audio.playedPcm16)
        assertEquals(
            listOf(
                mapOf(
                    "sessionId" to reconnectedSessionId,
                    "audio.output.base64_chars" to "reconnected-audio".length,
                )
            ),
            observability.events
                .filter { it.name == "hermes_voice.mobile.audio.playback_queued" }
                .map { it.attributes },
        )
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertEquals(2, sessionApi.createdSessions.size)
    }

    @Test
    fun `stale output audio after reconnect is not played or recorded as queued`() = runTest {
        val observability = RecordingVoiceObservability()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            audio = audio,
            observability = observability,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 1),
        )

        session.start()
        gemini.awaitConnectCount(1)
        observability.awaitConnectedSessionId()
        val firstCallback = gemini.eventHandlers.single()
        val blockedPlayback = audio.blockNextPlayback()

        val staleAudioJob = launch(Dispatchers.Default) {
            firstCallback(GeminiLiveEvent.OutputAudio("stale-audio"))
        }
        assertTrue(blockedPlayback.started.await(500, TimeUnit.MILLISECONDS))

        session.reconnect()
        gemini.awaitConnectCount(2)
        blockedPlayback.release.countDown()
        staleAudioJob.join()

        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertTrue(audio.playedPcm16.isEmpty())
        assertTrue(
            observability.events
                .filter { it.name == "hermes_voice.mobile.audio.playback_queued" }
                .isEmpty()
        )
    }

    @Test
    fun `accepted output audio made stale by reconnect is not recorded as queued`() = runTest {
        val observability = RecordingVoiceObservability()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            audio = audio,
            observability = observability,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 1),
        )

        session.start()
        gemini.awaitConnectCount(1)
        observability.awaitConnectedSessionId()
        val firstCallback = gemini.eventHandlers.single()
        val blockedAfterAccepted = audio.blockAfterNextAcceptedPlayback()

        val staleAudioJob = launch(Dispatchers.Default) {
            firstCallback(GeminiLiveEvent.OutputAudio("accepted-stale-audio"))
        }
        assertTrue(blockedAfterAccepted.started.await(500, TimeUnit.MILLISECONDS))

        session.reconnect()
        gemini.awaitConnectCount(2)
        blockedAfterAccepted.release.countDown()
        staleAudioJob.join()

        assertEquals(listOf("accepted-stale-audio"), audio.playedPcm16)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertFalse(session.state.value.audio == VoiceAudioStatus.AssistantSpeaking)
        assertTrue(
            observability.events
                .filter { it.name == "hermes_voice.mobile.audio.playback_queued" }
                .isEmpty()
        )
    }

    @Test
    fun `accepted output audio suppressed by interrupt is not recorded as queued`() = runTest {
        val observability = RecordingVoiceObservability()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            audio = audio,
            observability = observability,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 1),
        )

        session.start()
        gemini.awaitConnectCount(1)
        observability.awaitConnectedSessionId()
        val firstCallback = gemini.eventHandlers.single()
        val blockedPlayback = audio.blockNextPlayback()

        val audioJob = launch(Dispatchers.Default) {
            firstCallback(GeminiLiveEvent.OutputAudio("interrupted-audio"))
        }
        assertTrue(blockedPlayback.started.await(500, TimeUnit.MILLISECONDS))

        session.interrupt()
        audio.awaitSuppressPlaybackCalls(1)
        blockedPlayback.release.countDown()
        audioJob.join()

        assertEquals(listOf("interrupted-audio"), audio.playedPcm16)
        assertEquals(VoiceAudioStatus.PlaybackSuppressed, session.state.value.audio)
        assertTrue(
            observability.events
                .filter { it.name == "hermes_voice.mobile.audio.playback_queued" }
                .isEmpty()
        )
    }

    @Test
    fun `stale WebSocket failure after automatic reconnect does not schedule another reconnect`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = reconnectSession(
            sessionApi = sessionApi,
            gemini = gemini,
            audio = audio,
            diagnostics = diagnostics,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 1),
        )

        session.start()
        gemini.awaitConnectCount(1)
        val oldCallback = gemini.eventHandlers.single()

        oldCallback(GeminiLiveEvent.WebSocketFailure(message = "first drop"))
        gemini.awaitConnectCount(2)
        val scheduledAfterReconnect = diagnostics.events.value.count {
            it.name == "session_reconnect_scheduled"
        }

        oldCallback(GeminiLiveEvent.WebSocketFailure(message = "stale drop"))
        delay(50)

        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(VoiceSessionStatus.Connected, session.state.value.session)
        assertEquals(
            scheduledAfterReconnect,
            diagnostics.events.value.count { it.name == "session_reconnect_scheduled" },
        )
        assertTrue(diagnostics.events.value.any { it.name == "stale_gemini_event" })
    }

    private fun CoroutineScope.reconnectSession(
        sessionApi: FakeVoiceSessionApi = FakeVoiceSessionApi(),
        gemini: FakeGeminiLiveVoiceClient = FakeGeminiLiveVoiceClient(),
        audio: FakeVoiceAudioEngine = FakeVoiceAudioEngine(),
        diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
        observability: RecordingVoiceObservability = RecordingVoiceObservability(),
        reconnectPolicy: VoiceReconnectPolicy = fastReconnectPolicy(),
        nowMs: () -> Long = { System.currentTimeMillis() },
    ): VoiceAgentCallSession =
        VoiceAgentCallSession(
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
            observability = observability,
            reconnectPolicy = reconnectPolicy,
            nowMs = nowMs,
            scope = this,
        )

    private suspend fun RecordingVoiceObservability.awaitConnectedSessionId(count: Int = 1): Long =
        awaitEvent(name = "hermes_voice.mobile.session.connected", count = count).attributes["sessionId"] as Long

    private suspend fun RecordingVoiceObservability.awaitEvent(name: String, count: Int = 1): RecordedVoiceEvent =
        withTimeout(500) {
            while (events.count { it.name == name } < count) {
                delay(10)
            }
            events.filter { it.name == name }[count - 1]
        }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)
}
