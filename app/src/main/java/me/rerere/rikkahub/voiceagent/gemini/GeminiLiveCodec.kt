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

internal data class VoiceToolSpec(
    val name: String,
    val argKey: String,
    val responseScheduling: String,
    val buildCall: (callId: String, argValue: String) -> GeminiLiveEvent.ToolCall,
    /** Client-policy fields merged into this tool's server-sent declaration. */
    val declarationDefaults: Map<String, JsonPrimitive> = emptyMap(),
)

internal val VOICE_TOOL_SPECS: List<VoiceToolSpec> = listOf(
    VoiceToolSpec(
        name = VoiceAgentToolNames.ASK_HERMES,
        argKey = "prompt",
        responseScheduling = VoiceAgentToolNames.ASK_HERMES_RESPONSE_SCHEDULING_WHEN_IDLE,
        buildCall = { callId, argValue -> GeminiLiveEvent.AskHermesCall(callId = callId, prompt = argValue) },
        declarationDefaults = mapOf(
            "description" to JsonPrimitive(VoiceAgentToolNames.ASK_HERMES_DESCRIPTION),
            "behavior" to JsonPrimitive(VoiceAgentToolNames.ASK_HERMES_BEHAVIOR_NON_BLOCKING),
        ),
    ),
    VoiceToolSpec(
        name = VoiceAgentToolNames.CANCEL_HERMES,
        argKey = "question",
        // WHEN_IDLE for cancel is deliberate (adjudicated in the UX-gaps review:
        // blocking + immediate at idle turn, no barge-in) — now explicit per tool.
        responseScheduling = VoiceAgentToolNames.ASK_HERMES_RESPONSE_SCHEDULING_WHEN_IDLE,
        buildCall = { callId, argValue -> GeminiLiveEvent.CancelHermesCall(callId = callId, question = argValue) },
    ),
)

internal val voiceToolSpecsByName: Map<String, VoiceToolSpec> = VOICE_TOOL_SPECS.associateBy { it.name }

data class EncodedMessage(
    val kind: String,
    val text: String,
    val audioDataBytes: Int? = null,
)

data class EncodedSetup(
    val message: EncodedMessage,
    val debug: GeminiLiveDebugEvent.Setup,
)

