package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.vertex.ServiceAccountTokenProvider
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.AiLog
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.bufferStreamChunks
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.parseHttpErrorBody
import me.rerere.ai.util.removeElements
import me.rerere.ai.util.rethrowIfMediaTooLarge
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.apache.commons.text.StringEscapeUtils
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private const val TAG = "GoogleProvider"

class GoogleProvider(
    private val client: OkHttpClient,
    context: Context? = null,
    // 见 ClaudeProvider：SSE 使用短 readTimeout 的专用客户端，快速失败而非挂起 10 分钟。
    private val streamClient: OkHttpClient = client,
) : Provider<ProviderSetting.Google> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
    private val serviceAccountTokenProvider by lazy {
        ServiceAccountTokenProvider(client)
    }

    private fun buildUrl(providerSetting: ProviderSetting.Google, path: String): HttpUrl {
        return if (!providerSetting.vertexAI) {
            "${providerSetting.baseUrl}/$path".toHttpUrl()
        } else if (providerSetting.useServiceAccount) {
            "https://aiplatform.googleapis.com/v1/projects/${providerSetting.projectId}/locations/${providerSetting.location}/$path".toHttpUrl()
        } else {
            "https://aiplatform.googleapis.com/v1/$path".toHttpUrl()
        }
    }

    private suspend fun transformRequest(
        providerSetting: ProviderSetting.Google,
        request: Request
    ): Request {
        return if (providerSetting.vertexAI && providerSetting.useServiceAccount) {
            val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
                privateKeyPem = StringEscapeUtils.unescapeJson(providerSetting.privateKey.trim()),
            )
            request.newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            if (providerSetting.vertexAI) {
                request.newBuilder()
                    .url(request.url.newBuilder().addQueryParameter("key", key).build())
                    .build()
            } else {
                request.newBuilder()
                    .addHeader("x-goog-api-key", key)
                    .build()
            }
        }
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Google): List<Model> =
        withContext(Dispatchers.IO) {
            val url = buildUrl(providerSetting = providerSetting, path = "models?pageSize=100")
            val request = transformRequest(
                providerSetting = providerSetting,
                request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
            )
            val response = client.newCall(request).await()
            if (response.isSuccessful) {
                val body = response.body.string()
                val bodyObject = json.parseToJsonElement(body).jsonObject
                val models = bodyObject["models"]?.jsonArray ?: return@withContext emptyList()

                models.mapNotNull {
                    val modelObject = it.jsonObject

                    // 忽略非chat/embedding模型。第三方 Gemini 兼容代理可能省略字段 —
                    // 跳过缺字段的条目而非用 !! 让整个 listModels NPE（对齐 ClaudeProvider.listModels）。
                    val supportedGenerationMethods =
                        modelObject["supportedGenerationMethods"]?.jsonArrayOrNull
                            ?.mapNotNull { method -> method.jsonPrimitiveOrNull?.contentOrNull }
                            ?: return@mapNotNull null
                    if ("generateContent" !in supportedGenerationMethods && "embedContent" !in supportedGenerationMethods) {
                        return@mapNotNull null
                    }

                    val name = modelObject["name"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?: return@mapNotNull null
                    val modelId = name.substringAfter("/")

                    Model(
                        modelId = modelId,
                        // displayName 缺失时回退到 id（有效 id 的模型仍然可用），对齐 Claude。
                        displayName = modelObject["displayName"]?.jsonPrimitiveOrNull?.contentOrNull
                            ?: modelId,
                        type = if ("generateContent" in supportedGenerationMethods) ModelType.CHAT else ModelType.EMBEDDING,
                    )
                }
            } else {
                emptyList()
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody = buildCompletionRequestBody(messages, params)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:generateContent"
            } else {
                "models/${params.model.modelId}:generateContent"
            }
        )

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(params.customHeaders.toHeaders())
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        val response = client.newCall(request).await(params.callTimeoutMillis?.milliseconds)
        if (!response.isSuccessful) {
            throw parseHttpErrorBody(response.code, response.body.string())
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        parseGenerateContentResponse(bodyJson, params.model.modelId)
    }

    // Parses a non-streaming generateContent 200 response. Gemini can return HTTP 200
    // with no `candidates` (e.g. a safety-blocked prompt yielding only promptFeedback)
    // and/or no `usageMetadata`. Mirror the streaming path: surface the block reason /
    // server detail as an HttpException instead of a raw NPE.
    private fun parseGenerateContentResponse(
        bodyJson: JsonObject,
        modelId: String,
    ): MessageChunk {
        val blockReason = bodyJson["promptFeedback"]?.jsonObject
            ?.get("blockReason")?.jsonPrimitiveOrNull?.contentOrNull
        if (blockReason != null) {
            throw HttpException("Prompt feedback: $blockReason")
        }

        val candidates = bodyJson["candidates"]?.jsonArray
        if (candidates.isNullOrEmpty()) {
            throw bodyJson.parseErrorDetail()
        }

        return MessageChunk(
            id = Uuid.random().toString(),
            model = modelId,
            choices = candidates.map { candidate ->
                UIMessageChoice(
                    message = parseMessage(candidate.jsonObject),
                    index = 0,
                    finishReason = null,
                    delta = null
                )
            },
            usage = parseUsageMeta(bodyJson["usageMetadata"] as? JsonObject)
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildCompletionRequestBody(messages, params)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:streamGenerateContent"
            } else {
                "models/${params.model.modelId}:streamGenerateContent"
            }
        ).newBuilder().addQueryParameter("alt", "sse").build()

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(params.customHeaders.toHeaders())
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        AiLog.request(TAG, "google", params.model.modelId, true)

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                AiLog.event(TAG, type, id)

                try {
                    val jsonData = json.parseToJsonElement(data).jsonObject
                    val emit = when (val outcome = googleStreamFrameOutcome(jsonData)) {
                        is GoogleStreamFrame.Terminate -> {
                            close(RuntimeException("Prompt feedback: ${outcome.reason}"))
                            return
                        }

                        GoogleStreamFrame.Skip -> return
                        is GoogleStreamFrame.Emit -> outcome
                    }
                    // The candidates are carried on the Emit outcome itself, so trySend is reachable
                    // ONLY through the Emit branch — a terminating frame has no candidates to send.
                    val candidates = emit.candidates
                    val usage = parseUsageMeta(jsonData["usageMetadata"] as? JsonObject)
                    val messageChunk = MessageChunk(
                        id = Uuid.random().toString(),
                        model = params.model.modelId,
                        choices = candidates.mapIndexed { index, candidate ->
                            val candidateObj = candidate.jsonObject
                            val content = candidateObj["content"]?.jsonObject
                            val groundingMetadata = candidateObj["groundingMetadata"]?.jsonObject
                            val finishReason =
                                candidateObj["finishReason"]?.jsonPrimitive?.contentOrNull

                            val message = content?.let {
                                parseMessage(buildJsonObject {
                                    put("role", JsonPrimitive("model"))
                                    put("content", it)
                                    groundingMetadata?.let { groundingMetadata ->
                                        put("groundingMetadata", groundingMetadata)
                                    }
                                })
                            }

                            UIMessageChoice(
                                index = index,
                                delta = message,
                                message = null,
                                finishReason = finishReason
                            )
                        },
                        usage = usage
                    )

                    trySend(messageChunk)
                } catch (e: Exception) {
                    // A single malformed SSE frame must not kill the stream; skip it.
                    // Log metadata only — the frame data may carry user/model content.
                    AiLog.parseFailure(TAG, e)
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                var exception = t

                AiLog.failure(TAG, t, response?.code)

                try {
                    if (t == null && response != null) {
                        val bodyStr = response.body.stringSafe()
                        if (!bodyStr.isNullOrEmpty()) {
                            val bodyElement = json.parseToJsonElement(bodyStr)
                            if (bodyElement is JsonObject) {
                                exception = Exception(
                                    bodyElement["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                                        ?: "unknown"
                                )
                            }
                        } else {
                            exception = Exception("Unknown error: ${response.code}")
                        }
                    }
                } catch (e: Throwable) {
                    AiLog.parseFailure(TAG, e)
                    exception = e
                } finally {
                    close(exception ?: Exception("Stream failed"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Log.d(TAG, "stream closed")
                close()
            }
        }

        val eventSource = EventSources.createFactory(streamClient)
                .newEventSource(request, listener)

        awaitClose {
            Log.d(TAG, "closing eventSource")
            eventSource.cancel()
        }
    }.bufferStreamChunks()

    private fun buildCompletionRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): JsonObject = buildJsonObject {
        // System message if available
        val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        if (systemMessage != null && !params.model.outputModalities.contains(Modality.IMAGE)) {
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject {
                        put(
                            "text",
                            systemMessage.parts.filterIsInstance<UIMessagePart.Text>()
                                .joinToString { it.text })
                    })
                }
            })
        }

        // Generation config
        put("generationConfig", buildJsonObject {
            if (params.temperature != null) put("temperature", params.temperature)
            if (params.topP != null) put("topP", params.topP)
            if (params.maxTokens != null) put("maxOutputTokens", params.maxTokens)
            if (params.model.outputModalities.contains(Modality.IMAGE)) {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                })
            }
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", true)

                    val isGeminiPro =
                        params.model.modelId.contains(Regex("2\\.5.*pro", RegexOption.IGNORE_CASE))

                    when (params.reasoningLevel) {
                        ReasoningLevel.AUTO -> {} // 自动模式，不设置参数

                        ReasoningLevel.OFF -> {
                            if (ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId)) {
                                put("thinkingLevel", "minimal")
                            } else if (!isGeminiPro) {
                                put("thinkingBudget", 0)
                                put("includeThoughts", false)
                            }
                        }

                        else -> {
                            if (ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId)) {
                                when (params.reasoningLevel) {
                                    ReasoningLevel.LOW -> put("thinkingLevel", "low")
                                    ReasoningLevel.MEDIUM -> put("thinkingLevel", "medium")
                                    else -> put("thinkingLevel", "high") // HIGH, XHIGH
                                }
                            } else {
                                put("thinkingBudget", params.reasoningLevel.budgetTokens)
                            }
                        }
                    }
                })
            }
        })

        // Contents (user messages)
        put(
            "contents",
            buildContents(messages)
        )

        // Tools
        if (params.tools.isNotEmpty() && params.model.abilities.contains(ModelAbility.TOOL)) {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        params.tools.forEach { tool ->
                            add(buildJsonObject {
                                put("name", JsonPrimitive(tool.name))
                                put("description", JsonPrimitive(tool.description))
                                put(
                                    key = "parameters",
                                    element = json.encodeToJsonElement(tool.parameters())
                                        .removeElements(
                                            listOf(
                                                "const",
                                                "exclusiveMaximum",
                                                "exclusiveMinimum",
                                                "format",
                                                "additionalProperties",
                                                "enum",
                                            )
                                        )
                                )
                            })
                        }
                    })
                })
            })
        }
        // Model BuiltIn Tools
        // 目前不能和工具调用兼容
        if (params.model.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                params.model.tools.forEach { builtInTool ->
                    when (builtInTool) {
                        BuiltInTools.Search -> {
                            add(buildJsonObject {
                                put("googleSearch", buildJsonObject {})
                            })
                        }

                        BuiltInTools.UrlContext -> {
                            add(buildJsonObject {
                                put("urlContext", buildJsonObject {})
                            })
                        }

                        else -> {}
                    }
                }
            })
        }

        // Safety Settings
        putJsonArray("safetySettings") {
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HARASSMENT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HATE_SPEECH")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_DANGEROUS_CONTENT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_CIVIC_INTEGRITY")
                put("threshold", "OFF")
            })
        }
    }.mergeCustomBody(params.customBody)

    private fun commonRoleToGoogleRole(role: MessageRole): String {
        return when (role) {
            MessageRole.USER -> "user"
            MessageRole.SYSTEM -> "system"
            MessageRole.ASSISTANT -> "model"
            MessageRole.TOOL -> "user" // google api中, tool结果是用户role发送的
        }
    }

    private fun googleRoleToCommonRole(role: String): MessageRole {
        return when (role) {
            "user" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            "model" -> MessageRole.ASSISTANT
            // A proxy/future kind must degrade, not crash the parse.
            else -> MessageRole.ASSISTANT
        }
    }

    private fun parseMessage(message: JsonObject): UIMessage {
        val role = googleRoleToCommonRole(
            message["role"]?.jsonPrimitive?.contentOrNull ?: "model"
        )
        val content = message["content"]?.jsonObject ?: error("No content")
        // mapNotNull: an unknown part kind is dropped so valid sibling text parts in the same frame
        // survive (one unknown part previously threw and aborted the whole content.parts mapping).
        val parts = content["parts"]?.jsonArray?.mapNotNull { part ->
            parseMessagePart(part.jsonObject)
        } ?: emptyList()

        val groundingMetadata = message["groundingMetadata"]?.jsonObject
        val annotations = parseSearchGroundingMetadata(groundingMetadata)

        return UIMessage(
            role = role,
            parts = parts,
            annotations = annotations
        )
    }

    private fun parseSearchGroundingMetadata(jsonObject: JsonObject?): List<UIMessageAnnotation> {
        if (jsonObject == null) return emptyList()
        val groundingChunks = jsonObject["groundingChunks"]?.jsonArray ?: emptyList()
        val chunks = groundingChunks.mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            val uri = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = web["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            UIMessageAnnotation.UrlCitation(
                title = title,
                url = uri
            )
        }
        return chunks
    }

    private fun parseMessagePart(jsonObject: JsonObject): UIMessagePart? {
        return when {
            jsonObject.containsKey("text") -> {
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                if (thought) UIMessagePart.Reasoning(
                    reasoning = text,
                    createdAt = Clock.System.now(),
                    finishedAt = null
                ) else UIMessagePart.Text(text)
            }

            jsonObject.containsKey("functionCall") -> {
                UIMessagePart.Tool(
                    toolCallId = Uuid.random().toString(),
                    toolName = jsonObject["functionCall"]!!.jsonObject["name"]!!.jsonPrimitive.content,
                    input = json.encodeToString(jsonObject["functionCall"]!!.jsonObject["args"]),
                    output = emptyList(),
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                    ).toMetadata()
                )
            }

            jsonObject.containsKey("inlineData") -> {
                val inlineData = jsonObject["inlineData"]!!.jsonObject
                val mime = inlineData["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                val data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                require(mime.startsWith("image/")) {
                    "Only image mime type is supported"
                }
                // 如果是思考过程中的草稿图，直接忽略
                if (thought) {
                    return UIMessagePart.Reasoning(
                        reasoning = "[Draft Image]\n",
                        createdAt = Clock.System.now(),
                        finishedAt = null
                    )
                }
                UIMessagePart.Image(
                    url = data,
                    metadata = GoogleThoughtMetadata(thoughtSignature = thoughtSignature).toMetadata()
                )
            }

            // Unknown part kind (executableCode, codeExecutionResult, future kinds): skip it so
            // known sibling parts in the same frame are preserved.
            else -> null
        }
    }

    private fun buildContents(messages: List<UIMessage>): JsonArray {
        return buildJsonArray {
            messages
                .filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }
                .forEach { message ->
                    if (message.role == MessageRole.ASSISTANT) {
                        addModelMessage(message)
                    } else {
                        addUserMessage(message)
                    }
                }
        }
    }

    private fun JsonArrayBuilder.addModelMessage(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val partsBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.mapNotNull { it.toGooglePart() }.forEach { partsBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 functionCall 到 parts 缓冲
                    group.tools.forEach { partsBuffer.add(it.toFunctionCallPart()) }

                    // 输出 model 消息
                    add(buildJsonObject {
                        put("role", "model")
                        putJsonArray("parts") { partsBuffer.forEach { add(it) } }
                    })
                    partsBuffer.clear()

                    // 紧跟 functionResponse
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            group.tools.forEach { add(it.toFunctionResponsePart()) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (partsBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "model")
                putJsonArray("parts") { partsBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", commonRoleToGoogleRole(message.role))
            putJsonArray("parts") {
                message.parts.mapNotNull { it.toGooglePart() }.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toGooglePart(): JsonObject? = when (this) {
        is UIMessagePart.Text -> buildJsonObject {
            put("text", text)
        }

        is UIMessagePart.Image -> {
            encodeBase64(false).rethrowIfMediaTooLarge().getOrNull()?.let { encoded ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", encoded.mimeType)
                        put("data", encoded.base64)
                    })
                    metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
                        put("thoughtSignature", it)
                    }
                }
            }
        }

        is UIMessagePart.Video -> {
            encodeBase64(false).rethrowIfMediaTooLarge().getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", "video/mp4")
                        put("data", base64Data)
                    })
                }
            }
        }

        is UIMessagePart.Audio -> {
            encodeBase64(false).rethrowIfMediaTooLarge().getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", "audio/mp3")
                        put("data", base64Data)
                    })
                }
            }
        }

        else -> null
    }

    private fun UIMessagePart.Tool.toFunctionCallPart() = buildJsonObject {
        put("functionCall", buildJsonObject {
            put("name", toolName)
            put("args", inputAsJson())
        })
        metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
            put("thoughtSignature", it)
        }
    }

    private fun UIMessagePart.Tool.toFunctionResponsePart() = buildJsonObject {
        put("functionResponse", buildJsonObject {
            put("name", toolName)
            put("response", buildJsonObject {
                put(
                    "result",
                    output.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text }
                )
            })
        })
    }

    private fun parseUsageMeta(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) {
            return null
        }
        val promptTokens = jsonObject["promptTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val thoughtTokens = jsonObject["thoughtsTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val cachedTokens = jsonObject["cachedContentTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val candidatesTokens = jsonObject["candidatesTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val totalTokens = jsonObject["totalTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = candidatesTokens + thoughtTokens,
            totalTokens = totalTokens,
            cachedTokens = cachedTokens
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        val items = withContext(Dispatchers.IO) {
            require(providerSetting is ProviderSetting.Google) {
                "Expected Google provider setting"
            }

            val requestBody = buildJsonObject {
                putJsonArray("instances") {
                    add(buildJsonObject {
                        put("prompt", params.prompt)
                    })
                }
                putJsonObject("parameters") {
                    put("sampleCount", params.numOfImages)
                    put(
                        "aspectRatio", when (params.aspectRatio) {
                            ImageAspectRatio.SQUARE -> "1:1"
                            ImageAspectRatio.LANDSCAPE -> "16:9"
                            ImageAspectRatio.PORTRAIT -> "9:16"
                        }
                    )
                }
            }.mergeCustomBody(params.customBody)

            val url = buildUrl(
                providerSetting = providerSetting,
                path = if (providerSetting.vertexAI) {
                    "publishers/google/models/${params.model.modelId}:predict"
                } else {
                    "models/${params.model.modelId}:predict"
                }
            )

            val request = transformRequest(
                providerSetting = providerSetting,
                request = Request.Builder()
                    .url(url)
                    .headers(params.customHeaders.toHeaders())
                    .post(
                        json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                    )
                    .configureReferHeaders(providerSetting.baseUrl)
                    .build()
            )

            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to generate image: ${response.code} ${response.body.string()}")
            }

            val bodyStr = response.body.string()
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

            val predictions = bodyJson["predictions"]?.jsonArray ?: error("No predictions in response")

            predictions.mapNotNull { prediction ->
                val predictionObj = prediction.jsonObject
                val bytesBase64Encoded = predictionObj["bytesBase64Encoded"]?.jsonPrimitive?.contentOrNull

                if (bytesBase64Encoded != null) {
                    ImageGenerationItem(
                        data = bytesBase64Encoded,
                        mimeType = "image/png"
                    )
                } else null
            }
        }

        items.forEach { emit(it) }
    }
}

