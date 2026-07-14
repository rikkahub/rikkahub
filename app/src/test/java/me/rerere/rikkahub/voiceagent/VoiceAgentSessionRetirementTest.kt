package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.hermes.hermesQueueRecords
import me.rerere.rikkahub.voiceagent.hermesvoice.MobileHermesResponse
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceAgentSessionRetirementTest {
    @Test
    fun `session reconnect announces detached Hermes completion through new session bridge`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val diagnostics = VoiceDiagnostics()
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
            diagnostics = diagnostics,
            scope = CoroutineScope(coroutineContext + Dispatchers.Default),
        )

        session.start()
        gemini.awaitConnectCount(1)
        val oldCallback = gemini.eventHandlers.single()
        oldCallback(voiceToolCall(callId = "call-detached-complete", name = "ask_hermes", arg = "old"))
        assertEquals("call-detached-complete" to "old", toolApi.awaitRequest("call-detached-complete"))
        oldCallback(GeminiLiveEvent.OutputAudio("old-session-audio"))
        toolApi.complete(response(callId = "call-detached-complete", answer = "detached answer"))
        conversationStore.awaitHermesToolStatus("call-detached-complete", "complete")
        delay(20)
        assertEquals(emptyList<Pair<Long?, String>>(), gemini.textTurns)
        val blockedNextSession = sessionApi.blockNextSession()

        session.reconnect()
        withTimeout(500) {
            blockedNextSession.started.await()
        }
        delay(20)
        assertEquals(emptyList<Pair<Long?, String>>(), gemini.textTurns)
        assertFalse(
            conversationStore.conversation.value.hermesQueueRecords()
                .single { it.callId == "call-detached-complete" }
                .messageWritten,
        )

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
        assertEquals(1, gemini.textTurns.count { it.second.contains("detached answer") })
        assertTrue(
            diagnostics.events.value.any {
                it.name == "voice_playback_drained" && it.detail == "generation=1"
            }
        )
    }

    @Test
    fun `automatic reconnect retires active turn and playback before announcing through replacement`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val diagnostics = VoiceDiagnostics()
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
            diagnostics = diagnostics,
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 1),
            scope = CoroutineScope(coroutineContext + Dispatchers.Default),
        )

        session.start()
        gemini.awaitConnectCount(1)
        val oldCallback = gemini.eventHandlers.single()
        oldCallback(voiceToolCall(callId = "call-auto-complete", name = "ask_hermes", arg = "automatic"))
        assertEquals("call-auto-complete" to "automatic", toolApi.awaitRequest("call-auto-complete"))
        oldCallback(GeminiLiveEvent.OutputAudio("old-automatic-audio"))
        val blockedNextSession = sessionApi.blockNextSession()

        oldCallback(GeminiLiveEvent.WebSocketFailure(message = "network dropped"))
        withTimeout(500) {
            blockedNextSession.started.await()
        }
        toolApi.complete(response(callId = "call-auto-complete", answer = "automatic answer"))
        conversationStore.awaitHermesToolStatus("call-auto-complete", "complete")
        assertTrue(gemini.textTurns.isEmpty())

        blockedNextSession.release.complete(Unit)
        gemini.awaitConnectCount(2)
        withTimeout(500) {
            while (gemini.textTurns.none { it.first == 3L && it.second.contains("automatic answer") }) {
                delay(10)
            }
        }

        assertEquals(1, gemini.textTurns.count { it.second.contains("automatic answer") })
        assertTrue(
            diagnostics.events.value.any {
                it.name == "voice_playback_drained" && it.detail == "generation=1"
            }
        )
    }

    @Test
    fun `automatic reconnect cannot announce through bridge in retirement detach gap`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val retirementObserved = CountDownLatch(1)
        val releaseRetirement = CountDownLatch(1)
        diagnostics.addListener { event ->
            if (event.name == "gemini_session_retired") {
                retirementObserved.countDown()
                releaseRetirement.await(1, TimeUnit.SECONDS)
            }
        }
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
            reconnectPolicy = fastReconnectPolicy(maxAttempts = 3, delayMs = 1),
            scope = CoroutineScope(coroutineContext + Dispatchers.Default),
        )

        session.start()
        gemini.awaitConnectCount(1)
        val oldCallback = gemini.eventHandlers.single()
        oldCallback(voiceToolCall(callId = "call-retire-gap", name = "ask_hermes", arg = "gap"))
        assertEquals("call-retire-gap" to "gap", toolApi.awaitRequest("call-retire-gap"))

        val reconnect = launch(Dispatchers.Default) {
            oldCallback(GeminiLiveEvent.WebSocketFailure(message = "network dropped in gap"))
        }
        try {
            assertTrue(retirementObserved.await(500, TimeUnit.MILLISECONDS))
            toolApi.complete(response(callId = "call-retire-gap", answer = "gap answer"))
            conversationStore.awaitHermesToolStatus("call-retire-gap", "complete")
            delay(50)
            assertTrue("retired session A must not receive the announcement", gemini.textTurns.isEmpty())
        } finally {
            releaseRetirement.countDown()
        }

        reconnect.join()
        gemini.awaitConnectCount(2)
        withTimeout(500) {
            while (gemini.textTurns.none { it.first == 3L && it.second.contains("gap answer") }) {
                delay(10)
            }
        }
        assertEquals(1, gemini.textTurns.count { it.second.contains("gap answer") })
        assertTrue(gemini.textTurns.none { it.first == 1L && it.second.contains("gap answer") })
    }

    private fun response(callId: String, answer: String): MobileHermesResponse = MobileHermesResponse(
        callId = callId,
        answer = answer,
        model = "hermes-test",
        profileId = "profile",
        profileLabel = "Profile",
    )

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
