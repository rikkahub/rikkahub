package me.rerere.ai.provider.providers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonArray
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
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.await
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.removeElements
import me.rerere.ai.util.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val TAG = "GoogleProvider"

object GoogleProvider : Provider<ProviderSetting.Google> {
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        })
        .build()

    private fun buildUrl(providerSetting: ProviderSetting.Google, path: String): HttpUrl {
        return if (!providerSetting.vertexAI) {
            "${providerSetting.baseUrl}/$path".toHttpUrl()
                .newBuilder()
                .addQueryParameter("key", providerSetting.apiKey)
                .build()
        } else {
            "https://${providerSetting.location}-aiplatform.googleapis.com/v1/projects/${providerSetting.projectId}/locations/${providerSetting.location}/$path".toHttpUrl()
        }
    }

    private fun transformRequest(
        providerSetting: ProviderSetting.Google,
        request: Request
    ): Request {
        return if (providerSetting.vertexAI) {
            request.newBuilder()
                .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
                .build()
        } else {
            request.newBuilder().build()
        }
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Google): List<Model> {
        val url = buildUrl(providerSetting = providerSetting, path = "models")
        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .get()
                .build()
        )
        val response = client.newCall(request).await()
        return if (response.isSuccessful) {
            val body = response.body?.string() ?: error("empty body")
            Log.d(TAG, "listModels: $body")
            val bodyObject = json.parseToJsonElement(body).jsonObject
            val models = bodyObject["models"]?.jsonArray ?: return emptyList()

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
                println("[onEvent] $data")
                if (data == "[DONE]") {
                    println("[onEvent] (done) 结束流: $data")
                    eventSource.cancel()
                    close()
                    return
                }

                try {
                    val jsonData = json.parseToJsonElement(data).jsonObject
                    val candidates = jsonData["candidates"]?.jsonArray ?: return
                    if (candidates.isEmpty()) return
                    val usage = parseUsageMeta(jsonData["usageMetadata"] as? JsonObject)
                    val messageChunk = MessageChunk(
                        id = Uuid.random().toString(),
                        model = params.model.modelId,
                        choices = candidates.mapIndexed { index, candidate ->
                            val candidateObj = candidate.jsonObject
                            val content = candidateObj["content"]?.jsonObject
                            val finishReason =
                                candidateObj["finishReason"]?.jsonPrimitive?.contentOrNull

                            UIMessageChoice(
                                index = index,
                                delta = content?.let {
                                    parseMessage(
                                        JsonObject(
                                            mapOf(
                                                "role" to JsonPrimitive(
                                                    "model"
                                                ), "content" to it
                                            )
                                        )
                                    )
                                },
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
                        val bodyStr = response.body?.string()
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
                } finally {
                    close(exception ?: Exception("Stream failed"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                println("[onClosed] 连接已关闭")
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)

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
            put("system_instruction", buildJsonObject {
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
            if (params.model.outputModalities.contains(Modality.IMAGE)) {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                })
            }
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                put("thinkingConfig", buildJsonObject {
                    if (params.thinkingBudget != null) {
                        put("thinkingBudget", params.thinkingBudget)
                    }
                    if (params.thinkingBudget == null || params.thinkingBudget > 0) {
                        put("includeThoughts", true)
                    }
                })
            }
        })


        // Safety
//        put("safetySettings", buildJsonArray {
//            add(buildJsonObject {
//                put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT")
//                put("threshold", "BLOCK_NONE")
//            })
//            add(buildJsonObject {
//                put("category", "HARM_CATEGORY_HATE_SPEECH")
//                put("threshold", "BLOCK_NONE")
//            })
//            add(buildJsonObject {
//                put("category", "HARM_CATEGORY_HATE_SPEECH")
//                put("threshold", "BLOCK_NONE")
//            })
//            add(buildJsonObject {
//                put("category", "HARM_CATEGORY_DANGEROUS_CONTENT")
//                put("threshold", "BLOCK_NONE")
//            })
//        })

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
                                    element = json.encodeToJsonElement(tool.parameters)
                                        .removeElements(listOf("const", "exclusiveMaximum", "exclusiveMinimum", "format"))
                                )
                            })
                        }
                    })
                })
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

        return UIMessage(
            role = role,
            parts = parts
        )
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
                UIMessagePart.ToolCall(
                    toolCallId = "",
                    toolName = jsonObject["functionCall"]!!.jsonObject["name"]!!.jsonPrimitive.content,
                    arguments = json.encodeToString(jsonObject["functionCall"]!!.jsonObject["args"])
                )
            }

            jsonObject.containsKey("inlineData") -> {
                val inlineData = jsonObject["inlineData"]!!.jsonObject
                val mime = inlineData["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                val data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                require(mime.startsWith("image/")) {
                    "Only image mime type is supported"
                }
                UIMessagePart.Image(data)
            }

            else -> error("unknown message part type: $jsonObject")
        }
    }

    private fun buildContents(messages: List<UIMessage>): JsonArray {
        return buildJsonArray {
            messages
                .filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }
                .forEachIndexed { index, message ->
                    add(buildJsonObject {
                        put("role", commonRoleToGoogleRole(message.role))
                        putJsonArray("parts") {
                            for (part in message.parts) {
                                when (part) {
                                    is UIMessagePart.Text -> {
                                        add(buildJsonObject {
                                            put("text", part.text)
                                        })
                                    }

                                    is UIMessagePart.Image -> {
                                        part.encodeBase64(false).onSuccess { base64Data ->
                                            add(buildJsonObject {
                                                put("inline_data", buildJsonObject {
                                                    put("mime_type", "image/png")
                                                    put("data", base64Data)
                                                })
                                            })
                                        }
                                    }

                                    is UIMessagePart.ToolCall -> {
                                        add(buildJsonObject {
                                            put("functionCall", buildJsonObject {
                                                put("name", part.toolName)
                                                put("args", json.parseToJsonElement(part.arguments))
                                            })
                                        })
                                    }

                                    is UIMessagePart.ToolResult -> {
                                        add(buildJsonObject {
                                            put("functionResponse", buildJsonObject {
                                                put("name", part.toolName)
                                                put("response", buildJsonObject {
                                                    put("result", part.content)
                                                })
                                            })
                                        })
                                    }

                                    else -> {
                                        // Unsupported part type
                                    }
                                }
                            }
                        }
                    })
                }
        }
    }

    private fun parseUsageMeta(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) {
            return null
        }
        return TokenUsage(
            promptTokens = jsonObject["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["totalTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
        )
    }
}