package me.rerere.ai.provider.providers.claude

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
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

private const val TAG = "ClaudeProvider"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val CLAUDE_PAUSE_TURN = "pause_turn"
private const val MAX_PAUSE_TURN_CONTINUATIONS = 5

internal suspend fun generateClaudeWithPauseTurn(
    messages: List<UIMessage>,
    model: Model,
    maxContinuations: Int = MAX_PAUSE_TURN_CONTINUATIONS,
    request: suspend (List<UIMessage>) -> TextGenerationResult,
): TextGenerationResult {
    require(maxContinuations >= 0) { "maxContinuations must be non-negative" }

    var requestMessages = messages
    var combinedMessage = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
    var combinedUsage: TokenUsage? = null
    var nextServerToolBlockIndex = 0

    repeat(maxContinuations + 1) { continuationCount ->
        val rawResult = request(requestMessages)
        val result = rawResult.copy(
            message = rawResult.message.rebaseClaudeServerToolIndexes(nextServerToolBlockIndex),
        )
        result.message.maxClaudeServerToolIndex()?.let { maxIndex ->
            nextServerToolBlockIndex = maxOf(nextServerToolBlockIndex, maxIndex + 1)
        }
        combinedMessage = listOf(combinedMessage)
            .handleTextGenerationResult(result, model)
            .last()
        combinedUsage = combinedUsage.sum(result.usage)

        if (result.finishReason != CLAUDE_PAUSE_TURN || continuationCount == maxContinuations) {
            return result.copy(
                message = combinedMessage.copy(usage = combinedUsage),
                usage = combinedUsage,
            )
        }

        // pause_turn 要求原样回放当前 assistant 响应，不能额外插入 "Continue" user 消息。
        requestMessages = requestMessages.handleTextGenerationResult(result, model)
    }

    error("unreachable")
}

internal fun streamClaudeWithPauseTurn(
    messages: List<UIMessage>,
    model: Model,
    maxContinuations: Int = MAX_PAUSE_TURN_CONTINUATIONS,
    request: (List<UIMessage>) -> Flow<StreamChunk>,
): Flow<StreamChunk> = flow {
    require(maxContinuations >= 0) { "maxContinuations must be non-negative" }

    var requestMessages = messages
    var completedUsage: TokenUsage? = null
    var nextServerToolBlockIndex = 0

    repeat(maxContinuations + 1) { continuationCount ->
        val handler = StreamChunkHandler(model)
        var responseMessages = requestMessages
        var passUsage: TokenUsage? = null
        var finish: StreamChunk.Finish? = null
        val responseIndexOffset = nextServerToolBlockIndex

        request(requestMessages).collect { rawChunk ->
            val chunk = rawChunk.rebaseClaudeServerToolIndexes(responseIndexOffset)
            chunk.maxClaudeServerToolIndex()?.let { maxIndex ->
                nextServerToolBlockIndex = maxOf(nextServerToolBlockIndex, maxIndex + 1)
            }
            responseMessages = handler.handle(responseMessages, chunk)
            when (chunk) {
                is StreamChunk.Usage -> {
                    passUsage = passUsage.merge(chunk.usage)
                    completedUsage.sum(passUsage)?.let { emit(StreamChunk.Usage(it)) }
                }

                is StreamChunk.Finish -> {
                    finish = chunk
                    if (chunk.finishReason != CLAUDE_PAUSE_TURN ||
                        continuationCount == maxContinuations
                    ) {
                        emit(chunk)
                    }
                }

                else -> emit(chunk)
            }
        }

        if (finish?.finishReason != CLAUDE_PAUSE_TURN || continuationCount == maxContinuations) {
            return@flow
        }

        completedUsage = completedUsage.sum(passUsage)
        requestMessages = responseMessages
    }
}

/**
 * Claude content block index 只在单次响应内有效。pause_turn 会把多次响应合并为一个逻辑
 * assistant turn，因此在合并前将后续响应的 server tool index 偏移到同一序号空间。
 */
private fun UIMessage.rebaseClaudeServerToolIndexes(offset: Int): UIMessage = copy(
    parts = parts.map { part ->
        if (part !is UIMessagePart.ServerTool) return@map part
        part.copy(metadata = part.metadata.rebaseClaudeServerToolIndexes(offset))
    },
)

private fun StreamChunk.rebaseClaudeServerToolIndexes(offset: Int): StreamChunk = when (this) {
    is StreamChunk.ServerToolStart -> copy(metadata = metadata.rebaseClaudeServerToolIndexes(offset))
    is StreamChunk.ServerToolEnd -> copy(metadata = metadata.rebaseClaudeServerToolIndexes(offset))
    else -> this
}

