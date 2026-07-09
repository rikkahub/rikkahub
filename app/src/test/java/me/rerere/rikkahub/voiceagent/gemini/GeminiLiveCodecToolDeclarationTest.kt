package me.rerere.rikkahub.voiceagent.gemini

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                                "vendorToolField" to JsonPrimitive("tool-value"),
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
                                                "vendorDeclarationField" to JsonPrimitive("declaration-value"),
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

        val tool = codec.setupMessage(
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "Local instruction.",
        ).jsonObject()["setup"]!!
            .jsonObject["tools"]!!
            .jsonArray[0]
            .jsonObject

        assertEquals("tool-value", tool["vendorToolField"]!!.jsonPrimitive.content)
        assertEquals(VoiceAgentToolNames.ASK_HERMES_DESCRIPTION, askHermes["description"]!!.jsonPrimitive.content)
        assertEquals(VoiceAgentToolNames.ASK_HERMES_BEHAVIOR_NON_BLOCKING, askHermes["behavior"]!!.jsonPrimitive.content)
        assertEquals("Other tool", otherTool["description"]!!.jsonPrimitive.content)
        assertEquals("declaration-value", otherTool["vendorDeclarationField"]!!.jsonPrimitive.content)
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

    @Test
    fun `setup injects historyConfig when initial context is present`() {
        val encoded = GeminiLiveCodec().setupMessage(
            providerModel = "gemini-live",
            liveConnectConfig = configDeclaringAskHermes(),
            systemInstruction = "sys",
            hasInitialContext = true,
        )
        val setup = JsonInstant.parseToJsonElement(encoded.message.text).jsonObject["setup"]!!.jsonObject
        assertEquals(
            true,
            setup["historyConfig"]!!.jsonObject["initialHistoryInClientContent"]!!.jsonPrimitive.boolean,
        )
    }

    @Test
    fun `setup leaves an explicit historyConfig value alone`() {
        val config = JsonObject(
            configDeclaringAskHermes() + mapOf(
                "historyConfig" to buildJsonObject { put("initialHistoryInClientContent", false) },
            ),
        )
        val encoded = GeminiLiveCodec().setupMessage(
            providerModel = "gemini-live",
            liveConnectConfig = config,
            systemInstruction = "sys",
            hasInitialContext = true,
        )
        val setup = JsonInstant.parseToJsonElement(encoded.message.text).jsonObject["setup"]!!.jsonObject
        assertEquals(
            false,
            setup["historyConfig"]!!.jsonObject["initialHistoryInClientContent"]!!.jsonPrimitive.boolean,
        )
    }

    @Test
    fun `setup preserves existing historyConfig fields when injecting initial context flag`() {
        val config = JsonObject(
            configDeclaringAskHermes() + mapOf(
                "historyConfig" to buildJsonObject { put("otherHistoryField", "keep-me") },
            ),
        )
        val encoded = GeminiLiveCodec().setupMessage(
            providerModel = "gemini-live",
            liveConnectConfig = config,
            systemInstruction = "sys",
            hasInitialContext = true,
        )
        val historyConfig = JsonInstant.parseToJsonElement(encoded.message.text)
            .jsonObject["setup"]!!
            .jsonObject["historyConfig"]!!
            .jsonObject

        assertEquals("keep-me", historyConfig["otherHistoryField"]!!.jsonPrimitive.content)
        assertEquals(true, historyConfig["initialHistoryInClientContent"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `setup does not inject historyConfig without initial context`() {
        val encoded = GeminiLiveCodec().setupMessage(
            providerModel = "gemini-live",
            liveConnectConfig = configDeclaringAskHermes(),
            systemInstruction = "sys",
            hasInitialContext = false,
        )
        val setup = JsonInstant.parseToJsonElement(encoded.message.text).jsonObject["setup"]!!.jsonObject
        assertNull(setup["historyConfig"])
    }

    @Test
    fun `setup does not inject declaration defaults for cancel hermes`() {
        val encoded = GeminiLiveCodec().setupMessage(
            providerModel = "gemini-live",
            liveConnectConfig = JsonObject(
                mapOf(
                    "tools" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "functionDeclarations" to JsonArray(
                                        listOf(
                                            JsonObject(
                                                mapOf(
                                                    "name" to JsonPrimitive("cancel_hermes"),
                                                    "xCancelField" to JsonPrimitive("preserved"),
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    ),
                )
            ),
            systemInstruction = "sys",
        )
        val declaration = JsonInstant.parseToJsonElement(encoded.message.text)
            .jsonObject["setup"]!!
            .jsonObject["tools"]!!
            .jsonArray[0]
            .jsonObject["functionDeclarations"]!!
            .jsonArray[0]
            .jsonObject

        assertEquals("cancel_hermes", declaration["name"]!!.jsonPrimitive.content)
        assertEquals("preserved", declaration["xCancelField"]!!.jsonPrimitive.content)
        assertFalse("description" in declaration)
        assertFalse("behavior" in declaration)
    }

    private fun configDeclaringAskHermes(): JsonObject = JsonObject(
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

    private fun EncodedSetup.jsonObject(): JsonObject = message.text.jsonObject()

    private fun String.jsonObject(): JsonObject = JsonInstant.parseToJsonElement(this).jsonObject
}