/**
 * The teardown decision for a single Gemini streaming SSE frame.
 *
 * Exactly one outcome per frame, so the stream-termination path and the content-emit path are
 * mutually exclusive by construction — a frame that terminates can never also emit (issue #240).
 */
internal sealed interface GoogleStreamFrame {
    /** A prompt-feedback block terminated the stream; [reason] is `promptFeedback.blockReason`. */
    data class Terminate(val reason: String) : GoogleStreamFrame

    /**
     * The frame carries usable candidate content and should be emitted. The non-empty [candidates]
     * array is carried HERE so the emit path is reachable only through this branch — a terminating
     * frame, which has no [candidates], cannot reach `trySend` by construction (issue #240).
     */
    data class Emit(val candidates: JsonArray) : GoogleStreamFrame

    /** The frame carries no content (no/empty candidates, no block) and should be ignored. */
    object Skip : GoogleStreamFrame
}

/**
 * Classify a Gemini stream frame into a single teardown outcome.
 *
 * A `promptFeedback.blockReason` is checked FIRST and short-circuits to [GoogleStreamFrame.Terminate]
 * even when the same frame also carries partial `candidates` — that mid-stream SAFETY/RECITATION
 * shape is exactly what let the old code close-then-fall-through-and-emit. Pure (no network, no
 * android.util.Log) so the invariant is unit-testable in isolation.
 */
internal fun googleStreamFrameOutcome(frame: JsonObject): GoogleStreamFrame {
    val reason = frame["promptFeedback"]?.jsonObject?.get("blockReason")
        ?.jsonPrimitiveOrNull?.contentOrNull
    if (reason != null) return GoogleStreamFrame.Terminate(reason)

    val candidates = frame["candidates"]?.jsonArray
    if (candidates.isNullOrEmpty()) return GoogleStreamFrame.Skip

    return GoogleStreamFrame.Emit(candidates)
}
