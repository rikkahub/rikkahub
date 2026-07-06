package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.voiceagent.gemini.GeminiContentTurn
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.telemetry.RecordingVoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import me.rerere.rikkahub.voiceagent.telemetry.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VoiceAgentCallSessionTest {
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
        assertEquals(1, audio.startCaptureCalls)

        audio.emitCapture(byteArrayOf(1, 2, 3))
        assertEquals(listOf(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))), gemini.audioMessages)

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
    fun `end and drain waits for final transcript persistence`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val conversationStore = FakeVoiceConversationStore()
        val observability = RecordingVoiceObservability()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            observability = observability,
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        gemini.eventHandlers.single()(me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent.InputTranscript("hello"))

        session.endAndDrain()

        val userTranscript = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .single { it.text == "hello" }
        assertEquals("session-closed-before-final", userTranscript.metadata?.get("voice_status")?.jsonPrimitive?.content)
        assertEquals(VoiceSessionStatus.Ended, session.state.value.session)
        assertEquals(
            listOf(
                mapOf(
                    "sessionId" to 1L,
                    "modelId" to "gemini-flash",
                    "session.end_reason" to "user_end",
                    "session.failure.kind" to "none",
                )
            ),
            observability.events
                .filter { it.name == "voicelab.mobile.session.ended" }
                .map { it.attributes },
        )
    }

    @Test
    fun `session records transcript observability events with raw text`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val observability = RecordingVoiceObservability()
        val trace = VoiceTraceContext(traceId = "trace-123", voiceSessionId = "session-456")
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
            observability = observability,
            traceContext = trace,
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        gemini.eventHandlers.single()(GeminiLiveEvent.InputTranscript("hello user"))
        gemini.eventHandlers.single()(GeminiLiveEvent.OutputTranscript("hello assistant"))
        gemini.eventHandlers.single()(GeminiLiveEvent.GenerationComplete)

        session.endAndDrain()

        assertEquals(
            listOf(
                mapOf(
                    "turnId" to "user-1",
                    "text.chars" to 10,
                )
            ),
            observability.events
                .filter { it.name == "voicelab.mobile.transcript.input_delta" }
                .map { it.attributes },
        )
        assertEquals(
            listOf(
                mapOf(
                    "turnId" to "assistant-2",
                    "text.chars" to 15,
                )
            ),
            observability.events
                .filter { it.name == "voicelab.mobile.transcript.output_delta" }
                .map { it.attributes },
        )
        assertEquals(
            listOf(
                mapOf(
                    "turnId" to "user-1",
                    "speaker" to "user",
                    "status" to "session-closed-before-final",
                    "voice.user_transcript" to "hello user",
                    "voice.user_transcript.truncated" to false,
                    "voice.user_transcript.chars" to 10,
                    "voice.user_transcript.sha256" to sha256Hex("hello user"),
                )
            ),
            observability.events
                .filter { it.name == "voicelab.mobile.transcript.user_final" }
                .map { it.attributes },
        )
        assertEquals(
            listOf(
                mapOf(
                    "turnId" to "assistant-2",
                    "speaker" to "assistant",
                    "status" to "complete",
                    "gemini.output_transcript" to "hello assistant",
                    "gemini.output_transcript.truncated" to false,
                    "gemini.output_transcript.chars" to 15,
                    "gemini.output_transcript.sha256" to sha256Hex("hello assistant"),
                )
            ),
            observability.events
                .filter { it.name == "voicelab.mobile.transcript.assistant_final" }
                .map { it.attributes },
        )
        val expectedTranscriptTurns = listOf(
            mapOf(
                "turnId" to "assistant-2",
                "speaker" to "assistant",
                "status" to "complete",
                "gemini.output_transcript" to "hello assistant",
                "gemini.output_transcript.truncated" to false,
                "gemini.output_transcript.chars" to 15,
                "gemini.output_transcript.sha256" to sha256Hex("hello assistant"),
            ),
            mapOf(
                "turnId" to "user-1",
                "speaker" to "user",
                "status" to "session-closed-before-final",
                "voice.user_transcript" to "hello user",
                "voice.user_transcript.truncated" to false,
                "voice.user_transcript.chars" to 10,
                "voice.user_transcript.sha256" to sha256Hex("hello user"),
            ),
        )
        assertEquals(
            expectedTranscriptTurns.sortedBy { it["turnId"].toString() },
            observability.events
                .filter { it.name == "voicelab.mobile.transcript.turn" }
                .map { it.attributes }
                .sortedBy { it["turnId"].toString() },
        )
        assertTrue(
            observability.events
                .filter { it.name.startsWith("voicelab.mobile.transcript.") }
                .all { it.trace == trace }
        )
        val transcriptTelemetry = observability.events
            .filter {
                it.name == "voicelab.mobile.transcript.input_delta" ||
                    it.name == "voicelab.mobile.transcript.output_delta"
            }
            .joinToString(separator = "\n") { it.attributes.toString() }
        assertFalse(transcriptTelemetry.contains("hello user"))
        assertFalse(transcriptTelemetry.contains("hello assistant"))
        assertFalse(transcriptTelemetry.contains("voice.user_transcript"))
        assertFalse(transcriptTelemetry.contains("gemini.output_transcript"))
        assertFalse(transcriptTelemetry.contains("sha256"))
    }

    @Test
    fun `assistant final telemetry is emitted once when close recovers failed final persistence`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val conversationStore = FakeVoiceConversationStore()
        val observability = RecordingVoiceObservability()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            observability = observability,
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        gemini.eventHandlers.single()(GeminiLiveEvent.OutputTranscript("recovered assistant"))
        conversationStore.awaitTextUpdate("recovered assistant")
        conversationStore.failNextUpdate()
        gemini.eventHandlers.single()(GeminiLiveEvent.GenerationComplete)

        session.endAndDrain()

        val expected = listOf(
            mapOf(
                "turnId" to "assistant-1",
                "speaker" to "assistant",
                "status" to "complete",
                "gemini.output_transcript" to "recovered assistant",
                "gemini.output_transcript.truncated" to false,
                "gemini.output_transcript.chars" to 19,
                "gemini.output_transcript.sha256" to sha256Hex("recovered assistant"),
            )
        )
        assertEquals(
            expected,
            observability.events
                .filter { it.name == "voicelab.mobile.transcript.assistant_final" }
                .map { it.attributes },
        )
        assertEquals(
            expected,
            observability.events
                .filter {
                    it.name == "voicelab.mobile.transcript.turn" &&
                        it.attributes["speaker"] == "assistant"
                }
                .map { it.attributes },
        )
    }

    @Test
    fun `session close records assistant final as session closed before final`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val observability = RecordingVoiceObservability()
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
            observability = observability,
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        gemini.eventHandlers.single()(GeminiLiveEvent.OutputTranscript("unfinished assistant"))

        session.endAndDrain()

        val assistantFinal = observability.events
            .filter { it.name == "voicelab.mobile.transcript.assistant_final" }
            .map { it.attributes }
        assertEquals(
            listOf(
                mapOf(
                    "turnId" to "assistant-1",
                    "speaker" to "assistant",
                    "status" to "session-closed-before-final",
                    "gemini.output_transcript" to "unfinished assistant",
                    "gemini.output_transcript.truncated" to false,
                    "gemini.output_transcript.chars" to 20,
                    "gemini.output_transcript.sha256" to sha256Hex("unfinished assistant"),
                )
            ),
            assistantFinal,
        )
    }

    @Test
    fun `interrupted assistant final remains interrupted and deduped when session later closes`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val observability = RecordingVoiceObservability()
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
            observability = observability,
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        gemini.eventHandlers.single()(GeminiLiveEvent.OutputTranscript("partial assistant"))
        gemini.eventHandlers.single()(GeminiLiveEvent.Interrupted())
        gemini.eventHandlers.single()(GeminiLiveEvent.InputTranscript("next user turn"))

        session.endAndDrain()

        val assistantFinal = observability.events
            .filter { it.name == "voicelab.mobile.transcript.assistant_final" }
            .map { it.attributes }
        val assistantTurns = observability.events
            .filter { event ->
                event.name == "voicelab.mobile.transcript.turn" &&
                    event.attributes["speaker"] == "assistant"
            }
            .map { it.attributes }
        val expected = listOf(
            mapOf(
                "turnId" to "assistant-1",
                "speaker" to "assistant",
                "status" to "interrupted",
                "gemini.output_transcript" to "partial assistant",
                "gemini.output_transcript.truncated" to false,
                "gemini.output_transcript.chars" to 17,
                "gemini.output_transcript.sha256" to sha256Hex("partial assistant"),
            )
        )
        assertEquals(expected, assistantFinal)
        assertEquals(expected, assistantTurns)
    }

    @Test
    fun `final transcript telemetry is not emitted when persistence fails`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val conversationStore = FakeVoiceConversationStore()
        val observability = RecordingVoiceObservability()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            observability = observability,
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        gemini.eventHandlers.single()(GeminiLiveEvent.OutputTranscript("lost assistant"))
        conversationStore.awaitTextUpdate("lost assistant")
        conversationStore.failNextUpdate()
        conversationStore.failNextUpdate()
        gemini.eventHandlers.single()(GeminiLiveEvent.GenerationComplete)

        session.endAndDrain()

        assertEquals(
            emptyList<Map<String, Any?>>(),
            observability.events
                .filter {
                    it.name == "voicelab.mobile.transcript.assistant_final" ||
                        it.name == "voicelab.mobile.transcript.turn"
                }
                .map { it.attributes },
        )
    }

    @Test
    fun `debug injection completion stops capture and closes Gemini audio stream`() = runTest {
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
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        audio.completeDebugInjection()

        assertEquals(1, audio.stopCaptureCalls)
        assertEquals(listOf(1L), gemini.audioStreamEndSessionIds)
    }

    @Test
    fun `debug injection can resume capture after model generation completes`() = runTest {
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
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        assertEquals(1, audio.startCaptureCalls)

        audio.completeDebugInjection()
        assertEquals(1, audio.stopCaptureCalls)

        gemini.eventHandlers.single()(GeminiLiveEvent.GenerationComplete)

        assertEquals(2, audio.startCaptureCalls)
    }

    @Test
    fun `audio capture records started muted and unmuted observability events`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val observability = RecordingVoiceObservability()
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
            observability = observability,
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        withTimeout(500) {
            while (session.state.value.session != VoiceSessionStatus.Connected) {
                delay(10)
            }
        }
        session.setMuted(true)
        session.setMuted(false)

        assertEquals(
            listOf(
                mapOf("sessionId" to 1L, "audio.muted" to false),
                mapOf("sessionId" to 1L, "audio.muted" to false),
            ),
            observability.events
                .filter { it.name == "voicelab.mobile.audio.capture_started" }
                .map { it.attributes },
        )
        assertEquals(
            listOf(mapOf("sessionId" to 1L)),
            observability.events
                .filter { it.name == "voicelab.mobile.audio.capture_muted" }
                .map { it.attributes },
        )
        assertEquals(
            listOf(mapOf("sessionId" to 1L)),
            observability.events
                .filter { it.name == "voicelab.mobile.audio.capture_unmuted" }
                .map { it.attributes },
        )
    }

    @Test
    fun `startup failure records session failed observability event`() = runTest {
        val sessionApi = FakeVoiceSessionApi().apply {
            failNextSession(IllegalStateException("token service unavailable"))
        }
        val observability = RecordingVoiceObservability()
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
            observability = observability,
            scope = this,
        )

        session.start()
        withTimeout(500) {
            while (session.state.value.session !is VoiceSessionStatus.Error) {
                delay(10)
            }
        }

        assertEquals(
            listOf(
                mapOf(
                    "sessionId" to 1L,
                    "modelId" to "gemini-flash",
                    "session.end_reason" to "startup_failure",
                    "session.failure.kind" to "startup",
                    "session.failure.summary" to "token service unavailable",
                )
            ),
            observability.events
                .filter { it.name == "voicelab.mobile.session.failed" }
                .map { it.attributes },
        )
    }

    @Test
    fun `Gemini connect error records startup session failed observability event once`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient().apply {
            connectEvent = GeminiLiveEvent.Error(message = "connect rejected", raw = "connect rejected")
        }
        val observability = RecordingVoiceObservability()
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
            observability = observability,
            scope = this,
        )

        session.start()
        withTimeout(500) {
            while (observability.events.none { it.name == "voicelab.mobile.session.failed" }) {
                delay(10)
            }
        }

        assertEquals(
            listOf(
                mapOf(
                    "sessionId" to 1L,
                    "modelId" to "gemini-flash",
                    "session.end_reason" to "startup_failure",
                    "session.failure.kind" to "startup",
                    "session.failure.summary" to "connect rejected",
                )
            ),
            observability.events
                .filter { it.name == "voicelab.mobile.session.failed" }
                .map { it.attributes },
        )
    }

    @Test
    fun `session failure observability redacts credentials from summary`() = runTest {
        val privateSummary =
            "connect failed https://example.test/live?access_token=secret-token " +
                "Authorization: Bearer private-token github_pat_11_private_token " +
                "api_key=api-secret password=password-secret secret=plain-secret token=token-secret " +
                "secretToken: secret-token-2 \"apiKey\":\"json-api-secret\" client_secret: client-secret " +
                "refresh_token=refresh-secret \"Authorization\":\"Bearer json-bearer-token\" " +
                "\"Authorization\": \"Basic json-basic-token\" glpat-private-token"
        val gemini = FakeGeminiLiveVoiceClient().apply {
            connectEvent = GeminiLiveEvent.Error(message = privateSummary, raw = privateSummary)
        }
        val observability = RecordingVoiceObservability()
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
            observability = observability,
            scope = this,
        )

        session.start()
        withTimeout(500) {
            while (observability.events.none { it.name == "voicelab.mobile.session.failed" }) {
                delay(10)
            }
        }

        val summary = observability.events
            .single { it.name == "voicelab.mobile.session.failed" }
            .attributes["session.failure.summary"]
            .toString()
        assertTrue(summary.contains("<redacted-url>"))
        assertTrue(summary.contains("Authorization: Bearer <redacted>"))
        assertFalse(summary, summary.contains("secret-token"))
        assertFalse(summary, summary.contains("private-token"))
        assertFalse(summary, summary.contains("github_pat_11_private_token"))
        assertFalse(summary, summary.contains("api-secret"))
        assertFalse(summary, summary.contains("password-secret"))
        assertFalse(summary, summary.contains("plain-secret"))
        assertFalse(summary, summary.contains("token-secret"))
        assertFalse(summary, summary.contains("secret-token-2"))
        assertFalse(summary, summary.contains("json-api-secret"))
        assertFalse(summary, summary.contains("client-secret"))
        assertFalse(summary, summary.contains("refresh-secret"))
        assertFalse(summary, summary.contains("json-bearer-token"))
        assertFalse(summary, summary.contains("json-basic-token"))
        assertFalse(summary, summary.contains("glpat-private-token"))
    }

    @Test
    fun `activation failure before connected records startup failure kind`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient().apply {
            activateOutboundSessionEvent = GeminiLiveEvent.WebSocketFailure(message = "pre-setup drop")
        }
        val observability = RecordingVoiceObservability()
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
            observability = observability,
            reconnectPolicy = VoiceReconnectPolicy(maxAttempts = 0),
            scope = this,
        )

        session.start()
        withTimeout(500) {
            while (observability.events.none { it.name == "voicelab.mobile.session.failed" }) {
                delay(10)
            }
        }

        assertEquals(
            listOf(
                mapOf(
                    "sessionId" to 1L,
                    "modelId" to "gemini-flash",
                    "session.end_reason" to "startup_failure",
                    "session.failure.kind" to "startup",
                    "session.failure.summary" to "pre-setup drop",
                )
            ),
            observability.events
                .filter { it.name == "voicelab.mobile.session.failed" }
                .map { it.attributes },
        )
    }

    @Test
    fun `terminal Gemini runtime error records session failed observability event once`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val observability = RecordingVoiceObservability()
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
            observability = observability,
            reconnectPolicy = VoiceReconnectPolicy(maxAttempts = 0),
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        withTimeout(500) {
            while (session.state.value.session != VoiceSessionStatus.Connected) {
                delay(10)
            }
        }
        gemini.eventHandlers.single()(
            GeminiLiveEvent.Error(message = "model rejected request", raw = "model rejected request")
        )

        withTimeout(500) {
            while (observability.events.none { it.name == "voicelab.mobile.session.failed" }) {
                delay(10)
            }
        }

        assertEquals(
            listOf(
                mapOf(
                    "sessionId" to 1L,
                    "modelId" to "gemini-flash",
                    "session.end_reason" to "gemini_error",
                    "session.failure.kind" to "gemini_error",
                    "session.failure.summary" to "model rejected request",
                )
            ),
            observability.events
                .filter { it.name == "voicelab.mobile.session.failed" }
                .map { it.attributes },
        )
        assertFalse(
            observability.events.any { it.name == "voicelab.mobile.session.reconnect_scheduled" }
        )
    }

    @Test
    fun `end records session ended observability event`() = runTest {
        val observability = RecordingVoiceObservability()
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
            observability = observability,
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        session.end()
        withTimeout(500) {
            while (audio.releaseCalls < 1) {
                delay(10)
            }
        }

        assertEquals(
            listOf(
                mapOf(
                    "sessionId" to 1L,
                    "modelId" to "gemini-flash",
                    "session.end_reason" to "user_end",
                    "session.failure.kind" to "none",
                )
            ),
            observability.events
                .filter { it.name == "voicelab.mobile.session.ended" }
                .map { it.attributes },
        )
    }

    @Test
    fun `close now records session ended observability event`() = runTest {
        val observability = RecordingVoiceObservability()
        val trace = VoiceTraceContext(traceId = "trace-123", voiceSessionId = "session-456")
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = FakeGeminiLiveVoiceClient(),
            audio = FakeVoiceAudioEngine(),
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            observability = observability,
            traceContext = trace,
            scope = this,
        )

        session.start()
        session.closeNow()
        session.closeNow()

        assertEquals(
            listOf(
                mapOf(
                    "sessionId" to 1L,
                    "modelId" to "gemini-flash",
                    "session.end_reason" to "close_now",
                    "session.failure.kind" to "none",
                )
            ),
            observability.events
                .filter { it.name == "voicelab.mobile.session.ended" }
                .map { it.attributes },
        )
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)
}
