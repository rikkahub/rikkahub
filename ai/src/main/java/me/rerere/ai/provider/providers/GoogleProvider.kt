package me.rerere.ai.provider.providers

import android.content.Context
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
import me.rerere.ai.provider.GoogleAccessMode
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
private const val VERTEX_BASE_URL = "https://aiplatform.googleapis.com"
private const val GEMINI_PUBLISHER_PATH = "publishers/google/models"
private val FALLBACK_VERTEX_GEMINI_MODELS = listOf(
    "gemini-2.0-flash",
    "gemini-2.5-flash",
    "gemini-2.5-pro",
    "gemini-2.5-flash-image",
    "gemini-3-pro-image",
    "nano-banana",
    "gemini-3-pro",
    "gemini-3-flash",
    "gemini-3.1-pro-preview",
    "gemini-3.1-pro-preview-customtools",
    "gemini-3.1-flash-image",
    "gemini-flash-latest",
    "gemini-pro-latest",
)

class GoogleProvider(private val client: OkHttpClient, context: Context? = null) : Provider<ProviderSetting.Google> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
    private val serviceAccountTokenProvider by lazy {
        ServiceAccountTokenProvider(client)
    }

    private enum class GoogleAuthMode {
        API_KEY_HEADER,
        API_KEY_QUERY,
        SERVICE_ACCOUNT_BEARER,
    }

    private data class GoogleEndpointConfig(
        val authMode: GoogleAuthMode,
        val operationBaseUrl: String,
        val modelListBaseUrl: String,
    )

    private fun resolveApiKey(providerSetting: ProviderSetting.Google): String {
        return keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
    }

    private fun resolveEndpointConfig(providerSetting: ProviderSetting.Google): GoogleEndpointConfig {
        return when (providerSetting.resolvedAccessMode()) {
            GoogleAccessMode.GEMINI_API -> GoogleEndpointConfig(
                authMode = GoogleAuthMode.API_KEY_HEADER,
                operationBaseUrl = providerSetting.baseUrl,
                modelListBaseUrl = providerSetting.baseUrl,
            )

            GoogleAccessMode.VERTEX_API_KEY -> GoogleEndpointConfig(
                authMode = GoogleAuthMode.API_KEY_QUERY,
                operationBaseUrl = "$VERTEX_BASE_URL/v1",
                modelListBaseUrl = "$VERTEX_BASE_URL/v1beta1",
            )

            GoogleAccessMode.VERTEX_SERVICE_ACCOUNT -> GoogleEndpointConfig(
                authMode = GoogleAuthMode.SERVICE_ACCOUNT_BEARER,
                operationBaseUrl = "$VERTEX_BASE_URL/v1/projects/${providerSetting.projectId}/locations/${providerSetting.location}",
                modelListBaseUrl = "$VERTEX_BASE_URL/v1beta1",
            )
        }
    }

    private fun buildOperationUrl(providerSetting: ProviderSetting.Google, path: String): HttpUrl {
        val endpointConfig = resolveEndpointConfig(providerSetting)
        val urlBuilder = "${endpointConfig.operationBaseUrl}/$path".toHttpUrl().newBuilder()
        if (endpointConfig.authMode == GoogleAuthMode.API_KEY_QUERY) {
            urlBuilder.addQueryParameter("key", resolveApiKey(providerSetting))
        }
        return urlBuilder.build()
    }

    private fun buildModelListUrl(providerSetting: ProviderSetting.Google): HttpUrl {
        val endpointConfig = resolveEndpointConfig(providerSetting)
        val path = when (providerSetting.resolvedAccessMode()) {
            GoogleAccessMode.GEMINI_API -> "models?pageSize=100"
            GoogleAccessMode.VERTEX_API_KEY,
            GoogleAccessMode.VERTEX_SERVICE_ACCOUNT -> "$GEMINI_PUBLISHER_PATH?pageSize=100&listAllVersions=true"
        }
        val urlBuilder = "${endpointConfig.modelListBaseUrl}/$path".toHttpUrl().newBuilder()
        if (endpointConfig.authMode == GoogleAuthMode.API_KEY_QUERY) {
            urlBuilder.addQueryParameter("key", resolveApiKey(providerSetting))
        }
        return urlBuilder.build()
    }

    private fun buildModelOperationPath(providerSetting: ProviderSetting.Google, modelId: String, action: String): String {
        return when (providerSetting.resolvedAccessMode()) {
            GoogleAccessMode.GEMINI_API -> "models/$modelId:$action"
            GoogleAccessMode.VERTEX_API_KEY,
            GoogleAccessMode.VERTEX_SERVICE_ACCOUNT -> "$GEMINI_PUBLISHER_PATH/$modelId:$action"
        }
    }

    private fun Request.Builder.configureGoogleReferHeaders(providerSetting: ProviderSetting.Google): Request.Builder {
        return if (providerSetting.resolvedAccessMode() == GoogleAccessMode.GEMINI_API) {
            configureReferHeaders(providerSetting.baseUrl)
        } else {
            this
        }
    }

    private suspend fun transformRequest(
        providerSetting: ProviderSetting.Google,
        request: Request
    ): Request {
        return when (resolveEndpointConfig(providerSetting).authMode) {
            GoogleAuthMode.SERVICE_ACCOUNT_BEARER -> {
                val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                    serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
                    privateKeyPem = StringEscapeUtils.unescapeJson(providerSetting.privateKey.trim()),
                )
                request.newBuilder()
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()
            }

            GoogleAuthMode.API_KEY_HEADER -> {
                request.newBuilder()
                    .addHeader("x-goog-api-key", resolveApiKey(providerSetting))
                    .build()
            }

            GoogleAuthMode.API_KEY_QUERY -> request
        }
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Google): List<Model> =
        withContext(Dispatchers.IO) {
            when (providerSetting.resolvedAccessMode()) {
                GoogleAccessMode.GEMINI_API -> listGeminiModels(providerSetting)
                GoogleAccessMode.VERTEX_API_KEY,
                GoogleAccessMode.VERTEX_SERVICE_ACCOUNT -> {
                    runCatching {
                        listVertexModels(providerSetting)
                    }.onFailure {
                        logWarn("Failed to fetch Vertex models, falling back to local Gemini candidates", it)
                    }.getOrDefault(emptyList())
                        .ifEmpty { buildFallbackGeminiModels() }
                }
            }
        }

    private suspend fun listGeminiModels(providerSetting: ProviderSetting.Google): List<Model> {
        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(buildModelListUrl(providerSetting))
                .get()
                .build()
        )
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            return emptyList()
        }

        val body = response.body?.string() ?: error("empty body")
        logDebug("listGeminiModels: $body")
        val bodyObject = json.parseToJsonElement(body).jsonObject
        val models = bodyObject["models"]?.jsonArray ?: return emptyList()

        return models.mapNotNull {
            val modelObject = it.jsonObject
            val supportedGenerationMethods =
                modelObject["supportedGenerationMethods"]?.jsonArray
                    ?.map { method -> method.jsonPrimitive.content }
                    ?: return@mapNotNull null
            if ("generateContent" !in supportedGenerationMethods && "embedContent" !in supportedGenerationMethods) {
                return@mapNotNull null
            }

            Model(
                modelId = modelObject["name"]?.jsonPrimitive?.contentOrNull?.substringAfter("/") ?: return@mapNotNull null,
                displayName = modelObject["displayName"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                type = if ("generateContent" in supportedGenerationMethods) ModelType.CHAT else ModelType.EMBEDDING,
            )
        }
    }

    private suspend fun listVertexModels(providerSetting: ProviderSetting.Google): List<Model> {
        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(buildModelListUrl(providerSetting))
                .get()
                .build()
        )
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to list Vertex models: ${response.code} ${response.body?.string()}")
        }

        val body = response.body?.string() ?: error("empty body")
        logDebug("listVertexModels: $body")
        val bodyObject = json.parseToJsonElement(body).jsonObject
        val publisherModels = bodyObject["publisherModels"]?.jsonArray ?: return emptyList()

        return publisherModels.mapNotNull { model ->
            val modelObject = model.jsonObject
            val modelId = modelObject["name"]?.jsonPrimitive?.contentOrNull?.substringAfterLast("/") ?: return@mapNotNull null
            buildKnownGeminiModel(
                modelId = modelId,
                displayName = modelObject["displayName"]?.jsonPrimitive?.contentOrNull ?: modelId,
            )
        }.distinctBy { it.modelId }
    }

    private fun buildFallbackGeminiModels(): List<Model> {
        return FALLBACK_VERTEX_GEMINI_MODELS.mapNotNull { modelId ->
            buildKnownGeminiModel(modelId = modelId)
        }
    }

    private fun buildKnownGeminiModel(modelId: String, displayName: String = modelId): Model? {
        val normalizedModelId = FALLBACK_VERTEX_GEMINI_MODELS.firstOrNull { knownId ->
            knownId.equals(modelId, ignoreCase = true)
        } ?: return null
        val inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(normalizedModelId)
        val outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(normalizedModelId)
        val abilities = ModelRegistry.MODEL_ABILITIES.getData(normalizedModelId)

        return Model(
            modelId = normalizedModelId,
            displayName = displayName,
            type = if (outputModalities.contains(Modality.IMAGE)) ModelType.IMAGE else ModelType.CHAT,
            inputModalities = inputModalities,
            outputModalities = outputModalities,
            abilities = abilities,
        )
    }

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logWarn(message: String, throwable: Throwable) {
        runCatching { Log.w(TAG, message, throwable) }
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody = buildCompletionRequestBody(messages, params)

        val url = buildOperationUrl(
            providerSetting = providerSetting,
            path = buildModelOperationPath(
                providerSetting = providerSetting,
                modelId = params.model.modelId,
                action = "generateContent"
            )
        )

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(params.customHeaders.toHeaders())
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureGoogleReferHeaders(providerSetting)
                .build()
        )

        val response = client.newCall(request).await()
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

        val url = buildOperationUrl(
            providerSetting = providerSetting,
            path = buildModelOperationPath(
                providerSetting = providerSetting,
                modelId = params.model.modelId,
                action = "streamGenerateContent"
            )
        ).newBuilder().addQueryParameter("alt", "sse").build()

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(params.customHeaders.toHeaders())
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureGoogleReferHeaders(providerSetting)
                .build()
        )

        logInfo("streamText: ${json.encodeToString(requestBody)}")

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                logInfo("onEvent: $data")

                try {
                    val jsonData = json.parseToJsonElement(data).jsonObject
                    val reason = 
                        jsonData["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitiveOrNull?.contentOrNull
                    if (reason != null) {
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

        val eventSource = EventSources.createFactory(client)
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
                                put("googleSearch", buildJsonObject {})
                            })
                        }

                        BuiltInTools.UrlContext -> {
                            add(buildJsonObject {
                                put("urlContext", buildJsonObject {})
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
        logInfo("parseMessage: $groundingMetadata")
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
        logInfo("parseSearchGroundingMetadata: $chunks")
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
                    put("inlineData", buildJsonObject {
                        put("mimeType", encoded.mimeType)
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
                    put("inlineData", buildJsonObject {
                        put("mimeType", "video/mp4")
                        put("data", base64Data)
                    })
                }
            }
        }

        is UIMessagePart.Audio -> {
            encodeBase64(false).getOrNull()?.let { base64Data ->
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

        val url = buildOperationUrl(
            providerSetting = providerSetting,
            path = buildModelOperationPath(
                providerSetting = providerSetting,
                modelId = params.model.modelId,
                action = "predict"
            )
        )

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(params.customHeaders.toHeaders())
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureGoogleReferHeaders(providerSetting)
                .build()
        )

        val response = client.newCall(request).await()
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
