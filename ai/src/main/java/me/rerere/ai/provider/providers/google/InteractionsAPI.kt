package me.rerere.ai.provider.providers.google

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.GoogleInteractionsMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.configureSessionHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

private const val TAG = "InteractionsAPI"

/**
 * Gemini Interactions API (`POST /v1beta/interactions`)。
 *
 * 对话历史由客户端管理（分支、编辑、重新生成），因此始终使用无状态模式：`store=false`，
 * 每次请求在 `input` 中回传完整的 step 列表，而不是依赖 `previous_interaction_id`。
 */
internal class InteractionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette = KeyRoulette.default(),
) {
    suspend fun generateText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        val requestBody = buildRequestBody(messages, params, stream = false)
        val request = buildRequest(providerSetting, params, requestBody)

        // await() waits for the response headers; reading the body can still block.
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to get response: ${response.code} ${response.body.string()}")
            }
            val bodyJson = json.parseToJsonElement(response.body.string()).jsonObject
            parseInteraction(bodyJson, fallbackModel = params.model.modelId)
        }
    }

    fun streamText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val requestBody = buildRequestBody(messages, params, stream = true)
        val request = buildRequest(providerSetting, params, requestBody)

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        val decoder = InteractionsStreamDecoder(fallbackModel = params.model.modelId)

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            chunks.forEach { chunk ->
                trySend(chunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.d(TAG, "onEvent: $id/$type $data")
                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) close()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to parse stream event: $data", e)
                    close(e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        exception = json.parseToJsonElement(bodyRaw).parseErrorDetail()
                    } else if (t == null && response != null) {
                        exception = Exception("Unknown error: ${response.code}")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                } finally {
                    close(exception ?: Exception("Stream failed"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                sendChunks(decoder.onClosed())
                close()
            }
        }

        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    private fun buildRequest(
        providerSetting: ProviderSetting.Google,
        params: TextGenerationParams,
        requestBody: JsonObject,
    ): Request {
        val url = "${providerSetting.baseUrl}/interactions"
        return Request.Builder()
            .url(url)
            .headers(params.customHeaders.toHeaders())
            .configureSessionHeaders(url, params.sessionId)
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("x-goog-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()
    }

    internal fun buildRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", params.model.modelId)
        put("stream", stream)
        put("store", false)

        val outputsImage = params.model.outputModalities.contains(Modality.IMAGE)

        val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        if (systemMessage != null && !outputsImage) {
            put(
                "system_instruction",
                systemMessage.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            )
        }

        put("input", buildInput(messages))

        val generationConfig = buildJsonObject {
            if (params.temperature != null) put("temperature", params.temperature)
            if (params.topP != null) put("top_p", params.topP)
            if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                put("thinking_summaries", "auto")
                thinkingLevel(params)?.let { put("thinking_level", it) }
            }
        }
        if (generationConfig.isNotEmpty()) put("generation_config", generationConfig)

        if (outputsImage) {
            putJsonArray("response_format") {
                add(buildJsonObject { put("type", "text") })
                add(buildJsonObject { put("type", "image") })
            }
        }

        // Interactions API 的 tools 是扁平数组，函数工具和内置工具写在同一个 key 下
        val useFunctionTools =
            params.tools.isNotEmpty() && params.model.abilities.contains(ModelAbility.TOOL)
        val builtInTools = params.model.tools.mapNotNull { builtInTool ->
            when (builtInTool) {
                BuiltInTools.Search -> "google_search"
                BuiltInTools.UrlContext -> "url_context"
                else -> null
            }
        }
        if (useFunctionTools || builtInTools.isNotEmpty()) {
            putJsonArray("tools") {
                if (useFunctionTools) {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", json.encodeToJsonElement(tool.parameters()))
                        })
                    }
                }
                builtInTools.forEach { type ->
                    add(buildJsonObject { put("type", type) })
                }
            }
        }

        // safety_settings 只在 Vertex 上可用，Gemini API 的 Interactions 会直接拒绝该参数
    }.mergeCustomBody(params.customBody)

    // Interactions API 只接受 thinking_level，没有 thinkingBudget
    private fun thinkingLevel(params: TextGenerationParams): String? = when (params.reasoningLevel) {
        ReasoningLevel.AUTO -> null
        ReasoningLevel.OFF -> {
            val supportsMinimal = ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId) ||
                ModelRegistry.GEMINI_4.match(modelId = params.model.modelId)
            if (supportsMinimal) "minimal" else "low"
        }
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        else -> "high" // HIGH, XHIGH, MAX
    }

    internal fun buildInput(messages: List<UIMessage>): JsonArray = buildJsonArray {
        messages
            .filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addModelSteps(message)
                } else {
                    addUserStep(message)
                }
            }
    }

    private fun JsonArrayBuilder.addUserStep(message: UIMessage) {
        val content = message.parts.mapNotNull { it.toInteractionsContent() }
        if (content.isEmpty()) return
        add(buildJsonObject {
            put("type", "user_input")
            putJsonArray("content") { content.forEach { add(it) } }
        })
    }

    private fun JsonArrayBuilder.addModelSteps(message: UIMessage) {
        val contentBuffer = mutableListOf<JsonObject>()

        fun flushContent() {
            if (contentBuffer.isEmpty()) return
            val content = contentBuffer.toList()
            contentBuffer.clear()
            add(buildJsonObject {
                put("type", "model_output")
                putJsonArray("content") { content.forEach { add(it) } }
            })
        }

        for (group in groupPartsByToolBoundary(message.parts)) {
            when (group) {
                is PartGroup.Content -> group.parts.forEach { part ->
                    when (part) {
                        is UIMessagePart.Reasoning -> {
                            // 没有 Interactions 签名的思考来自其他协议，无法作为 thought step 回传
                            val signature = part.metadataAs<GoogleInteractionsMetadata>()?.signature
                                ?: return@forEach
                            flushContent()
                            add(buildJsonObject {
                                put("type", "thought")
                                put("signature", signature)
                                if (part.reasoning.isNotEmpty()) {
                                    putJsonArray("summary") {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", part.reasoning)
                                        })
                                    }
                                }
                            })
                        }

                        is UIMessagePart.ServerTool -> {
                            val steps = part.toInteractionsServerToolSteps()
                            if (steps.isNotEmpty()) {
                                flushContent()
                                steps.forEach { add(it) }
                            }
                        }

                        is UIMessagePart.Text, is UIMessagePart.Image ->
                            part.toInteractionsContent()?.let { contentBuffer.add(it) }

                        else -> {}
                    }
                }

                is PartGroup.Tools -> {
                    flushContent()
                    // 同一批并发工具调用需先输出全部 function_call，再输出对应结果
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("id", tool.toolCallId)
                            put("name", tool.toolName)
                            put("arguments", tool.inputAsJson())
                            tool.metadataAs<GoogleInteractionsMetadata>()?.signature?.let {
                                put("signature", it)
                            }
                        })
                    }
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_result")
                            put("call_id", tool.toolCallId)
                            put("name", tool.toolName)
                            putJsonArray("result") {
                                val content = tool.output
                                    .filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
                                    .mapNotNull { it.toInteractionsContent() }
                                if (content.isEmpty()) {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", " ")
                                    })
                                } else {
                                    content.forEach { add(it) }
                                }
                            }
                        })
                    }
                }
            }
        }

        flushContent()
    }

    private fun UIMessagePart.toInteractionsContent(): JsonObject? = when (this) {
        is UIMessagePart.Text -> if (text.isEmpty()) null else buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        is UIMessagePart.Image -> encodeBase64(false).getOrNull()?.let { encoded ->
            buildJsonObject {
                put("type", "image")
                if (encoded.base64.startsWith("http")) {
                    put("uri", encoded.base64)
                } else {
                    put("mime_type", encoded.mimeType)
                    // data URL 会原样返回，这里只取 base64 数据部分
                    put("data", encoded.base64.substringAfter(";base64,"))
                }
            }
        }

        is UIMessagePart.Video -> encodeBase64(false).getOrNull()?.let { base64Data ->
            buildJsonObject {
                put("type", "video")
                put("mime_type", "video/mp4")
                put("data", base64Data)
            }
        }

        is UIMessagePart.Audio -> encodeBase64(false).getOrNull()?.let { base64Data ->
            buildJsonObject {
                put("type", "audio")
                put("mime_type", "audio/mp3")
                put("data", base64Data)
            }
        }

        else -> null
    }

    private fun UIMessagePart.ServerTool.toInteractionsServerToolSteps(): List<JsonObject> {
        val metadata = metadataAs<ServerToolMetadata>() ?: return emptyList()
        if (metadata.protocol != ServerToolProtocol.GOOGLE_INTERACTIONS) return emptyList()
        // call 与 result 必须成对回传，流被中断导致缺少结果时整体丢弃
        val call = metadata.call ?: return emptyList()
        val result = metadata.result ?: return emptyList()
        return listOf(call, result)
    }

    internal fun parseInteraction(interaction: JsonObject, fallbackModel: String? = null): TextGenerationResult {
        val parts = mutableListOf<UIMessagePart>()
        val annotations = mutableListOf<UIMessageAnnotation>()

        interaction["steps"]?.jsonArrayOrNull.orEmpty().forEachIndexed { index, element ->
            val step = element.jsonObjectOrNull ?: return@forEachIndexed
            val type = step.stringOrNull("type") ?: return@forEachIndexed
            when {
                type == "thought" -> {
                    val now = Clock.System.now()
                    parts.add(
                        UIMessagePart.Reasoning(
                            reasoning = step["summary"]?.jsonArrayOrNull.orEmpty()
                                .mapNotNull { it.jsonObjectOrNull?.stringOrNull("text") }
                                .joinToString(""),
                            createdAt = now,
                            finishedAt = now,
                            metadata = interactionsSignatureMetadata(step.stringOrNull("signature")),
                        )
                    )
                }

                type == "model_output" -> step["content"]?.jsonArrayOrNull.orEmpty().forEach { item ->
                    val content = item.jsonObjectOrNull ?: return@forEach
                    when (content.stringOrNull("type")) {
                        "text" -> {
                            parts.add(UIMessagePart.Text(content.stringOrNull("text") ?: ""))
                            annotations.addAll(content["annotations"].toInteractionsAnnotations())
                        }

                        "image" -> content.stringOrNull("data")?.let { data ->
                            val mimeType = content.stringOrNull("mime_type") ?: "image/png"
                            parts.add(UIMessagePart.Image(url = "data:$mimeType;base64,$data"))
                        }
                    }
                }

                type == "function_call" -> parts.add(
                    UIMessagePart.Tool(
                        toolCallId = step.stringOrNull("id") ?: "${interaction.stringOrNull("id")}:tool-$index",
                        toolName = step.stringOrNull("name") ?: "",
                        input = step["arguments"]?.let(json::encodeToString) ?: "",
                        output = emptyList(),
                        metadata = interactionsSignatureMetadata(step.stringOrNull("signature")),
                    )
                )

                isInteractionsServerToolCall(type) -> parts.add(
                    UIMessagePart.ServerTool(
                        toolCallId = step.stringOrNull("id") ?: "",
                        toolName = type.removeSuffix("_call"),
                        input = step["arguments"],
                        status = ServerToolStatus.IN_PROGRESS,
                        metadata = ServerToolMetadata(
                            protocol = ServerToolProtocol.GOOGLE_INTERACTIONS,
                            call = step,
                            callIndex = index,
                        ).toMetadata(),
                    )
                )

                isInteractionsServerToolResult(type) -> {
                    val callId = step.stringOrNull("call_id") ?: ""
                    val resultMetadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.GOOGLE_INTERACTIONS,
                        result = step,
                        resultIndex = index,
                    ).toMetadata()
                    val existingIndex = parts.indexOfFirst {
                        it is UIMessagePart.ServerTool && it.toolCallId == callId
                    }
                    if (existingIndex < 0) {
                        parts.add(
                            UIMessagePart.ServerTool(
                                toolCallId = callId,
                                toolName = type.removeSuffix("_result"),
                                output = step["result"],
                                status = step.interactionsServerToolResultStatus(),
                                metadata = resultMetadata,
                            )
                        )
                    } else {
                        val existing = parts[existingIndex] as UIMessagePart.ServerTool
                        parts[existingIndex] = existing.copy(
                            output = step["result"],
                            status = step.interactionsServerToolResultStatus(),
                            metadata = JsonObject(existing.metadata.orEmpty() + resultMetadata),
                        )
                    }
                }
            }
        }

        return TextGenerationResult(
            id = interaction.stringOrNull("id") ?: "",
            model = interaction.stringOrNull("model") ?: fallbackModel ?: "",
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = parts,
                annotations = annotations.distinct(),
            ),
            finishReason = interaction.stringOrNull("status"),
            usage = parseInteractionsUsage(interaction["usage"]?.jsonObjectOrNull),
        )
    }
}

