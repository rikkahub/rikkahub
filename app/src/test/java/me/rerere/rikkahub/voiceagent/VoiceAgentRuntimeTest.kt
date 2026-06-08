package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.voiceagent.gemini.GeminiContentTurn
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveCodec
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.telemetry.HermesToolResponseHash
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceAgentRuntimeTest {
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

        assertEquals(VoiceToolStatus.CallingHermes("call-1"), coordinator.state.value.tool)
        assertEquals("call-1" to "Look this up", toolApi.awaitRequest("call-1"))

        coordinator.onGeminiEvent(GeminiLiveEvent.Interrupted())
        assertEquals(VoiceAudioStatus.PlaybackSuppressed, coordinator.state.value.audio)
        audio.awaitSuppressPlaybackCalls(1)
        assertEquals(VoiceToolStatus.CallingHermes("call-1"), coordinator.state.value.tool)
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("late-audio"))
        assertEquals(emptyList<String>(), audio.playedPcm16)

        withContext(Dispatchers.Default) {
            Thread.sleep(25)
        }
        toolApi.complete(response(callId = "call-1", answer = "Hermes answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf("call-1" to "Hermes answer"), gemini.toolResponses)
        val answered = assertHermesAnswered(callId = "call-1", status = coordinator.state.value.tool)
        assertTrue("Expected delayed Hermes call to record elapsed time", answered.elapsedMs >= 1L)
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
    fun `pending Hermes tool status refreshes elapsed time while request is waiting`() = runTest {
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

        withTimeout(1500) {
            while ((coordinator.state.value.tool as? VoiceToolStatus.CallingHermes)?.elapsedMs == 0L) {
                delay(10)
            }
        }

        val calling = coordinator.state.value.tool as VoiceToolStatus.CallingHermes
        assertEquals("call-timer", calling.callId)
        assertTrue("Expected pending Hermes status to expose elapsed time", calling.elapsedMs > 0L)

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

        assertEquals(listOf("call-supported" to "Supported answer"), gemini.toolResponses)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "unsupported_tool_call" &&
                    it.detail.contains("call-unsupported") &&
                    it.detail.contains("unsupported_tool")
            }
        )
    }

    @Test
    fun `close cancels in-flight Hermes call from external scope and prevents Gemini response`() = runTest {
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
        assertEquals(VoiceToolStatus.CallingHermes("call-close"), coordinator.state.value.tool)
        assertEquals(false, toolApi.wasCancelled("call-close"))

        coordinator.close()
        withTimeout(500) {
            toolApi.awaitCancelled("call-close")
        }
        toolApi.complete(response(callId = "call-close", answer = "late answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
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

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCallCancellation(listOf("call-a")))
        toolApi.awaitCancelled("call-a")

        assertEquals(VoiceToolStatus.CallingHermes("call-b"), coordinator.state.value.tool)
        assertEquals(
            mapOf("call-b" to VoiceToolStatus.CallingHermes("call-b")),
            coordinator.state.value.toolCalls,
        )
        assertEquals(false, toolApi.wasCancelled("call-b"))

        toolApi.complete(response(callId = "call-b", answer = "Second answer"))
        coordinator.awaitToolJobsWithTimeout()
        toolApi.complete(response(callId = "call-a", answer = "First late answer"))

        assertEquals(listOf("call-b" to "Second answer"), gemini.toolResponses)
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
    }

    @Test
    fun `duplicate tool call id cancels replaced Hermes job and only latest response is sent`() = runTest {
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
        toolApi.awaitCancelled("call-replay")
        assertEquals("call-replay" to "new", toolApi.awaitRequest("call-replay"))

        toolApi.complete(response(callId = "call-replay", answer = "new answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf("call-replay" to "new answer"), gemini.toolResponses)
        assertHermesAnswered(callId = "call-replay", status = coordinator.state.value.tool)
    }

    @Test
    fun `duplicate tool call id is ignored when old send is already in progress`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val oldSend = gemini.blockNextToolResponse("call-replay-send")
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
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
            listOf("call-replay-send" to "old answer"),
            gemini.toolResponses,
        )
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
        assertEquals(
            mapOf(
                "call-a" to VoiceToolStatus.CallingHermes("call-a"),
                "call-b" to VoiceToolStatus.CallingHermes("call-b"),
            ),
            coordinator.state.value.toolCalls,
        )

        toolApi.complete(response(callId = "call-a", answer = "First answer"))
        withTimeout(500) {
            while (coordinator.state.value.toolCalls["call-a"] !is VoiceToolStatus.HermesAnswered) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(VoiceToolStatus.CallingHermes("call-b"), coordinator.state.value.tool)
        assertEquals(setOf("call-a", "call-b"), coordinator.state.value.toolCalls.keys)
        assertHermesAnswered(callId = "call-a", status = coordinator.state.value.toolCalls.getValue("call-a"))
        assertEquals(VoiceToolStatus.CallingHermes("call-b"), coordinator.state.value.toolCalls.getValue("call-b"))

        toolApi.complete(response(callId = "call-b", answer = "Second answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(
            setOf("call-a" to "First answer", "call-b" to "Second answer"),
            gemini.toolResponses.toSet(),
        )
        assertEquals(setOf("call-a", "call-b"), coordinator.state.value.toolCalls.keys)
        assertHermesAnswered(callId = "call-a", status = coordinator.state.value.toolCalls.getValue("call-a"))
        assertHermesAnswered(callId = "call-b", status = coordinator.state.value.toolCalls.getValue("call-b"))
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_tool_succeeded" &&
                    it.detail.contains("callId=call-a") &&
                    it.detail.contains("elapsedMs=")
            }
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_tool_succeeded" &&
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

        assertEquals(listOf("call-hash" to " \nalpha\t  beta\r\n"), gemini.toolResponses)
        val hashEvent = diagnostics.events.value.single { it.name == "hermes_tool_response_hash" }
        assertTrue(hashEvent.detail.contains("callId=call-hash"))
        assertTrue(hashEvent.detail.contains("actualHash=$expectedHash"))
        assertTrue(hashEvent.detail.contains("expectedHashMatch=true"))
        assertTrue(hashEvent.detail.contains("serverElapsedMs=321"))
        assertFalse(hashEvent.detail.contains("alpha"))
        assertFalse(hashEvent.detail.contains("beta"))

        val successEvent = diagnostics.events.value.single { it.name == "hermes_tool_succeeded" }
        assertTrue(successEvent.detail.contains("callId=call-hash"))
        assertTrue(successEvent.detail.contains("answerChars=16"))
        assertFalse(successEvent.detail.contains("alpha"))
        assertFalse(successEvent.detail.contains("beta"))
    }

    @Test
    fun `Hermes response hash diagnostic is skipped without expected hash`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        var hashLogCalls = 0
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            hermesResponseExpectedHash = "",
            logHermesResponseHash = { hashLogCalls++ },
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-no-hash", name = "ask_hermes", prompt = "private prompt")
        )
        assertEquals("call-no-hash" to "private prompt", toolApi.awaitRequest("call-no-hash"))
        toolApi.complete(response(callId = "call-no-hash", answer = "alpha beta"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf("call-no-hash" to "alpha beta"), gemini.toolResponses)
        assertEquals(0, hashLogCalls)
        assertFalse(diagnostics.events.value.any { it.name == "hermes_tool_response_hash" })
        val successEvent = diagnostics.events.value.single { it.name == "hermes_tool_succeeded" }
        assertTrue(successEvent.detail.contains("callId=call-no-hash"))
        assertTrue(successEvent.detail.contains("answerChars=10"))
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

        assertEquals(listOf("call-log-fails" to "alpha beta"), gemini.toolResponses)
        assertHermesAnswered(callId = "call-log-fails", status = coordinator.state.value.tool)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_tool_response_hash" &&
                    it.detail.contains("callId=call-log-fails") &&
                    it.detail.contains("expectedHashMatch=true")
            }
        )
        val successEvent = diagnostics.events.value.single { it.name == "hermes_tool_succeeded" }
        assertTrue(successEvent.detail.contains("callId=call-log-fails"))
        assertTrue(successEvent.detail.contains("answerChars=10"))
        assertFalse(successEvent.detail.contains("alpha beta"))
        assertFalse(
            diagnostics.events.value.any {
                it.name == "hermes_tool_failed" && it.detail.contains("logger failed")
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

        toolApi.fail(callId = "call-fail", error = IllegalStateException("Hermes failed"))
        withTimeout(500) {
            while (coordinator.state.value.toolCalls["call-fail"] !is VoiceToolStatus.HermesFailed) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(VoiceToolStatus.CallingHermes("call-ok"), coordinator.state.value.tool)

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
        assertEquals(listOf("call-ok" to "Second answer"), gemini.toolResponses)
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

        toolApi.fail(IllegalStateException("Hermes offline"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(
            VoiceToolStatus.HermesFailed(callId = "call-3", message = "Hermes offline"),
            coordinator.state.value.tool,
        )
        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertTrue(
            diagnostics.events.value.any { it.name == "hermes_tool_failed" && it.detail.contains("Hermes offline") }
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
            IllegalStateException(
                "Voice Lab request failed 403: {\"prompt\":\"private prompt\",\"answer\":\"private answer\"}"
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
    fun `failed Gemini tool response send marks Hermes tool failed instead of answered`() = runTest {
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
        assertEquals(
            VoiceToolStatus.HermesFailed(
                callId = "call-send-fails",
                message = "Failed to send Gemini tool response message",
            ),
            coordinator.state.value.tool,
        )
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
    fun `close is idempotent and clears active tool state`() = runTest {
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
            GeminiLiveEvent.ToolCall(callId = "call-close-idempotent", name = "ask_hermes", prompt = "close")
        )
        assertEquals("call-close-idempotent" to "close", toolApi.awaitRequest("call-close-idempotent"))

        coordinator.close()
        toolApi.awaitCancelled("call-close-idempotent")
        coordinator.close()

        assertTrue(gemini.closeCalls >= 1)
        assertEquals(1, audio.releaseCalls)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)
        assertEquals(emptyMap<String, VoiceToolStatus>(), coordinator.state.value.toolCalls)
    }

    @Test
    fun `close waits for in flight Gemini tool response send before releasing resources`() = runTest {
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
        assertEquals(0, gemini.closeCalls)
        assertEquals(0, audio.releaseCalls)

        blockedSend.release.countDown()
        withTimeout(500) {
            closeJob.join()
        }

        assertEquals(listOf("call-close-send" to "answer before close"), gemini.toolResponses)
        assertTrue(gemini.closeCalls >= 1)
        assertEquals(1, audio.releaseCalls)
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

        assertEquals(listOf("call-close-reentry" to "answer before close"), gemini.toolResponses)
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

        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
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
        conversationStore.awaitUpdateCount(6)

        val messages = conversationStore.conversation.value.currentMessages
        assertEquals(3, messages.size)
        assertTrue(messages[0].parts.filterIsInstance<UIMessagePart.Text>().any { it.text == "hello" })
        assertTrue(messages[1].parts.filterIsInstance<UIMessagePart.Text>().any { it.text == "hi" })
        val userText = messages[0].parts.filterIsInstance<UIMessagePart.Text>().single()
        val assistantText = messages[1].parts.filterIsInstance<UIMessagePart.Text>().single()
        val tool = messages[2].parts.filterIsInstance<UIMessagePart.Tool>().single()
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
                it.name == "input_transcript_delta" && it.detail.contains("text=hel")
            }
        )
        assertTrue(
            coordinator.state.value.diagnostics.any {
                it.name == "output_transcript_delta" && it.detail.contains("text=h")
            }
        )
        assertTrue(
            coordinator.state.value.diagnostics.any {
                it.name == "hermes_tool_succeeded" &&
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
    fun `tool cancellation persists accepted Hermes record as failed`() = runTest {
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
        toolApi.awaitCancelled("call-cancel-persist")
        coordinator.awaitPersistenceJobsWithTimeout()

        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-cancel-persist", tool.toolCallId)
        assertEquals("failed", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("Tool call canceled by Gemini", tool.output.text())
    }

    @Test
    fun `reconnect cleanup persists accepted Hermes record as failed`() = runTest {
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
        toolApi.awaitCancelled("call-reconnect-persist")
        coordinator.awaitPersistenceJobsWithTimeout()

        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-reconnect-persist", tool.toolCallId)
        assertEquals("failed", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("Tool call canceled by reconnect", tool.output.text())
    }

    @Test
    fun `close persists accepted Hermes record as failed`() = runTest {
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
        toolApi.awaitCancelled("call-close-persist")
        coordinator.awaitPersistenceJobsWithTimeout()

        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-close-persist", tool.toolCallId)
        assertEquals("failed", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("Tool call canceled by session end", tool.output.text())
    }

    @Test
    fun `close after Gemini tool response send starts persists Hermes record as complete`() = runTest {
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
        coordinator.awaitPersistenceJobsWithTimeout()

        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-close-sending-persist", tool.toolCallId)
        assertEquals("complete", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("sent answer", tool.output.text())
    }

    @Test
    fun `reconnect after Gemini tool response send starts does not wait and persists Hermes record as failed`() = runTest {
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
        val tool = withTimeout(500) {
            var completedTool: UIMessagePart.Tool? = null
            while (completedTool?.metadata?.get("voice_tool_status")?.jsonPrimitive?.content != "failed") {
                delay(10)
                completedTool = conversationStore.conversation.value.currentMessages
                    .flatMap { it.parts }
                    .filterIsInstance<UIMessagePart.Tool>()
                    .singleOrNull()
            }
            completedTool
        }
        assertEquals("call-reconnect-sending-persist", tool.toolCallId)
        assertEquals("failed", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("Tool call canceled by reconnect", tool.output.text())
    }

    @Test
    fun `forced close after Gemini tool response send starts persists Hermes record as failed`() = runTest {
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
        val tool = withTimeout(500) {
            var completedTool: UIMessagePart.Tool? = null
            while (completedTool?.metadata?.get("voice_tool_status")?.jsonPrimitive?.content != "failed") {
                delay(10)
                completedTool = conversationStore.conversation.value.currentMessages
                    .flatMap { it.parts }
                    .filterIsInstance<UIMessagePart.Tool>()
                    .singleOrNull()
            }
            completedTool
        }
        assertEquals("call-forced-close-sending-persist", tool.toolCallId)
        assertEquals("failed", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("Tool call canceled by session end", tool.output.text())
    }

    @Test
    fun `Gemini cancellation after tool response send starts persists Hermes record as complete`() = runTest {
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
        assertEquals("complete", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("sent answer", tool.output.text())
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
    fun `reconnect cleanup cancels in flight Hermes call and suppresses stale response`() = runTest {
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

        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)
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
        assertEquals("failed", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("Tool call canceled by session end", tool.output.text())
        toolApi.awaitCancelled("call-async-error")
        audio.emitCapture(byteArrayOf(4, 5, 6))
        assertEquals(listOf(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))), gemini.audioMessages)
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
        conversationStore.awaitUpdateCount(2)

        val toolStatuses = conversationStore.updatesSnapshot()
            .flatMap { it.currentMessages }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolCallId == "call-vm-reconnect-order" }
            .mapNotNull { it.metadata?.get("voice_tool_status")?.jsonPrimitive?.content }

        assertTrue(toolStatuses.toString(), "failed" in toolStatuses)
        assertTrue(toolStatuses.toString(), "complete" !in toolStatuses)
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
        conversationStore.awaitUpdateCount(2)

        val toolStatuses = conversationStore.updatesSnapshot()
            .flatMap { it.currentMessages }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolCallId == "call-vm-reconnect-sending" }
            .mapNotNull { it.metadata?.get("voice_tool_status")?.jsonPrimitive?.content }
        val diagnosticNames = diagnostics.events.value.map { it.name }

        assertTrue("statuses=$toolStatuses diagnostics=$diagnosticNames", "complete" !in toolStatuses)
        assertTrue("statuses=$toolStatuses diagnostics=$diagnosticNames", "failed" in toolStatuses)
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
        conversationStore.awaitUpdateCount(2)

        val toolStatuses = conversationStore.updatesSnapshot()
            .flatMap { it.currentMessages }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolCallId == "call-vm-end-pending" }
            .mapNotNull { it.metadata?.get("voice_tool_status")?.jsonPrimitive?.content }

        assertTrue(toolStatuses.toString(), "complete" !in toolStatuses)
        assertTrue(toolStatuses.toString(), "failed" in toolStatuses)
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
        conversationStore.awaitUpdateCount(2)

        val toolStatuses = conversationStore.updatesSnapshot()
            .flatMap { it.currentMessages }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolCallId == "call-vm-cleared-pending" }
            .mapNotNull { it.metadata?.get("voice_tool_status")?.jsonPrimitive?.content }

        assertTrue(toolStatuses.toString(), "complete" !in toolStatuses)
        assertTrue(toolStatuses.toString(), "failed" in toolStatuses)
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

    private suspend fun VoiceDiagnostics.awaitEvent(name: String) {
        withTimeout(500) {
            while (events.value.none { it.name == name }) {
                delay(10)
            }
        }
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)
}