private fun JsonObject?.rebaseClaudeServerToolIndexes(offset: Int): JsonObject? {
    if (this == null) return null
    val metadata = runCatching {
        json.decodeFromJsonElement<ServerToolMetadata>(this)
    }.getOrNull()?.takeIf { it.protocol == ServerToolProtocol.ANTHROPIC_MESSAGES }
        ?: return this
    if (metadata.callIndex == null && metadata.resultIndex == null) return this
    return JsonObject(this + metadata.rebaseIndexes(offset).toMetadata())
}

private fun ServerToolMetadata.rebaseIndexes(offset: Int): ServerToolMetadata = copy(
    callIndex = callIndex?.plus(offset),
    resultIndex = resultIndex?.plus(offset),
)

private fun UIMessage.maxClaudeServerToolIndex(): Int? = parts
    .filterIsInstance<UIMessagePart.ServerTool>()
    .mapNotNull { part ->
        part.metadataAs<ServerToolMetadata>()
            ?.takeIf { it.protocol == ServerToolProtocol.ANTHROPIC_MESSAGES }
            ?.maxIndex()
    }
    .maxOrNull()

private fun StreamChunk.maxClaudeServerToolIndex(): Int? {
    val metadata = when (this) {
        is StreamChunk.ServerToolStart -> metadata
        is StreamChunk.ServerToolEnd -> metadata
        else -> null
    } ?: return null
    return runCatching {
        json.decodeFromJsonElement<ServerToolMetadata>(metadata)
    }.getOrNull()?.takeIf { it.protocol == ServerToolProtocol.ANTHROPIC_MESSAGES }?.maxIndex()
}

private fun ServerToolMetadata.maxIndex(): Int? = listOfNotNull(callIndex, resultIndex).maxOrNull()

private fun TokenUsage?.sum(other: TokenUsage?): TokenUsage? {
    if (this == null) return other
    if (other == null) return this
    return TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        totalTokens = totalTokens + other.totalTokens,
    )
}

