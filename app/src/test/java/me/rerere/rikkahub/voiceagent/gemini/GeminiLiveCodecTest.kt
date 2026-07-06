package me.rerere.rikkahub.voiceagent.gemini

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveCodecTest {
    private val codec = GeminiLiveCodec()

    @Test
    fun `setup message includes model and live connect config fields`() {
        val liveConnectConfig = JsonObject(
            mapOf(
                "responseModalities" to JsonArray(listOf(JsonPrimitive("AUDIO"))),
                "inputAudioTranscription" to JsonObject(emptyMap()),
                "outputAudioTranscription" to JsonObject(emptyMap()),
                "tools" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "functionDeclarations" to JsonArray(
                                    listOf(
                                        JsonObject(
                                            mapOf(
                                                "name" to JsonPrimitive("ask_hermes"),
                                                "description" to JsonPrimitive("Ask Hermes"),
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
            )
        )

        val message = codec.setupMessage(
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
        ).jsonObject()

        val setup = message["setup"]!!.jsonObject
        assertEquals("models/gemini-2.0-flash-live-001", setup["model"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("AUDIO"),
            setup["generationConfig"]!!.jsonObject["responseModalities"]!!.jsonArray.map {
                it.jsonPrimitive.content
            },
        )
        assertTrue(setup["inputAudioTranscription"] is JsonObject)
        assertTrue(setup["outputAudioTranscription"] is JsonObject)
        val declaration = setup["tools"]!!
            .jsonArray[0]
            .jsonObject["functionDeclarations"]!!
            .jsonArray[0]
            .jsonObject
        assertEquals("ask_hermes", declaration["name"]!!.jsonPrimitive.content)
        assertEquals(VoiceAgentToolNames.ASK_HERMES_DESCRIPTION, declaration["description"]!!.jsonPrimitive.content)
        assertEquals(VoiceAgentToolNames.ASK_HERMES_BEHAVIOR_NON_BLOCKING, declaration["behavior"]!!.jsonPrimitive.content)
        assertEquals(
            "You are Hermes.",
            setup["systemInstruction"]!!
                .jsonObject["parts"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `setup message preserves unknown setup fields and generation config fields`() {
        val liveConnectConfig = JsonObject(
            mapOf(
                "model" to JsonPrimitive("models/input-model"),
                "responseModalities" to JsonArray(listOf(JsonPrimitive("TEXT"))),
                "generationConfig" to JsonObject(
                    mapOf(
                        "temperature" to JsonPrimitive(0.7),
                        "candidateCount" to JsonPrimitive(2),
                        "responseModalities" to JsonArray(listOf(JsonPrimitive("AUDIO"))),
                    )
                ),
                "sessionResumption" to JsonObject(
                    mapOf("handle" to JsonPrimitive("resume-token"))
                ),
                "systemInstruction" to JsonObject(
                    mapOf(
                        "parts" to JsonArray(
                            listOf(
                                JsonObject(mapOf("text" to JsonPrimitive("input instruction")))
                            )
                        )
                    )
                ),
            )
        )

        val message = codec.setupMessage(
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "Local instruction.",
        ).jsonObject()

        val setup = message["setup"]!!.jsonObject
        val generationConfig = setup["generationConfig"]!!.jsonObject
        assertEquals("models/gemini-2.0-flash-live-001", setup["model"]!!.jsonPrimitive.content)
        assertEquals(liveConnectConfig["sessionResumption"], setup["sessionResumption"])
        assertEquals(JsonPrimitive(0.7), generationConfig["temperature"])
        assertEquals(JsonPrimitive(2), generationConfig["candidateCount"])
        assertEquals(
            listOf("TEXT"),
            generationConfig["responseModalities"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse("responseModalities" in setup)
        assertEquals(
            "Local instruction.",
            setup["systemInstruction"]!!
                .jsonObject["parts"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `setup message defaults ask hermes tool to required function calling`() {
        val liveConnectConfig = JsonObject(
            mapOf(
                "tools" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "functionDeclarations" to JsonArray(
                                    listOf(
                                        JsonObject(
                                            mapOf(
                                                "name" to JsonPrimitive("ask_hermes"),
                                                "description" to JsonPrimitive("Ask Hermes"),
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
            )
        )

        val message = codec.setupMessage(
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "Local instruction.",
        ).jsonObject()

        val functionCallingConfig = message["setup"]!!
            .jsonObject["toolConfig"]!!
            .jsonObject["functionCallingConfig"]!!
            .jsonObject
        assertEquals("ANY", functionCallingConfig["mode"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("ask_hermes"),
            functionCallingConfig["allowedFunctionNames"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `setup message strengthens ask hermes declaration description and behavior`() {
        val liveConnectConfig = JsonObject(
            mapOf(
                "tools" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "functionDeclarations" to JsonArray(
                                    listOf(
                                        JsonObject(
                                            mapOf(
                                                "name" to JsonPrimitive("ask_hermes"),
                                                "description" to JsonPrimitive("Ask Hermes"),
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
            )
        )

        val message = codec.setupMessage(
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "Local instruction.",
        ).jsonObject()

        val declaration = message["setup"]!!
            .jsonObject["tools"]!!
            .jsonArray[0]
            .jsonObject["functionDeclarations"]!!
            .jsonArray[0]
            .jsonObject
        assertEquals("ask_hermes", declaration["name"]!!.jsonPrimitive.content)
        assertEquals(
            VoiceAgentToolNames.ASK_HERMES_DESCRIPTION,
            declaration["description"]!!.jsonPrimitive.content,
        )
        assertEquals(
            VoiceAgentToolNames.ASK_HERMES_BEHAVIOR_NON_BLOCKING,
            declaration["behavior"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `setup message preserves non hermes function declarations while strengthening ask hermes`() {
        val liveConnectConfig = JsonObject(
            mapOf(
                "tools" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "functionDeclarations" to JsonArray(
                                    listOf(
                                        JsonObject(
                                            mapOf(
                                                "name" to JsonPrimitive("ask_hermes"),
                                                "description" to JsonPrimitive("Ask Hermes"),
                                            )
                                        ),
                                        JsonObject(
                                            mapOf(
                                                "name" to JsonPrimitive("other_tool"),
                                                "description" to JsonPrimitive("Other tool"),
                                            )
                                        ),
                                    )
                                )
                            )
                        )
                    )
                ),
            )
        )

        val message = codec.setupMessage(
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "Local instruction.",
        ).jsonObject()

        val declarations = message["setup"]!!
            .jsonObject["tools"]!!
            .jsonArray[0]
            .jsonObject["functionDeclarations"]!!
            .jsonArray
            .map { it.jsonObject }
        val askHermes = declarations.first { it["name"]!!.jsonPrimitive.content == "ask_hermes" }
        val otherTool = declarations.first { it["name"]!!.jsonPrimitive.content == "other_tool" }

        assertEquals(VoiceAgentToolNames.ASK_HERMES_DESCRIPTION, askHermes["description"]!!.jsonPrimitive.content)
        assertEquals(VoiceAgentToolNames.ASK_HERMES_BEHAVIOR_NON_BLOCKING, askHermes["behavior"]!!.jsonPrimitive.content)
        assertEquals("Other tool", otherTool["description"]!!.jsonPrimitive.content)
        assertFalse("behavior" in otherTool)
    }

    @Test
    fun `setup message preserves explicit server tool config`() {
        val explicitToolConfig = JsonObject(
            mapOf(
                "functionCallingConfig" to JsonObject(
                    mapOf(
                        "mode" to JsonPrimitive("AUTO"),
                    )
                )
            )
        )
        val liveConnectConfig = JsonObject(
            mapOf(
                "tools" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "functionDeclarations" to JsonArray(
                                    listOf(
                                        JsonObject(
                                            mapOf("name" to JsonPrimitive("ask_hermes"))
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
                "toolConfig" to explicitToolConfig,
            )
        )

        val message = codec.setupMessage(
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "Local instruction.",
        ).jsonObject()

        assertEquals(explicitToolConfig, message["setup"]!!.jsonObject["toolConfig"])
    }

    @Test
    fun `setup message omits optional live connect fields when config omits them`() {
        val liveConnectConfig = JsonObject(
            mapOf(
                "sessionToken" to JsonPrimitive("config-token"),
            )
        )

        val message = codec.setupMessage(
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "Local instruction.",
        ).jsonObject()

        val setup = message["setup"]!!.jsonObject
        assertEquals("models/gemini-2.0-flash-live-001", setup["model"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive("config-token"), setup["sessionToken"])
        assertFalse("responseModalities" in setup)
        assertFalse("generationConfig" in setup)
        assertFalse("inputAudioTranscription" in setup)
        assertFalse("outputAudioTranscription" in setup)
        assertFalse("tools" in setup)
        assertEquals(
            "Local instruction.",
            setup["systemInstruction"]!!
                .jsonObject["parts"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `client content message emits completed startup context turns`() {
        val message = codec.clientContentMessage(
            listOf(
                GeminiContentTurn(role = "user", text = "Hello"),
                GeminiContentTurn(role = "model", text = "Hi"),
            )
        ).jsonObject()

        val clientContent = message["clientContent"]!!.jsonObject
        assertTrue(clientContent["turnComplete"]!!.jsonPrimitive.boolean)
        val turns = clientContent["turns"]!!.jsonArray
        assertEquals("user", turns[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "Hello",
            turns[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals("model", turns[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "Hi",
            turns[1].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `realtime audio message emits pcm audio input`() {
        val message = codec.realtimeAudioMessage("base64-audio").jsonObject()

        val audio = message["realtimeInput"]!!
            .jsonObject["audio"]!!
            .jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        assertEquals("base64-audio", audio["data"]!!.jsonPrimitive.content)
        assertFalse("mediaChunks" in message["realtimeInput"]!!.jsonObject)
    }

    @Test
    fun `realtime audio stream end message flushes audio input`() {
        val message = codec.realtimeAudioStreamEndMessage().jsonObject()

        assertEquals(true, message["realtimeInput"]!!.jsonObject["audioStreamEnd"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `tool response message emits ask hermes answer`() {
        val message = codec.toolResponseMessage(callId = "call-1", answer = "42").jsonObject()

        val response = message["toolResponse"]!!
            .jsonObject["functionResponses"]!!
            .jsonArray[0]
            .jsonObject
        assertEquals("call-1", response["id"]!!.jsonPrimitive.content)
        assertEquals("ask_hermes", response["name"]!!.jsonPrimitive.content)
        assertEquals("42", response["response"]!!.jsonObject["answer"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parse server setup complete`() {
        assertEquals(
            GeminiLiveEvent.SetupComplete,
            codec.parseServerMessage("""{"setupComplete":{}}"""),
        )
    }

    @Test
    fun `parse server generation and turn completion signals`() {
        assertEquals(
            GeminiLiveEvent.GenerationComplete,
            codec.parseServerMessage("""{"serverContent":{"generationComplete":true}}"""),
        )
        assertEquals(
            GeminiLiveEvent.TurnComplete,
            codec.parseServerMessage("""{"serverContent":{"turnComplete":true}}"""),
        )
        assertTrue(
            codec.parseServerMessage("""{"serverContent":{"generationComplete":false,"turnComplete":false}}""")
                is GeminiLiveEvent.Ignored
        )
    }

    @Test
    fun `parse server message preserves transcript audio and completion events from same payload`() {
        assertEquals(
            GeminiLiveEvent.Events(
                listOf(
                    GeminiLiveEvent.OutputTranscript("final words"),
                    GeminiLiveEvent.OutputAudio("base64-pcm"),
                    GeminiLiveEvent.GenerationComplete,
                    GeminiLiveEvent.TurnComplete,
                )
            ),
            codec.parseServerMessage(
                """
                {
                  "serverContent":{
                    "outputTranscription":{"text":"final words"},
                    "modelTurn":{
                      "parts":[
                        {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"base64-pcm"}}
                      ]
                    },
                    "generationComplete":true,
                    "turnComplete":true
                  }
                }
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `parse server message preserves tool call and server content from same payload`() {
        assertEquals(
            GeminiLiveEvent.Events(
                listOf(
                    GeminiLiveEvent.ToolCall(
                        callId = "call-1",
                        name = "ask_hermes",
                        prompt = "Use Hermes",
                    ),
                    GeminiLiveEvent.OutputTranscript("I will check."),
                    GeminiLiveEvent.GenerationComplete,
                )
            ),
            codec.parseServerMessage(
                """
                {
                  "toolCall":{
                    "functionCalls":[
                      {
                        "id":"call-1",
                        "name":"ask_hermes",
                        "args":{"prompt":"Use Hermes"}
                      }
                    ]
                  },
                  "serverContent":{
                    "outputTranscription":{"text":"I will check."},
                    "generationComplete":true
                  }
                }
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `parse server transcriptions`() {
        assertEquals(
            GeminiLiveEvent.InputTranscript("heard"),
            codec.parseServerMessage("""{"serverContent":{"inputTranscription":{"text":"heard"}}}"""),
        )
        assertEquals(
            GeminiLiveEvent.OutputTranscript("spoken"),
            codec.parseServerMessage("""{"serverContent":{"outputTranscription":{"text":"spoken"}}}"""),
        )
    }

    @Test
    fun `parse server ignores non string transcription text`() {
        val raw = """{"serverContent":{"inputTranscription":{"text":123}}}"""

        assertEquals(GeminiLiveEvent.Ignored(raw), codec.parseServerMessage(raw))
    }

    @Test
    fun `parse server output audio inline data`() {
        val event = codec.parseServerMessage(
            """
            {
              "serverContent":{
                "modelTurn":{
                  "parts":[
                    {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"base64-pcm"}}
                  ]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(GeminiLiveEvent.OutputAudio(base64Pcm16 = "base64-pcm"), event)
        event as GeminiLiveEvent.OutputAudio
        assertEquals("base64-pcm", event.base64Pcm16)
    }

    @Test
    fun `parse server ignores non string audio data`() {
        val raw = """
            {
              "serverContent":{
                "modelTurn":{
                  "parts":[
                    {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":123}}
                  ]
                }
              }
            }
        """.trimIndent()

        assertEquals(GeminiLiveEvent.Ignored(raw), codec.parseServerMessage(raw))
    }

    @Test
    fun `parse server ignores non string audio mime type`() {
        val raw = """
            {
              "serverContent":{
                "modelTurn":{
                  "parts":[
                    {"inlineData":{"mimeType":true,"data":"base64-pcm"}}
                  ]
                }
              }
            }
        """.trimIndent()

        assertEquals(GeminiLiveEvent.Ignored(raw), codec.parseServerMessage(raw))
    }

    @Test
    fun `parse server ignores non audio inline data`() {
        val raw = """
            {
              "serverContent":{
                "modelTurn":{
                  "parts":[
                    {"inlineData":{"mimeType":"image/png","data":"base64-image"}}
                  ]
                }
              }
            }
        """.trimIndent()

        assertEquals(GeminiLiveEvent.Ignored(raw), codec.parseServerMessage(raw))
    }

    @Test
    fun `parse server interrupted boolean`() {
        assertEquals(
            GeminiLiveEvent.Interrupted(),
            codec.parseServerMessage("""{"serverContent":{"interrupted":true}}"""),
        )
        assertTrue(codec.parseServerMessage("""{"serverContent":{"interrupted":false}}""") is GeminiLiveEvent.Ignored)
    }

    @Test
    fun `parse server ignores malformed interrupted shape`() {
        val raw = """{"serverContent":{"interrupted":{"value":true}}}"""

        assertEquals(GeminiLiveEvent.Ignored(raw), codec.parseServerMessage(raw))
    }

    @Test
    fun `parse mixed tool call function with unsupported metadata`() {
        assertEquals(
            GeminiLiveEvent.ToolCalls(
                calls = listOf(
                    GeminiLiveEvent.ToolCall(
                        callId = "call-1",
                        name = "ask_hermes",
                        prompt = "What should I say?",
                    )
                ),
                unsupportedCalls = listOf(
                    GeminiLiveEvent.UnsupportedToolCall(
                        callId = "call-2",
                        name = "ignored",
                    )
                ),
            ),
            codec.parseServerMessage(
                """
                {
                  "toolCall":{
                    "functionCalls":[
                      {
                        "id":"call-1",
                        "name":"ask_hermes",
                        "args":{"prompt":"What should I say?"}
                      },
                      {
                        "id":"call-2",
                        "name":"ignored",
                        "args":{"prompt":"Ignore me"}
                      }
                    ]
                  }
                }
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `parse skips malformed tool calls and returns first valid function call`() {
        assertEquals(
            GeminiLiveEvent.ToolCall(
                callId = "call-2",
                name = "ask_hermes",
                prompt = "Use this prompt",
            ),
            codec.parseServerMessage(
                """
                {
                  "toolCall":{
                    "functionCalls":[
                      {
                        "id":"call-1",
                        "name":"ask_hermes",
                        "args":{}
                      },
                      {
                        "id":"call-2",
                        "name":"ask_hermes",
                        "args":{"prompt":"Use this prompt"}
                      }
                    ]
                  }
                }
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `parse multiple ask hermes tool calls in one frame`() {
        assertEquals(
            GeminiLiveEvent.ToolCalls(
                listOf(
                    GeminiLiveEvent.ToolCall(
                        callId = "call-1",
                        name = "ask_hermes",
                        prompt = "First prompt",
                    ),
                    GeminiLiveEvent.ToolCall(
                        callId = "call-2",
                        name = "ask_hermes",
                        prompt = "Second prompt",
                    ),
                )
            ),
            codec.parseServerMessage(
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
            ),
        )
    }

    @Test
    fun `parse skips tool calls with non string required fields`() {
        assertEquals(
            GeminiLiveEvent.ToolCall(
                callId = "call-valid",
                name = "ask_hermes",
                prompt = "Use this prompt",
            ),
            codec.parseServerMessage(
                """
                {
                  "toolCall":{
                    "functionCalls":[
                      {
                        "id":123,
                        "name":"ask_hermes",
                        "args":{"prompt":"Numeric id should not be actionable"}
                      },
                      {
                        "id":"call-non-string-name",
                        "name":true,
                        "args":{"prompt":"Boolean name should not be actionable"}
                      },
                      {
                        "id":"call-non-string-prompt",
                        "name":"ask_hermes",
                        "args":{"prompt":false}
                      },
                      {
                        "id":"call-null-prompt",
                        "name":"ask_hermes",
                        "args":{"prompt":null}
                      },
                      {
                        "id":"call-valid",
                        "name":"ask_hermes",
                        "args":{"prompt":"Use this prompt"}
                      }
                    ]
                  }
                }
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `parse preserves unsupported metadata when mixed tool calls include ask hermes`() {
        assertEquals(
            GeminiLiveEvent.ToolCalls(
                calls = listOf(
                    GeminiLiveEvent.ToolCall(
                        callId = "call-2",
                        name = "ask_hermes",
                        prompt = "Use this prompt",
                    )
                ),
                unsupportedCalls = listOf(
                    GeminiLiveEvent.UnsupportedToolCall(
                        callId = "call-1",
                        name = "unsupported_tool",
                    )
                ),
            ),
            codec.parseServerMessage(
                """
                {
                  "toolCall":{
                    "functionCalls":[
                      {
                        "id":"call-1",
                        "name":"unsupported_tool",
                        "args":{"prompt":"Do not use this prompt"}
                      },
                      {
                        "id":"call-2",
                        "name":"ask_hermes",
                        "args":{"prompt":"Use this prompt"}
                      }
                    ]
                  }
                }
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `parse preserves unsupported metadata when all tool calls are unsupported`() {
        val raw = """
            {
              "toolCall":{
                "functionCalls":[
                  {
                    "id":"call-1",
                    "name":"unsupported_tool",
                    "args":{"prompt":"Do not use this prompt"}
                  }
                ]
              }
            }
        """.trimIndent()

        assertEquals(
            GeminiLiveEvent.ToolCalls(
                calls = emptyList(),
                unsupportedCalls = listOf(
                    GeminiLiveEvent.UnsupportedToolCall(
                        callId = "call-1",
                        name = "unsupported_tool",
                    )
                ),
            ),
            codec.parseServerMessage(raw),
        )
    }

    @Test
    fun `parse ignores tool call with missing required fields`() {
        val raw = """
            {
              "toolCall":{
                "functionCalls":[
                  {
                    "id":"call-1",
                    "name":"ask_hermes",
                    "args":{}
                  }
                ]
              }
            }
        """.trimIndent()

        assertEquals(GeminiLiveEvent.Ignored(raw), codec.parseServerMessage(raw))
    }

    @Test
    fun `parse ignores tool call with blank required fields`() {
        val raw = """
            {
              "toolCall":{
                "functionCalls":[
                  {
                    "id":" ",
                    "name":"ask_hermes",
                    "args":{"prompt":"What should I say?"}
                  }
                ]
              }
            }
        """.trimIndent()

        assertEquals(GeminiLiveEvent.Ignored(raw), codec.parseServerMessage(raw))
    }

    @Test
    fun `parse tool call cancellation ids`() {
        assertEquals(
            GeminiLiveEvent.ToolCallCancellation(listOf("call-1", "call-2")),
            codec.parseServerMessage("""{"toolCallCancellation":{"ids":["call-1","call-2"]}}"""),
        )
    }

    @Test
    fun `parse ignores cancellation with no valid string ids`() {
        val raw = """{"toolCallCancellation":{"ids":[123,true,null," ",""]}}"""

        assertEquals(GeminiLiveEvent.Ignored(raw), codec.parseServerMessage(raw))
    }

    @Test
    fun `parse session resumption update`() {
        assertEquals(
            GeminiLiveEvent.SessionResumptionUpdate(
                newHandle = "resume-token",
                resumable = true,
            ),
            codec.parseServerMessage(
                """{"sessionResumptionUpdate":{"newHandle":"resume-token","resumable":true}}"""
            ),
        )
        assertEquals(
            GeminiLiveEvent.SessionResumptionUpdate(
                newHandle = null,
                resumable = false,
            ),
            codec.parseServerMessage("""{"sessionResumptionUpdate":{"resumable":false}}"""),
        )
    }

    @Test
    fun `parse ignores malformed session resumption update`() {
        val nonBooleanResumable = """{"sessionResumptionUpdate":{"newHandle":"resume-token","resumable":"true"}}"""
        val nonStringHandle = """{"sessionResumptionUpdate":{"newHandle":123,"resumable":true}}"""

        assertEquals(GeminiLiveEvent.Ignored(nonBooleanResumable), codec.parseServerMessage(nonBooleanResumable))
        assertEquals(GeminiLiveEvent.Ignored(nonStringHandle), codec.parseServerMessage(nonStringHandle))
    }

    @Test
    fun `invalid json returns error with raw text`() {
        val raw = "{not-json"

        val event = codec.parseServerMessage(raw)

        assertTrue(event is GeminiLiveEvent.Error)
        event as GeminiLiveEvent.Error
        assertTrue(event.message.isNotBlank())
        assertEquals(raw, event.raw)
    }

    @Test
    fun `unrecognized json is ignored with raw text`() {
        val raw = """{"serverContent":{"modelTurn":{"parts":[{"text":"not audio"}]}}}"""

        val event = codec.parseServerMessage(raw)

        assertEquals(GeminiLiveEvent.Ignored(raw), event)
    }

    private fun String.jsonObject(): JsonObject = JsonInstant.parseToJsonElement(this).jsonObject
}
