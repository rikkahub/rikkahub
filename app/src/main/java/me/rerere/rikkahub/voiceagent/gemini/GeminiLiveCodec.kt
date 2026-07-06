package me.rerere.rikkahub.voiceagent.gemini

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames

class GeminiLiveCodec(
    private val json: Json = JsonInstant,
) {
    fun setupMessage(
        providerModel: String,
        liveConnectConfig: JsonObject,
        systemInstruction: String,
    ): String {
        val generationConfigElement = liveConnectConfig["generationConfig"]
        val generationConfig = generationConfigElement?.jsonObjectOrNull()
        val topLevelResponseModalities = liveConnectConfig["responseModalities"]
        val normalizedLiveConnectConfig = liveConnectConfig.withAskHermesSetupDefaults()

        return json.encodeToString(
            buildJsonObject {
                putJsonObject("setup") {
                    normalizedLiveConnectConfig.forEach { (key, value) ->
                        if (key != "responseModalities" && key != "generationConfig") {
                            put(key, value)
                        }
                    }
                    if ("toolConfig" !in normalizedLiveConnectConfig && normalizedLiveConnectConfig.declaresAskHermesTool()) {
                        putJsonObject("toolConfig") {
                            putJsonObject("functionCallingConfig") {
                                put("mode", "ANY")
                                putJsonArray("allowedFunctionNames") {
                                    add(JsonPrimitive(VoiceAgentToolNames.ASK_HERMES))
                                }
                            }
                        }
                    }
                    put("model", "models/$providerModel")
                    if (generationConfig != null || topLevelResponseModalities != null) {
                        put(
                            "generationConfig",
                            buildJsonObject {
                                generationConfig?.forEach { (key, value) -> put(key, value) }
                                topLevelResponseModalities?.let { put("responseModalities", it) }
                            },
                        )
                    } else if (generationConfigElement != null) {
                        put("generationConfig", generationConfigElement)
                    }
                    putJsonObject("systemInstruction") {
                        putJsonArray("parts") {
                            add(
                                buildJsonObject {
                                    put("text", systemInstruction)
                                }
                            )
                        }
                    }
                }
            }
        )
    }

    fun clientContentMessage(turns: List<GeminiContentTurn>): String = json.encodeToString(
        buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    turns.forEach { turn ->
                        add(
                            buildJsonObject {
                                put("role", turn.role)
                                putJsonArray("parts") {
                                    add(
                                        buildJsonObject {
                                            put("text", turn.text)
                                        }
                                    )
                                }
                            }
                        )
                    }
                }
                put("turnComplete", true)
            }
        }
    )

    fun realtimeAudioMessage(base64Pcm16: String): String = json.encodeToString(
        buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64Pcm16)
                }
            }
        }
    )

    fun realtimeAudioStreamEndMessage(): String = json.encodeToString(
        buildJsonObject {
            putJsonObject("realtimeInput") {
                put("audioStreamEnd", true)
            }
        }
    )

    fun toolResponseMessage(callId: String, answer: String): String = json.encodeToString(
        buildJsonObject {
            putJsonObject("toolResponse") {
                putJsonArray("functionResponses") {
                    add(
                        buildJsonObject {
                            put("id", callId)
                            put("name", VoiceAgentToolNames.ASK_HERMES)
                            put("scheduling", VoiceAgentToolNames.ASK_HERMES_RESPONSE_SCHEDULING_WHEN_IDLE)
                            putJsonObject("response") {
                                put("answer", answer)
                            }
                        }
                    )
                }
            }
        }
    )

    fun parseServerMessage(text: String): GeminiLiveEvent {
        val element = runCatching { json.parseToJsonElement(text) }.getOrElse { error ->
            return GeminiLiveEvent.Error(
                message = error.message ?: error.javaClass.simpleName,
                raw = text,
            )
        }
        val root = element as? JsonObject ?: return GeminiLiveEvent.Ignored(text)

        val events = buildList {
            if ("setupComplete" in root) {
                add(GeminiLiveEvent.SetupComplete)
            }
            if ("sessionResumptionUpdate" in root) {
                root.sessionResumptionUpdate(text)
                    .takeUnless { it is GeminiLiveEvent.Ignored }
                    ?.let(::add)
            }
            root.toolCallEvent()?.let(::add)
            root.toolCallCancellation()?.let(::add)
            addAll(root.serverContentEvents())
        }

        return events.toEventOrIgnored(raw = text)
    }

    private fun JsonObject.serverContentEvents(): List<GeminiLiveEvent> {
        val serverContent = this["serverContent"] as? JsonObject ?: return emptyList()
        return buildList {
            if (serverContent["interrupted"]?.jsonPrimitiveOrNull()?.booleanOrNull == true) {
                add(GeminiLiveEvent.Interrupted())
            }
            serverContent.transcript("inputTranscription")?.let { add(GeminiLiveEvent.InputTranscript(it)) }
            serverContent.transcript("outputTranscription")?.let { add(GeminiLiveEvent.OutputTranscript(it)) }
            serverContent.outputAudio()?.let { add(GeminiLiveEvent.OutputAudio(it)) }
            if (serverContent["generationComplete"]?.jsonPrimitiveOrNull()?.booleanOrNull == true) {
                add(GeminiLiveEvent.GenerationComplete)
            }
            if (serverContent["turnComplete"]?.jsonPrimitiveOrNull()?.booleanOrNull == true) {
                add(GeminiLiveEvent.TurnComplete)
            }
        }
    }

    private fun List<GeminiLiveEvent>.toEventOrIgnored(raw: String): GeminiLiveEvent = when (size) {
        0 -> GeminiLiveEvent.Ignored(raw)
        1 -> single()
        else -> GeminiLiveEvent.Events(this)
    }

    private fun JsonObject.toolCallEvent(): GeminiLiveEvent? {
        val functionCalls = this["toolCall"]
            ?.jsonObjectOrNull()
            ?.get("functionCalls")
            ?.jsonArrayOrNull()
            ?: return null
        val unsupportedCalls = mutableListOf<GeminiLiveEvent.UnsupportedToolCall>()
        val calls = functionCalls.mapNotNull { functionCallElement ->
            val functionCall = functionCallElement.jsonObjectOrNull() ?: return@mapNotNull null
            val callId = functionCall["id"]?.stringContentOrNull()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val name = functionCall["name"]?.stringContentOrNull()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (name != VoiceAgentToolNames.ASK_HERMES) {
                unsupportedCalls += GeminiLiveEvent.UnsupportedToolCall(
                    callId = callId,
                    name = name,
                )
                return@mapNotNull null
            }
            val prompt = functionCall["args"]
                    ?.jsonObjectOrNull()
                    ?.get("prompt")
                    ?.stringContentOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
            GeminiLiveEvent.ToolCall(
                callId = callId,
                name = name,
                prompt = prompt,
            )
        }
        return when (calls.size) {
            0 -> if (unsupportedCalls.isEmpty()) null else {
                GeminiLiveEvent.ToolCalls(calls = emptyList(), unsupportedCalls = unsupportedCalls)
            }
            1 -> if (unsupportedCalls.isEmpty()) {
                calls.first()
            } else {
                GeminiLiveEvent.ToolCalls(calls = calls, unsupportedCalls = unsupportedCalls)
            }
            else -> GeminiLiveEvent.ToolCalls(calls = calls, unsupportedCalls = unsupportedCalls)
        }
    }

    private fun JsonObject.toolCallCancellation(): GeminiLiveEvent.ToolCallCancellation? {
        val ids = this["toolCallCancellation"]
            ?.jsonObjectOrNull()
            ?.get("ids")
            ?.jsonArrayOrNull()
            ?.mapNotNull { it.stringContentOrNull()?.takeIf { id -> id.isNotBlank() } }
            ?: return null
        if (ids.isEmpty()) return null
        return GeminiLiveEvent.ToolCallCancellation(ids)
    }

    private fun JsonObject.declaresAskHermesTool(): Boolean =
        this["tools"]
            ?.jsonArrayOrNull()
            ?.any { tool ->
                tool.jsonObjectOrNull()
                    ?.get("functionDeclarations")
                    ?.jsonArrayOrNull()
                    ?.any { declaration ->
                        declaration.jsonObjectOrNull()
                            ?.get("name")
                            ?.stringContentOrNull() == VoiceAgentToolNames.ASK_HERMES
                    } == true
            } == true

    private fun JsonObject.withAskHermesSetupDefaults(): JsonObject {
        if (!declaresAskHermesTool()) return this
        return buildJsonObject {
            forEach { (key, value) ->
                put(
                    key,
                    if (key == "tools") value.withAskHermesToolDefaults() else value,
                )
            }
        }
    }

    private fun JsonElement.withAskHermesToolDefaults(): JsonElement {
        val tools = jsonArrayOrNull() ?: return this
        return buildJsonArray {
            tools.forEach { tool ->
                val toolObject = tool.jsonObjectOrNull()
                if (toolObject == null || "functionDeclarations" !in toolObject) {
                    add(tool)
                } else {
                    add(toolObject.withAskHermesFunctionDeclarations())
                }
            }
        }
    }

    private fun JsonObject.withAskHermesFunctionDeclarations(): JsonObject = buildJsonObject {
        forEach { (key, value) ->
            put(
                key,
                if (key == "functionDeclarations") value.withAskHermesFunctionDeclarationDefaults() else value,
            )
        }
    }

    private fun JsonElement.withAskHermesFunctionDeclarationDefaults(): JsonElement {
        val declarations = jsonArrayOrNull() ?: return this
        return buildJsonArray {
            declarations.forEach { declaration ->
                val declarationObject = declaration.jsonObjectOrNull()
                if (declarationObject?.get("name")?.stringContentOrNull() == VoiceAgentToolNames.ASK_HERMES) {
                    add(declarationObject.withAskHermesSingleDeclarationDefaults())
                } else {
                    add(declaration)
                }
            }
        }
    }

    private fun JsonObject.withAskHermesSingleDeclarationDefaults(): JsonObject = buildJsonObject {
        forEach { (key, value) ->
            put(key, value)
        }
        put("description", JsonPrimitive(VoiceAgentToolNames.ASK_HERMES_DESCRIPTION))
        put("behavior", JsonPrimitive(VoiceAgentToolNames.ASK_HERMES_BEHAVIOR_NON_BLOCKING))
    }

    private fun JsonObject.sessionResumptionUpdate(raw: String): GeminiLiveEvent {
        val update = this["sessionResumptionUpdate"]?.jsonObjectOrNull()
            ?: return GeminiLiveEvent.Ignored(raw)
        val resumable = update["resumable"]?.booleanContentOrNull()
            ?: return GeminiLiveEvent.Ignored(raw)
        val newHandle = if ("newHandle" in update) {
            update["newHandle"]?.stringContentOrNull()
                ?: return GeminiLiveEvent.Ignored(raw)
        } else {
            null
        }
        return GeminiLiveEvent.SessionResumptionUpdate(
            newHandle = newHandle,
            resumable = resumable,
        )
    }

    private fun JsonObject.transcript(key: String): String? =
        this[key]
            ?.jsonObjectOrNull()
            ?.get("text")
            ?.stringContentOrNull()

    private fun JsonObject.outputAudio(): String? =
        this["modelTurn"]
            ?.jsonObjectOrNull()
            ?.get("parts")
            ?.jsonArrayOrNull()
            ?.firstNotNullOfOrNull { part ->
                val inlineData = part.jsonObjectOrNull()
                    ?.get("inlineData")
                    ?.jsonObjectOrNull()
                val mimeType = inlineData
                    ?.get("mimeType")
                    ?.stringContentOrNull()
                inlineData
                    ?.takeIf { mimeType?.startsWith("audio/pcm") == true }
                    ?.get("data")
                    ?.stringContentOrNull()
            }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    private fun JsonElement.stringContentOrNull(): String? {
        val primitive = jsonPrimitiveOrNull() ?: return null
        return primitive.takeIf { it.isString }?.contentOrNull
    }

    private fun JsonElement.booleanContentOrNull(): Boolean? {
        val primitive = jsonPrimitiveOrNull() ?: return null
        return primitive.takeUnless { it.isString }?.booleanOrNull
    }
}