internal fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitiveOrNull?.contentOrNull

internal fun interactionsSignatureMetadata(signature: String?): JsonObject? =
    signature?.takeIf { it.isNotEmpty() }?.let { GoogleInteractionsMetadata(signature = it).toMetadata() }

// function_call / function_result 由客户端执行，其余 *_call / *_result 都是服务端工具
internal fun isInteractionsServerToolCall(type: String): Boolean =
    type.endsWith("_call") && type != "function_call"

internal fun isInteractionsServerToolResult(type: String): Boolean =
    type.endsWith("_result") && type != "function_result"

internal fun JsonObject.interactionsServerToolResultStatus(): ServerToolStatus =
    if (this["is_error"]?.jsonPrimitiveOrNull?.booleanOrNull == true) {
        ServerToolStatus.FAILED
    } else {
        ServerToolStatus.COMPLETED
    }

internal fun JsonElement?.toInteractionsAnnotations(): List<UIMessageAnnotation> =
    this?.jsonArrayOrNull.orEmpty().mapNotNull { element ->
        val annotation = element.jsonObjectOrNull ?: return@mapNotNull null
        if (annotation.stringOrNull("type") != "url_citation") return@mapNotNull null
        val url = annotation.stringOrNull("url") ?: return@mapNotNull null
        UIMessageAnnotation.UrlCitation(title = annotation.stringOrNull("title") ?: url, url = url)
    }

internal fun parseInteractionsUsage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    fun count(key: String) = usage[key]?.jsonPrimitiveOrNull?.intOrNull ?: 0
    return TokenUsage(
        promptTokens = count("total_input_tokens"),
        // total_output_tokens 不包含思考 token
        completionTokens = count("total_output_tokens") + count("total_thought_tokens"),
        totalTokens = count("total_tokens"),
        cachedTokens = count("total_cached_tokens"),
    )
}
