package me.rerere.rikkahub.voiceagent.gemini

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames
import me.rerere.rikkahub.voiceagent.voiceToolCall
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveVoiceClientTest {
    private val setupCompleteMessage = """{"setupComplete":{}}"""
    private val liveConnectConfig = JsonObject(
        mapOf(
            "responseModalities" to JsonArray(listOf(JsonPrimitive("AUDIO"))),
        )
    )
    private val liveConnectConfigWithRealtimeInput = JsonObject(
        mapOf(
            "responseModalities" to JsonArray(listOf(JsonPrimitive("AUDIO"))),
            "realtimeInputConfig" to JsonObject(
                mapOf(
                    "automaticActivityDetection" to JsonObject(
                        mapOf(
                            "disabled" to JsonPrimitive(false),
                            "startOfSpeechSensitivity" to JsonPrimitive("START_SENSITIVITY_LOW"),
                            "endOfSpeechSensitivity" to JsonPrimitive("END_SENSITIVITY_LOW"),
                            "prefixPaddingMs" to JsonPrimitive(20),
                            "silenceDurationMs" to JsonPrimitive(100),
                        )
                    )
                )
            ),
        )
    )

    @Test
    fun `connect sends setup then context and forwards parsed tool calls`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())
        val events = mutableListOf<GeminiLiveEvent>()

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = listOf(
                GeminiContentTurn(role = "user", text = "Hello"),
                GeminiContentTurn(role = "model", text = "Hi"),
            ),
            onEvent = events::add,
        )

        assertEquals("wss://example.test/live", socket.openedUrl)
        assertEquals("token-1", socket.openedToken)
        assertEquals(1, socket.sentMessages.size)
        assertTrue("setup" in socket.sentMessages[0].jsonObject())
        assertEquals(
            true,
            socket.sentMessages[0]
                .jsonObject()["setup"]!!
                .jsonObject["historyConfig"]!!
                .jsonObject["initialHistoryInClientContent"]!!
                .jsonPrimitive.boolean,
        )

        socket.receive(setupCompleteMessage)

        assertEquals(2, socket.sentMessages.size)
        assertTrue("clientContent" in socket.sentMessages[1].jsonObject())
        assertEquals(
            true,
            socket.sentMessages[1]
                .jsonObject()["clientContent"]!!
                .jsonObject["turnComplete"]!!
                .jsonPrimitive.boolean,
        )
        assertEquals(listOf(GeminiLiveEvent.SetupComplete), events)
        events.clear()

        socket.receive(
            """
            {
              "toolCall":{
                "functionCalls":[
                  {
                    "id":"call-1",
                    "name":"ask_hermes",
                    "args":{"prompt":"First prompt"}
                  },
                  {
                    "id":"call-2",
                    "name":"ask_hermes",
                    "args":{"prompt":"Second prompt"}
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                GeminiLiveEvent.ToolCalls(
                    listOf(
                        voiceToolCall(
                            callId = "call-1",
                            name = "ask_hermes",
                            prompt = "First prompt",
                        ),
                        voiceToolCall(
                            callId = "call-2",
                            name = "ask_hermes",
                            prompt = "Second prompt",
                        ),
                    )
                )
            ),
            events,
        )

        client.sendToolResponse(callId = "call-1", answer = "42")

        val response = socket.sentMessages[2]
            .jsonObject()["toolResponse"]!!
            .jsonObject["functionResponses"]!!
            .jsonArray[0]
            .jsonObject
        assertEquals("call-1", response["id"]!!.jsonPrimitive.content)
        assertEquals("42", response["response"]!!.jsonObject["answer"]!!.jsonPrimitive.content)
    }

    @Test
    fun `send text turn sends client content after setup`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = {},
        )
        socket.receive(setupCompleteMessage)

        assertTrue(client.sendTextTurn("Hermes answer is ready"))

        val clientContent = socket.sentMessages.last()
            .jsonObject()["clientContent"]!!
            .jsonObject
        assertTrue(clientContent["turnComplete"]!!.jsonPrimitive.boolean)
        val turn = clientContent["turns"]!!.jsonArray.single().jsonObject
        assertEquals("user", turn["role"]!!.jsonPrimitive.content)
        assertEquals(
            "Hermes answer is ready",
            turn["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `connect omits client content when context is empty`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = {},
        )

        assertEquals(1, socket.sentMessages.size)
        assertTrue("setup" in socket.sentMessages.single().jsonObject())
        assertFalse("clientContent" in socket.sentMessages.single().jsonObject())
    }

    @Test
    fun `combined server content is emitted as ordered individual events`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())
        val events = mutableListOf<GeminiLiveEvent>()

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "",
            contextTurns = emptyList(),
            onEvent = events::add,
        )
        socket.receive(setupCompleteMessage)
        events.clear()

        socket.receive(
            """
            {
              "serverContent":{
                "outputTranscription":{"text":"final words"},
                "modelTurn":{
                  "parts":[
                    {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"base64-pcm"}}
                  ]
                },
                "generationComplete":true
              }
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                GeminiLiveEvent.OutputTranscript("final words"),
                GeminiLiveEvent.OutputAudio("base64-pcm"),
                GeminiLiveEvent.GenerationComplete,
            ),
            events,
        )
    }

    @Test
    fun `connect emits error when setup send fails`() = runBlocking {
        val socket = FakeGeminiSocket().apply {
            sendResults += false
        }
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())
        val events = mutableListOf<GeminiLiveEvent>()

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = events::add,
        )

        assertEquals(
            listOf(
                GeminiLiveEvent.Error(
                    message = "Failed to send Gemini setup message",
                    raw = "",
                )
            ),
            events,
        )
    }

    @Test
    fun `connect emits error when context send fails`() = runBlocking {
        val socket = FakeGeminiSocket().apply {
            sendResults += true
            sendResults += false
        }
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())
        val events = mutableListOf<GeminiLiveEvent>()

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = listOf(GeminiContentTurn(role = "user", text = "Hello")),
            onEvent = events::add,
        )

        socket.receive(setupCompleteMessage)

        assertEquals(
            listOf(
                GeminiLiveEvent.SetupComplete,
                GeminiLiveEvent.Error(
                    message = "Failed to send Gemini context message",
                    raw = "",
                )
            ),
            events,
        )
    }

    @Test
    fun `early audio waits for setup complete and tool response is rejected before setup`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())
        val events = mutableListOf<GeminiLiveEvent>()

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = listOf(GeminiContentTurn(role = "user", text = "Hello")),
            onEvent = events::add,
        )

        client.sendAudio("base64-audio")
        assertFalse(client.sendToolResponse(callId = "call-1", answer = "42"))

        assertEquals(1, socket.sentMessages.size)
        assertTrue("setup" in socket.sentMessages[0].jsonObject())

        socket.receive(setupCompleteMessage)

        assertEquals(3, socket.sentMessages.size)
        assertTrue("clientContent" in socket.sentMessages[1].jsonObject())
        assertTrue("realtimeInput" in socket.sentMessages[2].jsonObject())
        assertEquals(listOf(GeminiLiveEvent.SetupComplete), events)
    }

    @Test
    fun `send audio uses realtime input audio shape after setup complete`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = {},
        )
        socket.receive(setupCompleteMessage)

        client.sendAudio("base64-audio")

        val audio = socket.sentMessages.last()
            .jsonObject()["realtimeInput"]!!
            .jsonObject["audio"]!!
            .jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        assertEquals("base64-audio", audio["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `send audio stream end uses realtime input stream end shape after setup complete`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = {},
        )
        socket.receive(setupCompleteMessage)

        assertTrue(client.sendAudioStreamEnd(sessionId = null))

        val realtimeInput = socket.sentMessages.last()
            .jsonObject()["realtimeInput"]!!
            .jsonObject
        assertEquals(true, realtimeInput["audioStreamEnd"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `debug observer receives sanitized Gemini boundary events`() = runBlocking {
        val socket = FakeGeminiSocket()
        val observed = mutableListOf<GeminiLiveDebugEvent>()
        val client = TestableGeminiLiveVoiceClient(
            socket = socket,
            codec = GeminiLiveCodec(),
            debugObserver = observed::add,
        )

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = {},
        )
        socket.receive(setupCompleteMessage)

        client.sendAudio("base64-audio")
        client.sendAudioStreamEnd(sessionId = null)

        assertEquals(
            listOf(
                GeminiLiveDebugEvent.Setup(
                    hasAskHermesTool = false,
                    toolConfigMode = null,
                    allowedFunctionNames = emptyList(),
                    responseModalities = listOf("AUDIO"),
                    systemInstructionChars = "You are Hermes.".length,
                    realtimeInputConfig = null,
                ),
                GeminiLiveDebugEvent.Open,
                GeminiLiveDebugEvent.Send(kind = "setup", sent = true, dataBytes = null),
                GeminiLiveDebugEvent.Receive(kind = "setupComplete"),
                GeminiLiveDebugEvent.Event(kind = "SetupComplete"),
                GeminiLiveDebugEvent.Send(kind = "realtimeInput.audio", sent = true, dataBytes = 9),
                GeminiLiveDebugEvent.Send(kind = "realtimeInput.audioStreamEnd", sent = true, dataBytes = null),
            ),
            observed,
        )
    }

    @Test
    fun `debug observer reports sanitized realtime input config from setup`() = runBlocking {
        val socket = FakeGeminiSocket()
        val observed = mutableListOf<GeminiLiveDebugEvent>()
        val client = TestableGeminiLiveVoiceClient(
            socket = socket,
            codec = GeminiLiveCodec(),
            debugObserver = observed::add,
        )

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfigWithRealtimeInput,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = {},
        )

        assertEquals(
            GeminiLiveDebugEvent.Setup(
                hasAskHermesTool = false,
                toolConfigMode = null,
                allowedFunctionNames = emptyList(),
                responseModalities = listOf("AUDIO"),
                systemInstructionChars = "You are Hermes.".length,
                realtimeInputConfig = "automaticActivityDetection.disabled=false " +
                    "start=START_SENSITIVITY_LOW end=END_SENSITIVITY_LOW " +
                    "prefixPaddingMs=20 silenceDurationMs=100",
            ),
            observed.first(),
        )
    }

    @Test
    fun `send audio emits error when send fails after connect`() = runBlocking {
        val socket = FakeGeminiSocket().apply {
            sendResults += true
            sendResults += false
        }
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())
        val events = mutableListOf<GeminiLiveEvent>()

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = events::add,
        )

        socket.receive(setupCompleteMessage)
        events.clear()
        client.sendAudio("base64-audio")

        assertEquals(
            listOf(
                GeminiLiveEvent.Error(
                    message = "Failed to send Gemini audio message",
                    raw = "",
                )
            ),
            events,
        )
    }

    @Test
    fun `send tool response emits error when send fails after connect`() = runBlocking {
        val socket = FakeGeminiSocket().apply {
            sendResults += true
            sendResults += false
        }
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())
        val events = mutableListOf<GeminiLiveEvent>()

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = events::add,
        )

        socket.receive(setupCompleteMessage)
        events.clear()
        assertFalse(client.sendToolResponse(callId = "call-1", answer = "42"))

        assertEquals(
            listOf(
                GeminiLiveEvent.Error(
                    message = "Failed to send Gemini tool response message",
                    raw = "",
                )
            ),
            events,
        )
    }

    @Test
    fun `send tool response returns false after session closes`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = {},
        )
        socket.receive(setupCompleteMessage)
        client.close()

        assertFalse(client.sendToolResponse(callId = "call-closed", answer = "42"))
    }

    @Test
    fun `close cannot interleave before connect opens and sends setup`() = runBlocking {
        val closeStarted = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)
        lateinit var client: TestableGeminiLiveVoiceClient
        var closeThread: Thread? = null
        val socket = FakeGeminiSocket().apply {
            beforeOpen = {
                closeThread = Thread {
                    closeStarted.countDown()
                    client.close()
                    closeCompleted.countDown()
                }.also { it.start() }

                assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
                assertFalse(closeCompleted.await(100, TimeUnit.MILLISECONDS))
            }
        }
        client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = {},
        )
        closeThread?.join(1_000)

        assertTrue(closeCompleted.await(1, TimeUnit.SECONDS))
        assertEquals(1, socket.openedSessions.size)
        assertEquals(1, socket.sentMessages.size)
        assertTrue("setup" in socket.sentMessages.single().jsonObject())
        assertEquals(1, socket.closeCount)
    }

    @Test
    fun `close can complete while current generation send is blocked`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())
        val closeCompletedDuringSend = AtomicBoolean(false)
        val closeStarted = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)
        var closeThread: Thread? = null

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = {},
        )
        socket.receive(setupCompleteMessage)
        socket.beforeSend = { text ->
            if ("realtimeInput" in text) {
                val thread = Thread {
                    closeStarted.countDown()
                    client.close()
                    closeCompleted.countDown()
                }
                closeThread = thread
                thread.start()

                assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
                closeCompletedDuringSend.set(closeCompleted.await(100, TimeUnit.MILLISECONDS))
            }
        }

        client.sendAudio("base64-audio")
        closeThread?.join(1_000)

        assertTrue(closeCompletedDuringSend.get())
        assertTrue(closeCompleted.await(1, TimeUnit.SECONDS))
        assertEquals(1, socket.closeCount)
    }

    @Test
    fun `queued send failure after setup complete emits message specific error`() = runBlocking {
        val socket = FakeGeminiSocket().apply {
            sendResults += true
            sendResults += true
            sendResults += false
        }
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())
        val events = mutableListOf<GeminiLiveEvent>()

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = listOf(GeminiContentTurn(role = "user", text = "Hello")),
            onEvent = events::add,
        )
        client.sendAudio("base64-audio")

        socket.receive(setupCompleteMessage)

        assertEquals(
            listOf(
                GeminiLiveEvent.SetupComplete,
                GeminiLiveEvent.Error(
                    message = "Failed to send Gemini audio message",
                    raw = "",
                ),
            ),
            events,
        )
    }

    @Test
    fun `stale socket message after reconnect is ignored`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())
        val firstEvents = mutableListOf<GeminiLiveEvent>()
        val secondEvents = mutableListOf<GeminiLiveEvent>()

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = listOf(GeminiContentTurn(role = "user", text = "First")),
            onEvent = firstEvents::add,
        )
        client.connect(
            token = "token-2",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = listOf(GeminiContentTurn(role = "user", text = "Second")),
            onEvent = secondEvents::add,
        )

        socket.receiveFromSession(0, setupCompleteMessage)

        assertTrue(firstEvents.isEmpty())
        assertTrue(secondEvents.isEmpty())
        assertEquals(2, socket.sentMessages.size)

        socket.receiveFromSession(1, setupCompleteMessage)

        assertEquals(listOf(GeminiLiveEvent.SetupComplete), secondEvents)
        assertEquals(3, socket.sentMessages.size)
        assertTrue("clientContent" in socket.sentMessages[2].jsonObject())
    }

    @Test
    fun `close does not wait for blocked socket send`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = {},
        )
        socket.receive(setupCompleteMessage)

        val sendStarted = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        socket.beforeSend = { text ->
            if ("toolResponse" in text) {
                sendStarted.countDown()
                releaseSend.await(500, TimeUnit.MILLISECONDS)
            }
        }
        val sendThread = thread {
            client.sendToolResponse(callId = "call-1", answer = "42")
        }
        assertTrue(sendStarted.await(500, TimeUnit.MILLISECONDS))

        client.close()

        assertEquals(1, socket.closeCount)
        releaseSend.countDown()
        sendThread.join(500)
        assertFalse(sendThread.isAlive)
    }

    @Test
    fun `outbound invalidation waits for token aware audio handoff`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = {},
        )
        socket.receive(setupCompleteMessage)
        client.activateOutboundSession(1L)

        val sendStarted = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        socket.beforeSend = { text ->
            if ("realtimeInput" in text) {
                sendStarted.countDown()
                releaseSend.await(500, TimeUnit.MILLISECONDS)
            }
        }
        val sendThread = thread {
            assertTrue(client.sendAudio(base64Pcm16 = "base64-audio", sessionId = 1L))
        }
        assertTrue(sendStarted.await(500, TimeUnit.MILLISECONDS))

        val invalidationReturned = AtomicBoolean(false)
        val invalidateThread = thread {
            client.invalidateOutboundSession()
            invalidationReturned.set(true)
        }
        Thread.sleep(50)
        assertFalse(invalidationReturned.get())

        releaseSend.countDown()
        sendThread.join(500)
        invalidateThread.join(500)

        assertFalse(sendThread.isAlive)
        assertFalse(invalidateThread.isAlive)
        assertTrue(invalidationReturned.get())
        assertFalse(client.sendAudio(base64Pcm16 = "late-audio", sessionId = 1L))
        assertEquals(1, socket.sentMessages.count { "realtimeInput" in it })
    }

    @Test
    fun `outbound invalidation waits for token aware tool response handoff`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = {},
        )
        socket.receive(setupCompleteMessage)
        client.activateOutboundSession(1L)

        val sendStarted = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        socket.beforeSend = { text ->
            if ("functionResponses" in text) {
                sendStarted.countDown()
                releaseSend.await(500, TimeUnit.MILLISECONDS)
            }
        }
        val sendThread = thread {
            assertTrue(client.sendToolResponse(callId = "call-1", answer = "42", sessionId = 1L))
        }
        assertTrue(sendStarted.await(500, TimeUnit.MILLISECONDS))

        val invalidationReturned = AtomicBoolean(false)
        val invalidateThread = thread {
            client.invalidateOutboundSession()
            invalidationReturned.set(true)
        }
        Thread.sleep(50)
        assertFalse(invalidationReturned.get())

        releaseSend.countDown()
        sendThread.join(500)
        invalidateThread.join(500)

        assertFalse(sendThread.isAlive)
        assertFalse(invalidateThread.isAlive)
        assertTrue(invalidationReturned.get())
        client.activateOutboundSession(2L)
        assertFalse(client.sendToolResponse(callId = "call-late", answer = "late", sessionId = 1L))
        assertEquals(1, socket.sentMessages.count { "functionResponses" in it })
    }

    @Test
    fun `token aware tool response preserves cancel hermes function name`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = {},
        )
        socket.receive(setupCompleteMessage)
        client.activateOutboundSession(1L)

        assertTrue(
            client.sendToolResponse(
                callId = "cancel-1",
                answer = "cancel response",
                sessionId = 1L,
                name = VoiceAgentToolNames.CANCEL_HERMES,
            )
        )

        val functionResponse = socket.sentMessages
            .last { "functionResponses" in it }
            .jsonObject()["toolResponse"]!!
            .jsonObject["functionResponses"]!!
            .jsonArray.single()
            .jsonObject
        assertEquals("cancel-1", functionResponse["id"]!!.jsonPrimitive.content)
        assertEquals(VoiceAgentToolNames.CANCEL_HERMES, functionResponse["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `socket close before setup complete terminates session without flushing later sends`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())
        val events = mutableListOf<GeminiLiveEvent>()

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = events::add,
        )

        socket.closeFromServer(code = 1001, reason = "going away")
        client.sendAudio("base64-audio")
        client.sendToolResponse(callId = "call-1", answer = "42")
        socket.receive(setupCompleteMessage)
        socket.fail(IllegalStateException("network down"))

        assertEquals(
            listOf(
                GeminiLiveEvent.WebSocketClosed(
                    code = 1001,
                    reason = "going away",
                ),
            ),
            events,
        )
        assertEquals(1, socket.sentMessages.size)
        assertTrue("setup" in socket.sentMessages.single().jsonObject())
    }

    @Test
    fun `socket failure before setup complete terminates session without flushing later sends`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())
        val events = mutableListOf<GeminiLiveEvent>()

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = events::add,
        )

        socket.fail(IllegalStateException("network down"))
        client.sendAudio("base64-audio")
        client.sendToolResponse(callId = "call-1", answer = "42")
        socket.receive(setupCompleteMessage)
        socket.closeFromServer(code = 1001, reason = "going away")

        assertEquals(
            listOf(
                GeminiLiveEvent.WebSocketFailure(
                    message = "network down",
                ),
            ),
            events,
        )
        assertEquals(1, socket.sentMessages.size)
        assertTrue("setup" in socket.sentMessages.single().jsonObject())
    }

    @Test
    fun `socket close and failure become typed diagnostics events`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())
        val closeEvents = mutableListOf<GeminiLiveEvent>()
        val failureEvents = mutableListOf<GeminiLiveEvent>()

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = closeEvents::add,
        )
        socket.closeFromServer(code = 1001, reason = "going away")

        client.connect(
            token = "token-2",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = failureEvents::add,
        )
        socket.fail(IllegalStateException("network down"))

        assertEquals(
            listOf(
                GeminiLiveEvent.WebSocketClosed(
                    code = 1001,
                    reason = "going away",
                ),
            ),
            closeEvents,
        )
        assertEquals(
            listOf(
                GeminiLiveEvent.WebSocketFailure(
                    message = "network down",
                ),
            ),
            failureEvents,
        )
    }

    @Test
    fun `close delegates to socket`() {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())

        client.close()

        assertEquals(1, socket.closeCount)
    }

    @Test
    fun `okhttp socket access token preserves existing query parameters`() {
        val url = geminiLiveUrlWithAccessToken(
            url = "wss://example.test/live?alt=sse",
            token = "token value",
        )

        assertEquals("sse", url.queryParameter("alt"))
        assertEquals("token value", url.queryParameter("access_token"))
        assertEquals(2, url.querySize)
    }

    @Test
    fun `okhttp socket forwards binary text frames as server messages`() {
        val server = MockWebServer()
        val message = """{"setupComplete":{}}"""
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(message.encodeUtf8())
                        }
                    }
                )
                .build()
        )
        server.start()
        try {
            val received = mutableListOf<String>()
            val latch = CountDownLatch(1)
            val socket = OkHttpGeminiSocket(OkHttpClient())

            socket.open(
                url = server.url("/live").toString().replace("http://", "ws://"),
                token = "token-1",
                onMessage = {
                    received += it
                    latch.countDown()
                },
                onClosed = { _, _ -> },
                onFailure = { throw AssertionError(it) },
            )

            assertTrue(latch.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(message), received)
        } finally {
            server.close()
        }
    }

    @Test
    fun `okhttp socket client strips application and network interceptors`() {
        val appInterceptor = Interceptor { chain ->
            chain.proceed(chain.request())
        }
        val networkInterceptor = Interceptor { chain ->
            chain.proceed(chain.request())
        }
        val baseClient = OkHttpClient.Builder()
            .addInterceptor(appInterceptor)
            .addNetworkInterceptor(networkInterceptor)
            .build()

        val isolatedClient = isolatedGeminiWebSocketClient(baseClient)

        assertEquals(listOf(appInterceptor), baseClient.interceptors)
        assertEquals(listOf(networkInterceptor), baseClient.networkInterceptors)
        assertTrue(isolatedClient.interceptors.isEmpty())
        assertTrue(isolatedClient.networkInterceptors.isEmpty())
    }

    private fun String.jsonObject(): JsonObject = JsonInstant.parseToJsonElement(this).jsonObject
}
