package me.rerere.rikkahub.voiceagent.gemini

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GeminiLiveCodecToolDeclarationTest {
    private val codec = GeminiLiveCodec()

    @Test
    fun `setup message strengthens ask hermes declaration description and behavior`() {
        val parameters = JsonObject(
            mapOf(
                "type" to JsonPrimitive("OBJECT"),
                "properties" to JsonObject(
                    mapOf(
                        "prompt" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("STRING"),
                            )
                        )
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("prompt"))),
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
                                            mapOf(
                                                "name" to JsonPrimitive("ask_hermes"),
                                                "description" to JsonPrimitive("Ask Hermes"),
                                                "parameters" to parameters,
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

        val declaration = codec.setupMessage(
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "Local instruction.",
        ).jsonObject()["setup"]!!
            .jsonObject["tools"]!!
            .jsonArray[0]
            .jsonObject["functionDeclarations"]!!
            .jsonArray[0]
            .jsonObject

        assertEquals("ask_hermes", declaration["name"]!!.jsonPrimitive.content)
        assertEquals(VoiceAgentToolNames.ASK_HERMES_DESCRIPTION, declaration["description"]!!.jsonPrimitive.content)
        assertEquals(VoiceAgentToolNames.ASK_HERMES_BEHAVIOR_NON_BLOCKING, declaration["behavior"]!!.jsonPrimitive.content)
        assertEquals(parameters, declaration["parameters"])
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

        val declarations = codec.setupMessage(
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "Local instruction.",
        ).jsonObject()["setup"]!!
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
    fun `setup message preserves non function tool objects while normalizing ask hermes`() {
        val legacyAskHermes = JsonObject(
            mapOf(
                "name" to JsonPrimitive("ask_hermes"),
                "description" to JsonPrimitive("Ask Hermes old description"),
                "behavior" to JsonPrimitive("UNKNOWN"),
            )
        )
        val nonFunctionTool = JsonObject(
            mapOf(
                "googleSearch" to JsonObject(emptyMap()),
            )
        )

        val liveConnectConfig = JsonObject(
            mapOf(
                "tools" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "functionDeclarations" to JsonArray(listOf(legacyAskHermes))
                            )
                        ),
                        nonFunctionTool,
                    )
                ),
            )
        )

        val tools = codec.setupMessage(
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "Local instruction.",
        ).jsonObject()["setup"]!!.jsonObject["tools"]!!.jsonArray

        val askHermesDeclaration = tools[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject
        assertEquals(VoiceAgentToolNames.ASK_HERMES_DESCRIPTION, askHermesDeclaration["description"]!!.jsonPrimitive.content)
        assertEquals(VoiceAgentToolNames.ASK_HERMES_BEHAVIOR_NON_BLOCKING, askHermesDeclaration["behavior"]!!.jsonPrimitive.content)
        assertEquals(2, tools.size)
        assertEquals(nonFunctionTool, tools[1])
    }

    private fun String.jsonObject(): JsonObject = JsonInstant.parseToJsonElement(this).jsonObject
}
