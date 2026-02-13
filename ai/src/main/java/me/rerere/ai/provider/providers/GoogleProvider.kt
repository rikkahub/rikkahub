package me.rerere.ai.provider.providers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureClientWithProxy
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.removeElements
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
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
import kotlin.uuid.Uuid

private const val TAG = "GoogleProvider"

class GoogleProvider(private val client: OkHttpClient) : Provider<ProviderSetting.Google> {
    private val keyRoulette = KeyRoulette.default()
    private val serviceAccountTokenProvider by lazy {
        ServiceAccountTokenProvider(client)
    }

    private fun buildUrl(providerSetting: ProviderSetting.Google, path: String): HttpUrl {
        return if (!providerSetting.vertexAI) {
            val key = keyRoulette.next(providerSetting.apiKey)
            "${providerSetting.baseUrl}/$path".toHttpUrl()
                .newBuilder()
                .addQueryParameter("key", key)
                .build()
        } else {
            "https://aiplatform.googleapis.com/v1/projects/${providerSetting.projectId}/locations/${providerSetting.location}/$path".toHttpUrl()
        }
    }

    private suspend fun transformRequest(
        providerSetting: ProviderSetting.Google,
        request: Request
    ): Request {
        return if (providerSetting.vertexAI) {
            val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
                privateKeyPem = StringEscapeUtils.unescapeJson(providerSetting.privateKey.trim()),
            )
            request.newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            request.newBuilder().build()
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
            val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: error("empty body")
                Log.d(TAG, "listModels: $body")
                val bodyObject = json.parseToJsonElement(body).jsonObject
                val models = bodyObject["models"]?.jsonArray ?: return@withContext emptyList()

                models.mapNotNull {
                    val modelObject = it.jsonObject

                    // 忽略非chat/embedding模型
                    val supportedGenerationMethods =
                        modelObject["supportedGenerationMethods"]!!.jsonArray
                            .map { method -> method.jsonPrimitive.content }
                    if ("generateContent" !in supportedGenerationMethods && "embedContent" !in supportedGenerationMethods) {
                        return@mapNotNull null
                    }

                    Model(
                        modelId = modelObject["name"]!!.jsonPrimitive.content.substringAfter("/"),
                        displayName = modelObject["displayName"]!!.jsonPrimitive.content,
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

        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        val candidates = bodyJson["candidates"]!!.jsonArray
        val usage = bodyJson["usageMetadata"]!!.jsonObject

        val messageChunk = MessageChunk(
            id = Uuid.random().toString(),
            model = params.model.modelId,
            choices = candidates.map { candidate ->
                UIMessageChoice(
                    message = parseMessage(candidate.jsonObject),
                    index = 0,
                    finishReason = null,
                    delta = null
                )
            },
            usage = parseUsageMeta(usage)
        )

        messageChunk
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

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.i(TAG, "onEvent: $data")

                try {
                    val jsonData = json.parseToJsonElement(data).jsonObject
                    if (jsonData["promptFeedback"] != null) {
                        val reason =
                            jsonData["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitiveOrNull?.contentOrNull
                        close(RuntimeException("Prompt feedback: $reason"))
                    }
                    val candidates = jsonData["candidates"]?.jsonArray ?: return
                    if (candidates.isEmpty()) return
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
                    e.printStackTrace()
                    println("[onEvent] 解析错误: $data")
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                var exception = t

                t?.printStackTrace()
                println("[onFailure] 发生错误: ${t?.message}")

                try {
                    if (t == null && response != null) {
                        val bodyStr = response.body.stringSafe()
                        if (!bodyStr.isNullOrEmpty()) {
                            val bodyElement = json.parseToJsonElement(bodyStr)
                            println(bodyElement)
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
                    e.printStackTrace()
                    exception = e
                } finally {
                    close(exception ?: Exception("Stream failed"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                println("[onClosed] 连接已关闭")
                close()
            }
        }

        val eventSource =
            EventSources.createFactory(client.configureClientWithProxy(providerSetting.proxy))
                .newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource")
            eventSource.cancel()
        }
    }

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

                    when (params.thinkingBudget) {
                        null, -1 -> {} // 如果是自动，不设置thinkingBudget参数

                        0 -> {
                            // disable thinking if not gemini pro
                            if (!isGeminiPro) {
                                put("thinkingBudget", 0)
                                put("includeThoughts", false)
                            }
                        }

                        else -> {
                            if (ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId)) {
                                when (val level = ReasoningLevel.fromBudgetTokens(params.thinkingBudget)) {
                                    ReasoningLevel.HIGH -> put("thinkingLevel", "high")
                                    ReasoningLevel.MEDIUM -> put("thinkingLevel", "high")
                                    ReasoningLevel.LOW -> put("thinkingLevel", "low")
                                    else -> error("Unknown reasoning level: $level")
                                }
                            } else {
                                put("thinkingBudget", params.thinkingBudget)
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
                                put("google_search", buildJsonObject {})
                            })
                        }

                        BuiltInTools.UrlContext -> {
                            add(buildJsonObject {
                                put("url_context", buildJsonObject {})
                            })
                        }
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
            else -> error("Unknown role $role")
        }
    }

    private fun parseMessage(message: JsonObject): UIMessage {
        val role = googleRoleToCommonRole(
            message["role"]?.jsonPrimitive?.contentOrNull ?: "model"
        )
        val content = message["content"]?.jsonObject ?: error("No content")
        val parts = content["parts"]?.jsonArray?.map { part ->
            parseMessagePart(part.jsonObject)
        } ?: emptyList()

        val groundingMetadata = message["groundingMetadata"]?.jsonObject
        Log.i(TAG, "parseMessage: $groundingMetadata")
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
        Log.i(TAG, "parseSearchGroundingMetadata: $chunks")
        return chunks
    }

    private fun parseMessagePart(jsonObject: JsonObject): UIMessagePart {
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
                    metadata = buildJsonObject {
                        put("thoughtSignature", jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull)
                    }
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
                    metadata = buildJsonObject {
                        put("thoughtSignature", thoughtSignature)
                    }
                )
            }

            else -> error("unknown message part type: $jsonObject")
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
            encodeBase64(false).getOrNull()?.let { encoded ->
                buildJsonObject {
                    put("inline_data", buildJsonObject {
                        put("mime_type", encoded.mimeType)
                        put("data", encoded.base64)
                    })
                    metadata?.get("thoughtSignature")?.jsonPrimitive?.contentOrNull?.let {
                        put("thoughtSignature", it)
                    }
                }
            }
        }

        is UIMessagePart.Video -> {
            encodeBase64(false).getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inline_data", buildJsonObject {
                        put("mime_type", "video/mp4")
                        put("data", base64Data)
                    })
                }
            }
        }

        is UIMessagePart.Audio -> {
            encodeBase64(false).getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inline_data", buildJsonObject {
                        put("mime_type", "audio/mp3")
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
            put("args", json.parseToJsonElement(input.ifBlank { "{}" }))
        })
        metadata?.get("thoughtSignature")?.let {
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
    ): ImageGenerationResult = withContext(Dispatchers.IO) {
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

        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate image: ${response.code} ${response.body.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        val predictions = bodyJson["predictions"]?.jsonArray ?: error("No predictions in response")

        val items = predictions.mapNotNull { prediction ->
            val predictionObj = prediction.jsonObject
            val bytesBase64Encoded = predictionObj["bytesBase64Encoded"]?.jsonPrimitive?.contentOrNull

            if (bytesBase64Encoded != null) {
                ImageGenerationItem(
                    data = bytesBase64Encoded,
                    mimeType = "image/png"
                )
            } else null
        }

        ImageGenerationResult(items = items)
    }
}
