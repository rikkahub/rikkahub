package me.rerere.ai.provider.providers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.toHeaders
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

private const val TAG = "OpenAIProvider"

object OpenAIProvider : Provider<ProviderSetting.OpenAI> {
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("X-Title", "RikkaHub")
                .addHeader("HTTP-Referer", "https://rikka-ai.com")
                .build()
            chain.proceed(request)
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        })
        .build()

    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
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

                Model(
                    modelId = id,
                    displayName = id,
                )
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody =
            buildChatCompletionRequest(messages, params)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/chat/completions")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .build()

        Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
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
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildChatCompletionRequest(
            messages,
            params,
            stream = true,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/chat/completions")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .build()

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        // just for debugging response body
        // println(client.newCall(request).await().body?.string())

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    println("[onEvent] (done) 结束流: $data")
                    eventSource.cancel()
                    close()
                    return
                }
                Log.d(TAG, "onEvent: $data")
                data
                    .trim()
                    .split("\n")
                    .filter { it.isNotBlank() }
                    .map { json.parseToJsonElement(it).jsonObject }
                    .forEach {
                        if (it["error"] != null) {
                            val error = it["error"]!!.parseErrorDetail()
                            throw error
                        }
                        val id = it["id"]?.jsonPrimitive?.contentOrNull ?: ""
                        val model = it["model"]?.jsonPrimitive?.contentOrNull ?: ""

                        val choices = it["choices"]?.jsonArray ?: JsonArray(emptyList())
                        val choiceList = buildList {
                            if (choices.isNotEmpty()) {
                                val choice = choices[0].jsonObject
                                val message =
                                    choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject
                                    ?: throw Exception("delta/message is null")
                                val finishReason =
                                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                                        ?: "unknown"
                                add(
                                    UIMessageChoice(
                                        index = 0,
                                        delta = parseMessage(message),
                                        message = null,
                                        finishReason = finishReason,
                                    )
                                )
                            }
                        }
                        val usage = parseTokenUsage(it["usage"] as? JsonObject)

                        val messageChunk = MessageChunk(
                            id = id,
                            model = model,
                            choices = choiceList,
                            usage = usage
                        )
                        trySend(messageChunk)
                    }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                t?.printStackTrace()
                println("[onFailure] 发生错误: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.string()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        println(bodyElement)
                        exception = bodyElement.parseErrorDetail()
                        Log.i(TAG, "onFailure: $exception")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    e.printStackTrace()
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource ")
            eventSource.cancel()
        }
    }

    private fun buildChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false,
    ): JsonObject {
        return buildJsonObject {
            put("model", params.model.modelId)
            put("messages", buildMessages(messages))

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }

            put("stream", stream)
            if (stream) {
                put("stream_options", buildJsonObject {
                    put("include_usage", true)
                })
            }
            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
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
                                        tool.parameters
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
        // 不能是openai o-系列模型，例如o3, o4-mini
        return !model.modelId.matches(Regex("o[0-9](-.+)?"))
    }

    private fun buildMessages(messages: List<UIMessage>) = buildJsonArray {
        111
        messages
            .filter {
                it.isValidToUpload()
            }
            .forEachIndexed { index, message ->
                if (message.role == MessageRole.TOOL) {
                    message.getToolResults().forEach { result ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("name", result.toolName)
                            put("tool_call_id", result.toolCallId)
                            put("content", json.encodeToString(result.content))
                        })
                    }
                    return@forEachIndexed
                }
                add(buildJsonObject {
                    // role
                    put("role", JsonPrimitive(message.role.name.lowercase()))

                    // content
                    if (message.parts.isOnlyTextPart()) {
                        // 如果只是纯文本，直接赋值给content
                        put(
                            "content",
                            message.parts.filterIsInstance<UIMessagePart.Text>().first().text
                        )
                    } else {
                        // 否则，使用parts构建
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
                                            part.encodeBase64().onSuccess {
                                                put("type", "image_url")
                                                put("image_url", buildJsonObject {
                                                    put("url", it)
                                                })
                                            }.onFailure {
                                                it.printStackTrace()
                                                println("encode image failed: ${part.url}")

                                                put("type", "text")
                                                put("text", "")
                                            }
                                        })
                                    }

                                    else -> {
                                        Log.w(
                                            TAG,
                                            "buildMessages: message part not supported: $part"
                                        )
                                        // DO NOTHING
                                    }
                                }
                            }
                        }
                    }

                    // tool_calls
                    message.getToolCalls()
                        .takeIf { it.isNotEmpty() }
                        ?.let { toolCalls ->
                            put("tool_calls", buildJsonArray {
                                toolCalls.forEach { toolCall ->
                                    add(buildJsonObject {
                                        put("id", toolCall.toolCallId)
                                        put("type", "function")
                                        put("function", buildJsonObject {
                                            put("name", toolCall.toolName)
                                            put("arguments", toolCall.arguments)
                                        })
                                    })
                                }
                            })
                        }
                })
            }
    }

    private fun parseMessage(jsonObject: JsonObject): UIMessage {
        val role = MessageRole.valueOf(
            jsonObject["role"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "ASSISTANT"
        )

        // 也许支持其他模态的输出content? 暂时只支持文本吧
        val content = jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val reasoning = jsonObject["reasoning_content"] ?: jsonObject["reasoning"]
        val toolCalls = jsonObject["tool_calls"] as? JsonArray ?: JsonArray(emptyList())

        return UIMessage(
            role = role,
            parts = buildList {
                if (reasoning?.jsonPrimitive?.contentOrNull != null) {
                    add(
                        UIMessagePart.Reasoning(
                            reasoning = reasoning.jsonPrimitive.contentOrNull ?: "",
                            createdAt = Clock.System.now(),
                            finishedAt = null
                        )
                    )
                }
                toolCalls.forEach { toolCalls ->
                    val type = toolCalls.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                    if (type != null && type != "function") error("tool call type not supported: $type")
                    val toolCallId = toolCalls.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    val toolName =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    val arguments =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull
                    add(
                        UIMessagePart.ToolCall(
                            toolCallId = toolCallId ?: "",
                            toolName = toolName ?: "",
                            arguments = arguments ?: ""
                        )
                    )
                }
                add(UIMessagePart.Text(content))
            },
            annotations = parseAnnotations(
                jsonObject["annotations"]?.jsonArray ?: JsonArray(
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
        )
    }

    private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
        val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
        val texts = filter { it is UIMessagePart.Text }.size
        return gonnaSend == texts && texts == 1
    }
}