class ClaudeProvider(private val client: OkHttpClient, context: Context? = null) : Provider<ProviderSetting.Claude> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    override suspend fun listModels(providerSetting: ProviderSetting.Claude): List<Model> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val displayName = modelObj["display_name"]?.jsonPrimitive?.contentOrNull ?: id

                Model(
                    modelId = id,
                    displayName = displayName,
                )
            }
        }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> {
        error("Claude provider does not support image generation")
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        generateClaudeWithPauseTurn(messages, params.model) { requestMessages ->
            generateTextOnce(providerSetting, requestMessages, params)
        }
    }

    private suspend fun generateTextOnce(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val requestBody = buildMessageRequest(providerSetting, messages, params)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val content = bodyJson["content"]?.jsonArray ?: JsonArray(emptyList())
        val stopReason = bodyJson["stop_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val usage = parseTokenUsage(bodyJson)

        return TextGenerationResult(
            id = id,
            model = model,
            message = parseMessage(content),
            finishReason = stopReason,
            usage = usage
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = streamClaudeWithPauseTurn(messages, params.model) { requestMessages ->
        streamTextOnce(providerSetting, requestMessages, params)
    }

    private fun streamTextOnce(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val requestBody = buildMessageRequest(providerSetting, messages, params, stream = true)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        requestBody["messages"]!!.jsonArray.forEach {
            Log.i(TAG, "streamText: $it")
        }

        val decoder = ClaudeStreamDecoder()

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
                Log.d(TAG, "onEvent: type=$type, data=$data")
                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) close()
                } catch (e: Throwable) {
                    close(e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                t?.printStackTrace()
                Log.e(TAG, "onFailure: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        Log.i(TAG, "Error response: $bodyElement")
                        exception = bodyElement.parseErrorDetail()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    e.printStackTrace()
                } finally {
                    close(exception)
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
            Log.d(TAG, "Closing eventSource")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED)

    private fun buildMessageRequest(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        return buildJsonObject {
            put("model", params.model.modelId)
            put(
                "messages",
                buildMessages(messages, providerSetting.promptCaching, providerSetting.promptCacheTtl)
            )
            put("max_tokens", params.maxTokens ?: 64_000)

            // 顶层 cache_control: 让 Anthropic 自动管理缓存断点
            if (providerSetting.promptCaching) {
                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
            }

            if (params.temperature != null && !params.reasoningLevel.isEnabled) put(
                "temperature",
                params.temperature
            )
            if (params.topP != null) put("top_p", params.topP)

            put("stream", stream)

            // system prompt
            val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
            val systemTextParts = systemMessage?.parts?.filterIsInstance<UIMessagePart.Text>().orEmpty()
            if (systemTextParts.isNotEmpty()) {
                put("system", buildJsonArray {
                    systemTextParts.forEachIndexed { index, part ->
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                            if (providerSetting.promptCaching && index == systemTextParts.lastIndex) {
                                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
                            }
                        })
                    }
                })
            }

            // 处理 thinking
            // Anthropic 新 API: adaptive 模式 + output_config.effort 控制强度
            // 旧的 type=enabled + budget_tokens 在 Opus 4.7+ 上已不支持
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                when (params.reasoningLevel) {
                    ReasoningLevel.OFF -> {
                        put("thinking", buildJsonObject { put("type", "disabled") })
                    }

                    ReasoningLevel.AUTO -> {
                        put("thinking", buildJsonObject {
                            put("type", "adaptive")
                            put("display", "summarized")
                        })
                    }

                    else -> {
                        put("thinking", buildJsonObject {
                            put("type", "adaptive")
                            put("display", "summarized")
                        })
                        put("output_config", buildJsonObject {
                            put("effort", params.reasoningLevel.effort)
                        })
                    }
                }
            }

            // 处理工具
            val useFunctionTools =
                params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()
            val toolDefinitions = buildList {
                if (useFunctionTools) {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", json.encodeToJsonElement(tool.parameters()))
                        })
                    }
                }
                params.model.tools.forEach { builtInTool ->
                    when (builtInTool) {
                        BuiltInTools.Search -> add(buildJsonObject {
                            put("type", "web_search_20250305")
                            put("name", "web_search")
                        })
                        BuiltInTools.UrlContext,
                        BuiltInTools.ImageGeneration,
                            -> Unit
                    }
                }
            }
            if (toolDefinitions.isNotEmpty()) {
                putJsonArray("tools") {
                    toolDefinitions.forEachIndexed { index, definition ->
                        if (providerSetting.promptCaching && index == toolDefinitions.lastIndex) {
                            add(JsonObject(
                                definition + mapOf(
                                    "cache_control" to cacheControlEphemeral(providerSetting.promptCacheTtl)
                                )
                            ))
                        } else {
                            add(definition)
                        }
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun cacheControlEphemeral(promptCacheTtl: ClaudePromptCacheTtl) = buildJsonObject {
        put("type", "ephemeral")
        promptCacheTtl.apiValue?.let { put("ttl", it) }
    }

    private fun buildMessages(
        messages: List<UIMessage>,
        promptCaching: Boolean,
        promptCacheTtl: ClaudePromptCacheTtl
    ) = buildJsonArray {
        messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantMessage(message)
                } else {
                    addUserMessage(message)
                }
            }
    }.let { messagesArray ->
        if (!promptCaching) return@let messagesArray
        insertMessagesCacheControl(messagesArray, promptCacheTtl)
    }

    /**
     * 在倒数第二条非 tool_result 的 user message 的最后一个 content block 上插入 cache_control
     */
    private fun insertMessagesCacheControl(
        messages: JsonArray,
        promptCacheTtl: ClaudePromptCacheTtl
    ): JsonArray {
        // 找出所有非 tool_result 的 user message 的索引
        val realUserIndices = messages.mapIndexedNotNull { index, msg ->
            val obj = msg.jsonObject
            if (obj["role"]?.jsonPrimitive?.contentOrNull == "user") {
                val content = obj["content"]?.jsonArray
                val isToolResult = content?.any {
                    it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_result"
                } == true
                if (!isToolResult) index else null
            } else null
        }

        // 取倒数第二条
        val targetIndex = if (realUserIndices.size >= 2) {
            realUserIndices[realUserIndices.size - 2]
        } else return messages

        // 在目标 message 的最后一个 content block 上添加 cache_control
        return JsonArray(messages.mapIndexed { index, msg ->
            if (index == targetIndex) {
                val obj = msg.jsonObject
                val content = obj["content"]?.jsonArray ?: return@mapIndexed msg
                val newContent = JsonArray(content.mapIndexed { contentIndex, block ->
                    if (contentIndex == content.lastIndex) {
                        JsonObject(
                            block.jsonObject + mapOf("cache_control" to cacheControlEphemeral(promptCacheTtl))
                        )
                    } else block
                })
                JsonObject(obj + mapOf("content" to newContent))
            } else msg
        })
    }

    private fun JsonArrayBuilder.addAssistantMessage(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.toContentBlocks().forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 tool_use 到内容缓冲
                    group.tools.forEach { contentBuffer.add(it.toToolUseBlock()) }

                    // 输出 assistant 消息
                    add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") { contentBuffer.forEach { add(it) } }
                    })
                    contentBuffer.clear()

                    // 紧跟 tool_result
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            group.tools.forEach { add(it.toToolResultBlock()) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") { contentBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", message.role.name.lowercase())
            putJsonArray("content") {
                message.parts.flatMap { it.toContentBlocks() }.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toContentBlocks(): List<JsonObject> = when (this) {
        is UIMessagePart.ServerTool -> serverToolContentBlocks()
        else -> listOfNotNull(toContentBlock())
    }

    /**
     * 解析后 server tool 的 call/result 会合并到同一个 part。回放连续 server tool 时
     * 按原始 content block index 还原顺序；旧消息没有 index 时回退为先 calls、后 results。
     */
    private fun List<UIMessagePart>.toContentBlocks(): List<JsonObject> = buildList {
        val serverTools = mutableListOf<UIMessagePart.ServerTool>()

        fun flushServerTools() {
            val calls = mutableListOf<Pair<JsonObject, Int?>>()
            val results = mutableListOf<Pair<JsonObject, Int?>>()
            serverTools.forEach { tool ->
                val blocks = tool.serverToolContentBlocks()
                val metadata = tool.metadataAs<ServerToolMetadata>()
                blocks.firstOrNull()?.let { calls.add(it to metadata?.callIndex) }
                blocks.getOrNull(1)?.let { results.add(it to metadata?.resultIndex) }
            }
            val blocks = calls + results
            val orderedBlocks = if (blocks.all { it.second != null }) {
                blocks.sortedBy { it.second }
            } else {
                blocks
            }
            orderedBlocks.forEach { add(it.first) }
            serverTools.clear()
        }

        for (part in this@toContentBlocks) {
            if (part is UIMessagePart.ServerTool) {
                serverTools.add(part)
            } else {
                flushServerTools()
                addAll(part.toContentBlocks())
            }
        }
        flushServerTools()
    }

    private fun UIMessagePart.ServerTool.serverToolContentBlocks(): List<JsonObject> {
        val metadata = metadataAs<ServerToolMetadata>()
        val protocol = metadata?.protocol
        if (protocol != null && protocol != ServerToolProtocol.ANTHROPIC_MESSAGES) return emptyList()

        return buildList {
            val rawCall = metadata?.call.takeIf { protocol == ServerToolProtocol.ANTHROPIC_MESSAGES }
            add(rawCall?.let {
                if (input == null) it else JsonObject(it + mapOf("input" to input))
            } ?: buildJsonObject {
                    put("type", "server_tool_use")
                    put("id", toolCallId)
                    put("name", toolName)
                    input?.let { put("input", it) }
                })
            val rawResult = metadata?.result.takeIf { protocol == ServerToolProtocol.ANTHROPIC_MESSAGES }
            if (isFinished && (output != null || rawResult != null)) {
                add(rawResult ?: buildJsonObject {
                    put("type", "${toolName}_tool_result")
                    put("tool_use_id", toolCallId)
                    output?.let { put("content", it) }
                })
            }
        }
    }

    private fun UIMessagePart.toContentBlock(): JsonObject? = when (this) {
        is UIMessagePart.Text -> buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        is UIMessagePart.Image -> buildJsonObject {
            encodeBase64(withPrefix = false).onSuccess { encoded ->
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", encoded.mimeType)
                    put("data", encoded.base64)
                })
            }.onFailure {
                Log.w(TAG, "encode image failed: $url", it)
                put("type", "text")
                put("text", "")
            }
        }

        is UIMessagePart.Reasoning -> buildJsonObject {
            put("type", "thinking")
            put("thinking", reasoning)
            metadataAs<ClaudeReasoningMetadata>()?.signature?.let { put("signature", it) }
        }

        else -> null
    }

    private fun UIMessagePart.Tool.toToolUseBlock() = buildJsonObject {
        put("type", "tool_use")
        put("id", toolCallId)
        put("name", toolName)
        put("input", inputAsJson())
    }

    private fun UIMessagePart.Tool.toToolResultBlock() = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", toolCallId)
        putJsonArray("content") {
            output.mapNotNull { it.toContentBlock() }.forEach { add(it) }
        }
    }

    internal fun parseMessage(content: JsonArray): UIMessage {
        val parts = mutableListOf<UIMessagePart>()
        val serverToolIndexes = mutableMapOf<String, Int>()

        content.forEachIndexed { blockIndex, contentBlock ->
            val block = contentBlock.jsonObject
            val type = block["type"]?.jsonPrimitive?.contentOrNull

            when (type) {
                "text", "text_delta" -> {
                    val text = block["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotEmpty()) {
                        parts.add(UIMessagePart.Text(text))
                    }
                }

                "thinking", "thinking_delta", "signature_delta" -> {
                    val thinking = block["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                    val signature = block["signature"]?.jsonPrimitive?.contentOrNull
                    if (thinking.isNotEmpty() || signature != null) {
                        val reasoning = UIMessagePart.Reasoning(
                            reasoning = thinking,
                            createdAt = Clock.System.now(),
                            finishedAt = null
                        )
                        if (signature != null) {
                            reasoning.metadata = ClaudeReasoningMetadata(signature = signature).toMetadata()
                        }
                        parts.add(reasoning)
                    }
                }

                "redacted_thinking" -> {
                    val data = block["data"]?.jsonPrimitiveOrNull?.contentOrNull
                    println(data)
                }

                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val input = block["input"]?.jsonObject ?: JsonObject(emptyMap())
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = id,
                            toolName = name,
                            input = if (input.isEmpty()) "" else json.encodeToString(input),
                            output = emptyList()
                        )
                    )
                }

                else -> if (type.isClaudeServerToolUseType()) {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    serverToolIndexes[id] = parts.size
                    parts.add(
                        UIMessagePart.ServerTool(
                            toolCallId = id,
                            toolName = block["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            input = block["input"],
                            status = ServerToolStatus.IN_PROGRESS,
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                call = block,
                                callIndex = blockIndex,
                            ).toMetadata(),
                        )
                    )
                } else if (type == "input_json_delta") {
                    val input = block["partial_json"]?.jsonPrimitive?.contentOrNull
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = "",
                            toolName = "",
                            input = input ?: "",
                            output = emptyList()
                        )
                    )
                } else if (type.isClaudeServerToolResultType()) {
                    val id = block["tool_use_id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val output = block["content"]
                    val status = if (output.isClaudeServerToolError()) {
                        ServerToolStatus.FAILED
                    } else {
                        ServerToolStatus.COMPLETED
                    }
                    val index = serverToolIndexes[id]
                    if (index == null || parts.getOrNull(index) !is UIMessagePart.ServerTool) {
                        parts.add(UIMessagePart.ServerTool(
                            toolCallId = id,
                            toolName = type?.removeSuffix("_tool_result") ?: "",
                            output = output,
                            status = status,
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                result = block,
                                resultIndex = blockIndex,
                            ).toMetadata(),
                        ))
                    } else {
                        val tool = parts[index] as UIMessagePart.ServerTool
                        parts[index] = tool.copy(
                            output = output,
                            status = status,
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                call = tool.metadataAs<ServerToolMetadata>()?.call,
                                callIndex = tool.metadataAs<ServerToolMetadata>()?.callIndex,
                                result = block,
                                resultIndex = blockIndex,
                            ).toMetadata(),
                        )
                    }
                }
            }
        }

        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts
        )
    }

    private fun parseTokenUsage(bodyJson: JsonObject?): TokenUsage? {
        if (bodyJson == null) return null

        // 回退到标准 usage 字段
        val usageJson = bodyJson["usage"]?.jsonObject
            ?: bodyJson["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val inputTokens = usageJson["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedInputTokens = usageJson["cache_read_input_tokens"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val cachedCreationTokens = usageJson["cache_creation_input_tokens"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val completionTokens = usageJson["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val promptTokens = inputTokens + cachedInputTokens + cachedCreationTokens
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            cachedTokens = cachedInputTokens,
        )
    }
}

internal fun String?.isClaudeServerToolResultType(): Boolean =
    this != null && this != "tool_result" && endsWith("_tool_result")

internal fun String?.isClaudeServerToolUseType(): Boolean =
    this == "server_tool_use" || (this != null && this != "tool_use" && endsWith("_tool_use"))

internal fun JsonElement?.isClaudeServerToolError(): Boolean {
    val content = this as? JsonObject ?: return false
    return content["type"]?.jsonPrimitive?.contentOrNull?.endsWith("_error") == true
}
