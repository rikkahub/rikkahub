package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.effectiveCapabilityProfile
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.BoundedStreamBridge
import me.rerere.ai.util.ProviderStreamErrorCode
import me.rerere.ai.util.ProviderStreamException
import me.rerere.ai.util.providerStreamFailure
import me.rerere.ai.util.providerRequestFailure
import me.rerere.ai.util.ProviderStreamTerminationReason
import me.rerere.ai.util.boundedStreamFlow
import me.rerere.ai.util.configureReferHeaders
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

class ChatCompletionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette,
    private val streamQueueCapacity: Int = BoundedStreamBridge.DEFAULT_CAPACITY,
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody =
            buildChatCompletionRequest(
                messages = messages,
                params = params,
                providerSetting = providerSetting
            )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw providerRequestFailure(response)
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val choice = bodyJson["choices"]?.jsonArray?.get(0)?.jsonObject ?: error("choices is null")

        val message = choice["message"]?.jsonObject ?: throw Exception("message is null")
        val finishReason = choice["finish_reason"]
            ?.jsonPrimitive
            ?.content
            ?: "unknown"
        val usage = parseTokenUsage(bodyJson["usage"] as? JsonObject)

        MessageChunk(
            id = id,
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = parseMessage(message),
                    finishReason = finishReason
                )
            ),
            usage = usage
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = boundedStreamFlow(
        capacity = streamQueueCapacity,
    ) { bridge ->
        var completed = false
        val toolCallAssembler = OpenAIChatToolCallAssembler()
        val requestBody = buildChatCompletionRequest(
            messages = messages,
            params = params,
            providerSetting = providerSetting,
            stream = true,
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}")
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    completed = true
                    bridge.complete()
                    return
                }
                try {
                    for (line in data.trim().split("\n").filter { it.isNotBlank() }) {
                        val event = json.parseToJsonElement(line).jsonObject
                        if (event["error"] != null) {
                            bridge.fail(
                                ProviderStreamException(
                                    ProviderStreamErrorCode.STREAM_UPSTREAM_FAILURE,
                                    "Provider returned a stream error",
                                    event["error"]!!.parseErrorDetail(),
                                )
                            )
                            return
                        }
                        val id = event["id"]?.jsonPrimitive?.contentOrNull ?: ""
                        val model = event["model"]?.jsonPrimitive?.contentOrNull ?: ""

                        val choices = event["choices"]?.jsonArray ?: JsonArray(emptyList())
                        val choiceList = buildList {
                            choices.forEach { choiceElement ->
                                val choice = choiceElement.jsonObject
                                val message =
                                    choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject
                                    ?: throw IllegalArgumentException("delta/message is null")
                                val finishReason =
                                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                                if (finishReason != "unknown") completed = true
                                add(
                                    UIMessageChoice(
                                        index = choice["index"]?.jsonPrimitive?.intOrNull ?: 0,
                                        delta = parseMessage(
                                            message,
                                            choice["index"]?.jsonPrimitive?.intOrNull ?: 0,
                                            toolCallAssembler,
                                        ),
                                        message = null,
                                        finishReason = finishReason,
                                    )
                                )
                            }
                        }
                        val usage = parseTokenUsage(event["usage"] as? JsonObject)

                        if (!bridge.emit(
                                MessageChunk(
                                    id = id,
                                    model = model,
                                    choices = choiceList,
                                    usage = usage,
                                )
                            )
                        ) return
                    }
                } catch (error: Throwable) {
                    bridge.fail(
                        ProviderStreamException(
                            ProviderStreamErrorCode.STREAM_MALFORMED_EVENT,
                            "Malformed provider stream event",
                            error,
                        ),
                        ProviderStreamTerminationReason.MALFORMED_EVENT,
                    )
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception: Throwable = t ?: RuntimeException("Provider stream failed")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        exception = bodyElement.parseErrorDetail()
                    }
                } catch (e: Throwable) {
                    if (t == null) exception = e
                } finally {
                    bridge.fail(providerStreamFailure(exception, response?.code, response?.message))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (completed) bridge.complete() else bridge.failIncomplete()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        bridge.attachUpstreamCanceller(eventSource::cancel)
    }


    private fun buildChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
        stream: Boolean = false,
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        return buildJsonObject {
            put("model", params.model.modelId)
            put(
                "messages",
                buildMessages(
                    messages = messages,
                    includeHistoryReasoning = providerSetting.includeHistoryReasoning,
                    supportInputModalities = params.model.inputModalities,
                )
            )

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_tokens", params.maxTokens)

            put("stream", stream)
            if (stream) {
                if (host != "api.mistral.ai") { // mistral 不支持 stream_options
                    put("stream_options", buildJsonObject {
                        put("include_usage", true)
                    })
                }
            }

            // open router适配
            if(host == "openrouter.ai") {
                if(params.model.outputModalities.contains(Modality.IMAGE)) {
                    put("modalities", buildJsonArray {
                        add("image")
                        add("text")
                    })
                }
            }

            if (params.model.effectiveCapabilityProfile().reasoning) {
                val level = params.reasoningLevel
                when (host) {
                    "openrouter.ai" -> {
                        // https://openrouter.ai/docs/use-cases/reasoning-tokens
                        put("reasoning", buildJsonObject {
                            when (level) {
                                ReasoningLevel.OFF -> put("effort", "none")
                                ReasoningLevel.AUTO -> put("enabled", true)
                                else -> put("effort", level.effort)
                            }
                        })
                    }

                    "dashscope.aliyuncs.com" -> {
                        // 阿里云百炼
                        // https://bailian.console.aliyun.com/console?tab=doc#/doc/?type=model&url=https%3A%2F%2Fhelp.aliyun.com%2Fdocument_detail%2F2870973.html&renderType=iframe
                        put("enable_thinking", level.isEnabled)
                        if (level != ReasoningLevel.AUTO) put("thinking_budget", level.budgetTokens)
                    }

                    "ark.cn-beijing.volces.com" -> {
                        // 豆包 (火山)
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.mistral.ai" -> {
                        // Mistral 不支持
                    }

                    "chat.intern-ai.org.cn" -> {
                        // 书生
                        // https://internlm.intern-ai.org.cn/api/document?lang=zh
                        put("thinking_mode", level.isEnabled)
                    }

                    "api.siliconflow.cn" -> {
                        // https://docs.siliconflow.cn/cn/userguide/capabilities/reasoning#3-1-api-%E5%8F%82%E6%95%B0
                        val modelId = params.model.modelId
                        val siliconflowThinkingModels = setOf(
                            "Pro/moonshotai/Kimi-K2.5",
                            "Pro/zai-org/GLM-5",
                            "Pro/zai-org/GLM-5.1",
                            "Pro/zai-org/GLM-4.7",
                            "deepseek-ai/DeepSeek-V3.2",
                            "Pro/deepseek-ai/DeepSeek-V3.2",
                            "Qwen/Qwen3.5-397B-A17B",
                            "Qwen/Qwen3.5-122B-A10B",
                            "Qwen/Qwen3.5-35B-A3B",
                            "Qwen/Qwen3.5-27B",
                            "Qwen/Qwen3.5-9B",
                            "Qwen/Qwen3.5-4B",
                            "zai-org/GLM-4.6",
                            "Qwen/Qwen3-8B",
                            "Qwen/Qwen3-14B",
                            "Qwen/Qwen3-32B",
                            "Qwen/Qwen3-30B-A3B",
                            "tencent/Hunyuan-A13B-Instruct",
                            "zai-org/GLM-4.5V",
                            "deepseek-ai/DeepSeek-V3.1-Terminus",
                            "Pro/deepseek-ai/DeepSeek-V3.1-Terminus",
                            "deepseek-ai/DeepSeek-V4-Flash",
                            "Pro/deepseek-ai/DeepSeek-V4-Flash",
                            "deepseek-ai/DeepSeek-V4-Pro",
                            "Pro/deepseek-ai/DeepSeek-V4-Pro",
                        )
                        if (modelId in siliconflowThinkingModels) {
                            put("enable_thinking", level.isEnabled)
                        }
                    }

                    "aiping.cn" -> {
                        put("enable_thinking", level.isEnabled)
                    }

                    "open.bigmodel.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.moonshot.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                            // K2.6 的 thinking.keep 默认为 null（忽略历史思考），思考开启时
                            // 需显式传 "all" 才是保留式思考；文档推荐与 enabled 搭配（#1586）
                            if (level.isEnabled && ModelRegistry.KIMI_K2_6.match(params.model.modelId)) {
                                put("keep", "all")
                            }
                        })
                    }

                    "api.deepseek.com" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                        if (level.isEnabled && level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", level.effort)
                        }
                    }

                    "integrate.api.nvidia.com" -> {
                        if ("deepseek-v4" in params.model.modelId.lowercase()) {
                            if (level != ReasoningLevel.AUTO) {
                                val effort = when (level) {
                                    ReasoningLevel.XHIGH -> "max"
                                    ReasoningLevel.OFF -> "none"
                                    else -> "high"
                                }
                                put("reasoning_effort", effort)
                            }
                        } else {
                            if (level != ReasoningLevel.AUTO) {
                                put("reasoning_effort", if (level.effort == "none") "low" else level.effort)
                            }
                        }
                    }

                    "opencode.ai" -> {
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", level.effort)
                        }
                    }

                    else -> {
                        // OpenAI 官方
                        // 文档中，completions API 只支持 "low", "medium", "high"
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", if (level.effort == "none") "low" else level.effort)
                        }
                    }
                }
            }

            if (params.model.effectiveCapabilityProfile().toolCalling && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.parameters()
                                    )
                                )
                            })
                        })
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun isModelAllowTemperature(model: Model): Boolean {
        val isMoonshotRestricted = ModelRegistry.KIMI_K2_5.match(model.modelId) ||
                ModelRegistry.KIMI_K2_6.match(model.modelId) ||
                ModelRegistry.KIMI_K3.match(model.modelId) ||
                ModelRegistry.KIMI_K3_ALIAS.match(model.modelId)
        return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && 
               !ModelRegistry.GPT_5.match(model.modelId) && 
               !isMoonshotRestricted
    }

    private fun buildMessages(
        messages: List<UIMessage>,
        includeHistoryReasoning: Boolean = true,
        supportInputModalities: List<Modality> = listOf(Modality.TEXT, Modality.IMAGE),
    ) = buildJsonArray {
        val filteredMessages = messages.filter { it.isValidToUpload() }

        filteredMessages.forEach { message ->
            if (message.role == MessageRole.ASSISTANT) {
                addAssistantMessages(
                    message = message,
                    includeReasoning = includeHistoryReasoning,
                    supportInputModalities = supportInputModalities,
                )
            } else {
                addNonAssistantMessage(message)
            }
        }
    }

    private fun JsonArrayBuilder.addAssistantMessages(
        message: UIMessage,
        includeReasoning: Boolean,
        supportInputModalities: List<Modality>,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()
        var reasoningPart: UIMessagePart.Reasoning? = null

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    // 从当前 group 中提取 reasoning（保持顺序）
                    if (includeReasoning) {
                        group.parts.filterIsInstance<UIMessagePart.Reasoning>().firstOrNull()?.let {
                            reasoningPart = it
                        }
                    }
                    group.parts
                        .filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
                        .forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 输出 assistant 消息（包含累积的内容 + tool_calls）
                    buildAssistantMessageJson(
                        contentParts = contentBuffer,
                        tools = group.tools,
                        reasoningPart = reasoningPart
                    )?.let { assistantMessage ->
                        add(assistantMessage)
                    }
                    contentBuffer.clear()
                    reasoningPart = null // 清空，下一个 group 可能有新的 reasoning

                    // 紧跟 tool 结果消息
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("name", tool.toolName)
                            put("tool_call_id", tool.toolCallId)
                            put("content", tool.toToolResultContent(supportInputModalities))
                        })
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty() || reasoningPart != null) {
            buildAssistantMessageJson(
                contentParts = contentBuffer,
                tools = emptyList(),
                reasoningPart = reasoningPart
            )?.let { assistantMessage ->
                add(assistantMessage)
            }
        }
    }

    private fun buildAssistantMessageJson(
        contentParts: List<UIMessagePart>,
        tools: List<UIMessagePart.Tool>,
        reasoningPart: UIMessagePart.Reasoning?
    ): JsonObject? {
        val hasUsableContent = contentParts.any { part ->
            when (part) {
                is UIMessagePart.Text -> part.text.isNotBlank()
                is UIMessagePart.Image -> part.url.isNotBlank()
                else -> false
            }
        }
        val hasReasoning = !reasoningPart?.reasoning.isNullOrBlank()
        if (!hasUsableContent && !hasReasoning && tools.isEmpty()) {
            return null
        }

        return buildJsonObject {
            put("role", "assistant")

            // reasoning_content
            if (hasReasoning) {
                put("reasoning_content", reasoningPart.reasoning)
            }

            // content
            if (contentParts.isEmpty()) {
                put("content", "")
            } else if (contentParts.size == 1 && contentParts[0] is UIMessagePart.Text) {
                put("content", (contentParts[0] as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    contentParts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", encodedImage.base64)
                                        })
                                    }.onFailure {
                                        put("type", "text")
                                        put("text", "")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }

            // tool_calls
            if (tools.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("id", tool.toolCallId)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.toolName)
                                // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送
                                put("arguments", tool.inputAsJson().toString())
                            })
                        })
                    }
                })
            }
        }
    }

    private fun JsonArrayBuilder.addNonAssistantMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", JsonPrimitive(message.role.name.lowercase()))

            if (message.parts.isOnlyTextPart()) {
                put("content", message.parts.filterIsInstance<UIMessagePart.Text>().first().text)
            } else {
                putJsonArray("content") {
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", encodedImage.base64)
                                        })
                                    }.onFailure {
                                        put("type", "text")
                                        put("text", "")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    private fun UIMessagePart.Tool.toToolResultContent(supportInputModalities: List<Modality>): JsonElement {
        // 只考虑文字和图片;只有模型支持图片输入时,图片才作为多模态内容回传,否则以文本占位,避免发给不支持的模型报错
        val supportsImageInput = Modality.IMAGE in supportInputModalities
        val hasImageToSend = output.any { it is UIMessagePart.Image && supportsImageInput }
        return if (!hasImageToSend) {
            JsonPrimitive(output.mapNotNull { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text
                    is UIMessagePart.Image -> "[Image output omitted: current model does not support image input]"
                    else -> null
                }
            }.joinToString("\n"))
        } else {
            buildJsonArray {
                output.forEach { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            if (part.text.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }
                        }

                        is UIMessagePart.Image -> {
                            add(buildJsonObject {
                                part.encodeBase64().onSuccess { encodedImage ->
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", encodedImage.base64)
                                    })
                                }.onFailure {
                                    put("type", "text")
                                    put("text", "Error: Failed to encode image to base64")
                                }
                            })
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    private fun parseMessage(
        jsonObject: JsonObject,
        choiceIndex: Int? = null,
        toolCallAssembler: OpenAIChatToolCallAssembler? = null,
    ): UIMessage {
        val role = MessageRole.valueOf(
            jsonObject["role"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "ASSISTANT"
        )

        // 也许支持其他模态的输出content?
        val content = jsonObject["content"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val reasoning = jsonObject["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonObject["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonObject["content"]?.takeIf { it is JsonArray }?.let { arr ->
                // Mistral接口
                // {"id":"","object":"chat.completion.chunk","created":1772351733,"model":"magistral-medium-2509","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"好的"}]}]},"finish_reason":null}]}
                arr.jsonArrayOrNull?.getOrNull(0)?.jsonObject?.get("thinking")?.jsonArrayOrNull?.getOrNull(0)?.jsonObjectOrNull?.get(
                    "text"
                )?.jsonPrimitiveOrNull?.contentOrNull
            }
        val toolCalls = jsonObject["tool_calls"] as? JsonArray ?: JsonArray(emptyList())
        val images = jsonObject["images"] as? JsonArray ?: JsonArray(emptyList())

        return UIMessage(
            role = role,
            parts = buildList {
                if (!reasoning.isNullOrEmpty()) {
                    add(
                        UIMessagePart.Reasoning(
                            reasoning = reasoning,
                            createdAt = Clock.System.now(),
                            finishedAt = null
                        )
                    )
                }
                toolCalls.forEach { toolCall ->
                    val toolCallObject = toolCall.jsonObject
                    val type = toolCallObject["type"]?.jsonPrimitive?.contentOrNull
                    if (!type.isNullOrEmpty() && type != "function") error("tool call type not supported: $type")
                    val streamedTool = if (choiceIndex != null && toolCallAssembler != null) {
                        toolCallAssembler.resolve(choiceIndex, toolCallObject)
                    } else {
                        val toolCallId = toolCallObject["id"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: return@forEach
                        UIMessagePart.Tool(
                            toolCallId = toolCallId,
                            toolName = toolCallObject["function"]?.jsonObject?.get("name")
                                ?.jsonPrimitive?.contentOrNull ?: "",
                            input = toolCallObject["function"]?.jsonObject?.get("arguments")
                                ?.jsonPrimitive?.contentOrNull ?: "",
                            output = emptyList(),
                        )
                    }
                    streamedTool?.let(::add)
                }
                if (content.isNotEmpty()) add(UIMessagePart.Text(content))
                images.forEach { image ->
                    val imageObject = image.jsonObjectOrNull ?: return@forEach
                    val type = imageObject["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (type != "image_url") return@forEach
                    val url = imageObject["image_url"]?.jsonObjectOrNull?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                    require(url.startsWith("data:image")) { "Only data uri is supported" }
                    add(UIMessagePart.Image(url.substringAfter("data:image/png;base64,")))
                }
            },
            annotations = parseAnnotations(
                jsonArray = jsonObject["annotations"]?.jsonArrayOrNull ?: JsonArray(
                    emptyList()
                )
            ),
        )
    }

    private fun parseAnnotations(jsonArray: JsonArray): List<UIMessageAnnotation> {
        return jsonArray.map { element ->
            val type =
                element.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: error("type is null")
            when (type) {
                "url_citation" -> {
                    UIMessageAnnotation.UrlCitation(
                        title = element.jsonObject["url_citation"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
                            ?: "",
                        url = element.jsonObject["url_citation"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                            ?: "",
                    )
                }

                else -> error("unknown annotation type: $type")
            }
        }
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            // 各 provider 汇报缓存命中的字段形状不统一，按方言兜底解析（#1576）：
            // OpenAI 嵌套 -> Moonshot 顶层 cached_tokens -> DeepSeek prompt_cache_hit_tokens
            cachedTokens = jsonObject["prompt_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: jsonObject["cached_tokens"]?.jsonPrimitive?.intOrNull
                ?: jsonObject["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }

    private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
        val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
        val texts = filter { it is UIMessagePart.Text }.size
        return gonnaSend == texts && texts == 1
    }
}