class GeminiLiveCodec(
    private val json: Json = JsonInstant,
) {
    fun setupMessage(
        providerModel: String,
        liveConnectConfig: JsonObject,
        systemInstruction: String,
        hasInitialContext: Boolean = false,
    ): EncodedSetup {
        val generationConfigElement = liveConnectConfig["generationConfig"]
        val generationConfig = generationConfigElement?.jsonObjectOrNull()
        val topLevelResponseModalities = liveConnectConfig["responseModalities"]
        val normalizedLiveConnectConfig = liveConnectConfig
            .withVoiceToolDeclarationDefaults()
            .withInitialHistoryConfig(hasInitialContext)

        val payload = buildJsonObject {
            putJsonObject("setup") {
                normalizedLiveConnectConfig.forEach { (key, value) ->
                    if (key != "responseModalities" && key != "generationConfig") {
                        put(key, value)
                    }
                }
                val declaredToolNames = VOICE_TOOL_SPECS.map { it.name }
                    .filter { normalizedLiveConnectConfig.declaresTool(it) }
                if ("toolConfig" !in normalizedLiveConnectConfig && declaredToolNames.isNotEmpty()) {
                    putJsonObject("toolConfig") {
                        putJsonObject("functionCallingConfig") {
                            put("mode", "ANY")
                            putJsonArray("allowedFunctionNames") {
                                declaredToolNames.forEach { add(JsonPrimitive(it)) }
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
        val setup = payload["setup"] as JsonObject
        return EncodedSetup(
            message = EncodedMessage(kind = "setup", text = json.encodeToString(payload)),
            debug = setup.toDebugSetup(),
        )
    }

    fun clientContentMessage(turns: List<GeminiContentTurn>): EncodedMessage = EncodedMessage(
        kind = "clientContent",
        text = json.encodeToString(
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
        ),
    )

    fun realtimeAudioMessage(base64Pcm16: String): EncodedMessage = EncodedMessage(
        kind = "realtimeInput.audio",
        text = json.encodeToString(
            buildJsonObject {
                putJsonObject("realtimeInput") {
                    putJsonObject("audio") {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Pcm16)
                    }
                }
            },
        ),
        audioDataBytes = base64Pcm16.estimatedBase64DecodedBytes(),
    )

    fun realtimeAudioStreamEndMessage(): EncodedMessage = EncodedMessage(
        kind = "realtimeInput.audioStreamEnd",
        text = json.encodeToString(
            buildJsonObject {
                putJsonObject("realtimeInput") {
                    put("audioStreamEnd", true)
                }
            },
        ),
    )

    fun toolResponseMessage(
        callId: String,
        answer: String,
        name: String = VoiceAgentToolNames.ASK_HERMES,
    ): EncodedMessage = EncodedMessage(
        kind = "toolResponse",
        text = json.encodeToString(
            buildJsonObject {
                putJsonObject("toolResponse") {
                    putJsonArray("functionResponses") {
                        add(
                            buildJsonObject {
                                put("id", callId)
                                put("name", name)
                                put(
                                    "scheduling",
                                    voiceToolSpecsByName[name]?.responseScheduling
                                        ?: VoiceAgentToolNames.ASK_HERMES_RESPONSE_SCHEDULING_WHEN_IDLE,
                                )
                                putJsonObject("response") {
                                    put("answer", answer)
                                }
                            }
                        )
                    }
                }
            },
        ),
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
            val spec = voiceToolSpecsByName[name] ?: run {
                unsupportedCalls += GeminiLiveEvent.UnsupportedToolCall(callId = callId, name = name)
                return@mapNotNull null
            }
            val prompt = functionCall["args"]
                ?.jsonObjectOrNull()
                ?.get(spec.argKey)
                ?.stringContentOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            spec.buildCall(callId, prompt)
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

    private fun JsonObject.declaresTool(name: String): Boolean =
        this["tools"]
            ?.jsonArrayOrNull()
            ?.any { tool ->
                tool.jsonObjectOrNull()
                    ?.get("functionDeclarations")
                    ?.jsonArrayOrNull()
                    ?.any { declaration ->
                        declaration.jsonObjectOrNull()
                            ?.get("name")
                            ?.stringContentOrNull() == name
                    } == true
            } == true

    /** Walk tools[].functionDeclarations[], applying transform to each declaration object. */
    private fun JsonObject.mapToolDeclarations(
        transform: (JsonObject) -> JsonObject,
    ): JsonObject {
        val tools = this["tools"]?.jsonArrayOrNull() ?: return this
        val mappedTools = buildJsonArray {
            tools.forEach { tool ->
                val toolObject = tool.jsonObjectOrNull()
                val declarations = toolObject?.get("functionDeclarations")?.jsonArrayOrNull()
                if (toolObject == null || declarations == null) {
                    add(tool)
                } else {
                    add(
                        JsonObject(
                            toolObject + mapOf(
                                "functionDeclarations" to buildJsonArray {
                                    declarations.forEach { declaration ->
                                        val declarationObject = declaration.jsonObjectOrNull()
                                        if (declarationObject == null) {
                                            add(declaration)
                                        } else {
                                            add(transform(declarationObject))
                                        }
                                    }
                                },
                            ),
                        ),
                    )
                }
            }
        }
        return JsonObject(this + mapOf("tools" to mappedTools))
    }

    /** Merge each tool's typed declarationDefaults into its server-sent declaration. */
    private fun JsonObject.withVoiceToolDeclarationDefaults(): JsonObject =
        mapToolDeclarations { declaration ->
            val name = declaration["name"]?.stringContentOrNull()
            val defaults = name?.let { voiceToolSpecsByName[it] }?.declarationDefaults
            if (defaults.isNullOrEmpty()) {
                declaration
            } else {
                JsonObject(declaration + defaults)
            }
        }

    private fun JsonObject.withInitialHistoryConfig(hasInitialContext: Boolean): JsonObject {
        if (!hasInitialContext) return this
        val historyConfig = this["historyConfig"]?.jsonObjectOrNull() ?: JsonObject(emptyMap())
        if ("initialHistoryInClientContent" in historyConfig) return this
        return JsonObject(
            this + mapOf(
                "historyConfig" to JsonObject(
                    historyConfig + mapOf("initialHistoryInClientContent" to JsonPrimitive(true)),
                ),
            ),
        )
    }

    private fun JsonObject.toDebugSetup(): GeminiLiveDebugEvent.Setup {
        val functionCallingConfig = this["toolConfig"]
            ?.jsonObjectOrNull()
            ?.get("functionCallingConfig")
            ?.jsonObjectOrNull()
        val generationConfig = this["generationConfig"]?.jsonObjectOrNull()
        return GeminiLiveDebugEvent.Setup(
            hasAskHermesTool = declaresTool(VoiceAgentToolNames.ASK_HERMES),
            toolConfigMode = functionCallingConfig
                ?.get("mode")
                ?.jsonPrimitiveOrNull()
                ?.contentOrNull,
            allowedFunctionNames = functionCallingConfig
                ?.get("allowedFunctionNames")
                ?.jsonArrayOrNull()
                ?.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }
                ?: emptyList(),
            responseModalities = generationConfig
                ?.get("responseModalities")
                ?.jsonArrayOrNull()
                ?.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }
                ?: emptyList(),
            systemInstructionChars = this["systemInstruction"]
                ?.jsonObjectOrNull()
                ?.get("parts")
                ?.jsonArrayOrNull()
                ?.sumOf { part ->
                    part.jsonObjectOrNull()
                        ?.get("text")
                        ?.jsonPrimitiveOrNull()
                        ?.contentOrNull
                        ?.length
                        ?: 0
                }
                ?: 0,
            realtimeInputConfig = geminiDebugRealtimeInputConfig(),
        )
    }

    private fun JsonObject.geminiDebugRealtimeInputConfig(): String? {
        val config = this["realtimeInputConfig"]?.jsonObjectOrNull() ?: return null
        val activityDetection = config["automaticActivityDetection"]?.jsonObjectOrNull()
        return if (activityDetection != null) {
            "automaticActivityDetection.disabled=${activityDetection.stringValue("disabled") ?: "n/a"} " +
                "start=${activityDetection.stringValue("startOfSpeechSensitivity") ?: "n/a"} " +
                "end=${activityDetection.stringValue("endOfSpeechSensitivity") ?: "n/a"} " +
                "prefixPaddingMs=${activityDetection.stringValue("prefixPaddingMs") ?: "n/a"} " +
                "silenceDurationMs=${activityDetection.stringValue("silenceDurationMs") ?: "n/a"}"
        } else {
            "automaticActivityDetection=missing"
        }
    }

    private fun JsonObject.stringValue(name: String): String? =
        get(name)?.jsonPrimitiveOrNull()?.contentOrNull

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

    private fun String.estimatedBase64DecodedBytes(): Int {
        val padding = takeLastWhile { it == '=' }.length.coerceAtMost(2)
        return ((length * 3) / 4 - padding).coerceAtLeast(0)
    }
}
