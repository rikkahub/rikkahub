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
import org.junit.Assert.assertEquals
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
                    "text" to "hello user",
                    "text.chars" to 10,
                    "text.sha256" to "b371a0ad941d7d294f63e6d0843e5588b62931b48c7f13d9c3e81b77150d1bf1",
                    "text.truncated" to false,
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
                    "text" to "hello assistant",
                    "text.chars" to 15,
                    "text.sha256" to "86babda521bb7aa17c08dcf62f1d281535e61234173e215f45e77a5bba20d78f",
                    "text.truncated" to false,
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
                    "voice.user_transcript.chars" to 10,
                    "voice.user_transcript.sha256" to "b371a0ad941d7d294f63e6d0843e5588b62931b48c7f13d9c3e81b77150d1bf1",
                    "voice.user_transcript.truncated" to false,
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
                    "gemini.output_transcript.chars" to 15,
                    "gemini.output_transcript.sha256" to "86babda521bb7aa17c08dcf62f1d281535e61234173e215f45e77a5bba20d78f",
                    "gemini.output_transcript.truncated" to false,
                )
            ),
            observability.events
                .filter { it.name == "voicelab.mobile.transcript.assistant_final" }
                .map { it.attributes },
        )
        assertEquals(
            listOf(
                mapOf(
                    "turnId" to "assistant-2",
                    "speaker" to "assistant",
                    "status" to "complete",
                    "gemini.output_transcript" to "hello assistant",
                    "gemini.output_transcript.chars" to 15,
                    "gemini.output_transcript.sha256" to "86babda521bb7aa17c08dcf62f1d281535e61234173e215f45e77a5bba20d78f",
                    "gemini.output_transcript.truncated" to false,
                ),
                mapOf(
                    "turnId" to "user-1",
                    "speaker" to "user",
                    "status" to "session-closed-before-final",
                    "voice.user_transcript" to "hello user",
                    "voice.user_transcript.chars" to 10,
                    "voice.user_transcript.sha256" to "b371a0ad941d7d294f63e6d0843e5588b62931b48c7f13d9c3e81b77150d1bf1",
                    "voice.user_transcript.truncated" to false,
                ),
            ),
            observability.events
                .filter { it.name == "voicelab.mobile.transcript.turn" }
                .map { it.attributes },
        )
        assertTrue(
            observability.events
                .filter { it.name.startsWith("voicelab.mobile.transcript.") }
                .all { it.trace == trace }
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
                    "gemini.output_transcript.chars" to 20,
                    "gemini.output_transcript.sha256" to "0c362834d7d8bd15103af70a4e9b5702b6fbee3e62cd0432caf192c94ce0878d",
                    "gemini.output_transcript.truncated" to false,
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
                "gemini.output_transcript.chars" to 17,
                "gemini.output_transcript.sha256" to "c14f04516a5b6592dc95504726652043c1376e9dffd5026c22fd3651b84a5633",
                "gemini.output_transcript.truncated" to false,
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
        conversationStore.awaitUpdateCount(1)
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
                )
            ),
            observability.events
                .filter { it.name == "voicelab.mobile.session.ended" }
                .map { it.attributes },
        )
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)
}
