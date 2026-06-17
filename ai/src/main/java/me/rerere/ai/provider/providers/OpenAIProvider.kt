package me.rerere.ai.provider.providers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelListProbe
import me.rerere.ai.provider.ProbeOutcome
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.jsonObjectHasField
import me.rerere.ai.provider.runChatProbe
import me.rerere.ai.provider.runModelListProbe
import me.rerere.ai.registry.guessModelType
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.getByKey
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.File
import java.io.IOException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class OpenAIProvider(
    private val client: OkHttpClient,
    context: Context? = null,
    // 见 ClaudeProvider：SSE 使用短 readTimeout 的专用客户端，快速失败而非挂起 10 分钟。
    private val streamClient: OkHttpClient = client,
) : Provider<ProviderSetting.OpenAI> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    private val chatCompletionsAPI = ChatCompletionsAPI(client = client, keyRoulette = keyRoulette, streamClient = streamClient)
    private val responseAPI = ResponseAPI(client = client, keyRoulette = keyRoulette, streamClient = streamClient)


    // listModels and probeModelList share ONE HTTP path (probeModelList) and ONE parser
    // (parseModels); listModels just collapses the probe back to the throwing contract its single
    // caller still expects, while probeModelList keeps the status/shape the connection classifier needs.
    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> {
        val probe = probeModelList(providerSetting)
        return when (val outcome = probe.outcome) {
            is ProbeOutcome.Http ->
                if (outcome.status in 200..299) probe.models
                else error("Failed to get models: ${outcome.status}")

            is ProbeOutcome.Transport -> error("Failed to get models: ${outcome.error}")
        }
    }

    override suspend fun probeModelList(providerSetting: ProviderSetting.OpenAI): ModelListProbe =
        withContext(Dispatchers.IO) {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()
            runModelListProbe(client, request) { parseModels(it) }
        }

    override suspend fun probeChat(
        providerSetting: ProviderSetting.OpenAI,
        modelId: String,
    ): ProbeOutcome = withContext(Dispatchers.IO) {
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        // Probe whichever generation API this provider actually uses: a Responses-API-only endpoint
        // has no /chat/completions, so probing chat there would read as unreachable. For the chat
        // path, honor the configured chatCompletionsPath (proxies remap it).
        val (url, body, successField) = if (providerSetting.useResponseApi) {
            Triple(
                "${providerSetting.baseUrl}/responses",
                buildJsonObject {
                    put("model", modelId)
                    put("input", JsonPrimitive("hi"))
                    put("max_output_tokens", JsonPrimitive(16))
                },
                "output",
            )
        } else {
            Triple(
                "${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}",
                buildJsonObject {
                    put("model", modelId)
                    putJsonArray("messages") {
                        add(buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive("hi"))
                        })
                    }
                    put("max_tokens", JsonPrimitive(1))
                },
                "choices",
            )
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .build()
        runChatProbe(client, request) { jsonObjectHasField(it, successField) }
    }

    private fun parseModels(bodyStr: String): List<Model> {
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { modelJson ->
            val modelObj = modelJson.jsonObject
            val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Model(
                modelId = id,
                displayName = id,
                type = guessModelType(id),
            )
        }
    }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val url = if (providerSetting.balanceOption.apiPath.startsWith("http")) {
            providerSetting.balanceOption.apiPath
        } else {
            "${providerSetting.baseUrl}${providerSetting.balanceOption.apiPath}"
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to get balance: ${response.code} ${response.body.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val value = bodyJson.getByKey(providerSetting.balanceOption.resultPath)
        val digitalValue = value.toFloatOrNull()
        if(digitalValue != null) {
            "%.2f".format(digitalValue)
        } else {
            value
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = if (providerSetting.useResponseApi) {
        responseAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = if (providerSetting.useResponseApi) {
        responseAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(params.input.isNotEmpty()) { "Embedding input cannot be empty" }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                if (params.input.size == 1) {
                    put("input", params.input.first())
                } else {
                    putJsonArray("input") {
                        params.input.forEach { add(JsonPrimitive(it)) }
                    }
                }
                params.dimensions?.let { put("dimensions", it) }
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/embeddings")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            // An HTTP error from the embedding endpoint (429/401/5xx) is an external-service / I/O
            // failure, not a programming-state error. It must be an IOException so the sole caller
            // (KnowledgeRetrievalTransformer) degrades RAG to best-effort instead of aborting the chat.
            throw IOException("Failed to generate embedding: ${response.code} ${response.body.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId

        val embeddings = data.map { embeddingJson ->
            val embeddingArray = embeddingJson.jsonObject["embedding"]?.jsonArray
                ?: error("No embedding in response")
            embeddingArray.map { it.jsonPrimitive.content.toFloat() }
        }

        EmbeddingGenerationResult(
            model = model,
            embeddings = embeddings
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        // Only the gpt-image-* family supports stream/partial_images; dall-e-* (and any other model
        // the user marked IMAGE) reject those params with a 400. Gate streaming on the family so
        // non-gpt-image models keep working via the one-shot path, wrapped as a single-batch flow.
        val stream = supportsImageStreaming(params.model.modelId)

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)
                if (stream) {
                    put("stream", true)
                    put("partial_images", params.partialImages.coerceIn(0, 3))
                }
                put(
                    "size", when (params.aspectRatio) {
                        ImageAspectRatio.SQUARE -> "1024x1024"
                        ImageAspectRatio.LANDSCAPE -> "1536x1024"
                        ImageAspectRatio.PORTRAIT -> "1024x1536"
                    }
                )
            }
                .mergeCustomBody(params.customBody)
                .let { if (stream) it.forceImageStream() else it }
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        return if (stream) {
            streamImageRequest(
                request = request,
                partialEventType = "image_generation.partial_image",
                completedEventType = "image_generation.completed",
            )
        } else {
            nonStreamingImageRequest(request)
        }
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        require(params.images.isNotEmpty()) {
            "At least one image is required"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        // See generateImage: gate stream/partial_images on the gpt-image-* family so dall-e-2 edits
        // (and other non-gpt-image IMAGE models) keep working via the one-shot path.
        val stream = supportsImageStreaming(params.model.modelId)
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", params.model.modelId)
            .addFormDataPart("prompt", params.prompt)
            .addFormDataPart("n", params.numOfImages.toString())
            .addFormDataPart(
                "size", when (params.aspectRatio) {
                    ImageAspectRatio.SQUARE -> "1024x1024"
                    ImageAspectRatio.LANDSCAPE -> "1536x1024"
                    ImageAspectRatio.PORTRAIT -> "1024x1536"
                }
            )
        if (stream) {
            bodyBuilder.addFormDataPart("partial_images", params.partialImages.coerceIn(0, 3).toString())
        }

        val imageFieldName = if (params.images.size == 1) "image" else "image[]"
        params.images.forEach { path ->
            val imageFile = File(path)
            require(imageFile.exists()) {
                "Image file does not exist: $path"
            }
            require(imageFile.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) {
                "Unsupported image file type for OpenAI edit: ${imageFile.extension}"
            }
            bodyBuilder.addFormDataPart(
                imageFieldName,
                imageFile.name,
                imageFile.asRequestBody(imageFile.imageMediaType().toMediaType())
            )
        }

        params.customBody.forEach { customBody ->
            val value = when (val element = customBody.value) {
                is JsonPrimitive -> element.contentOrNull ?: element.toString()
                else -> element.toString()
            }
            bodyBuilder.addFormDataPart(customBody.key, value)
        }
        if (stream) {
            bodyBuilder.addFormDataPart("stream", "true")
        }

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/edits")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .post(bodyBuilder.build())
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        return if (stream) {
            streamImageRequest(
                request = request,
                partialEventType = "image_edit.partial_image",
                completedEventType = "image_edit.completed",
            )
        } else {
            nonStreamingImageRequest(request)
        }
    }

    private fun streamImageRequest(
        request: Request,
        partialEventType: String,
        completedEventType: String,
    ): Flow<ImageGenerationItem> = callbackFlow {
        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    close()
                    return
                }

                val event = json.parseToJsonElement(data).jsonObject
                val item = parseImageStreamEvent(
                    event = event,
                    partialEventType = partialEventType,
                    completedEventType = completedEventType,
                    eventType = type,
                ) ?: return
                trySend(item)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // 不得吞掉失败：close(null) 会把 callbackFlow 当成正常完成，
                // 静默丢掉上游错误。失败必须以非空 Throwable 终止。
                val responseMessage = response?.let {
                    "${it.code} ${it.body.stringSafe() ?: ""}".trim()
                }
                close(t ?: IllegalStateException("Failed to stream image: $responseMessage"))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(streamClient)
            .newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
    }
    // NOTE: deliberately NOT .bufferStreamChunks() here. That helper raises the channel to
    // Channel.UNLIMITED for text streams, where hundreds of TINY delta frames can outrun the Room
    // writer. Image frames are the opposite: FEW (partial_images partials + one final per output
    // image, a couple dozen at most, inherently bounded by the API) but each carries a full
    // multi-megabyte base64 payload. An unlimited buffer would let every large payload pile up if the
    // collector lags; the callbackFlow's default bounded (64) channel is ample for the handful of
    // image frames and caps memory.

    /**
     * One-shot (non-streaming) image request for models that do not support stream/partial_images
     * (dall-e-2/dall-e-3 and any other IMAGE-typed model). The JSON `data[]` is parsed into final
     * [ImageGenerationItem]s and emitted as a single-batch flow, preserving the `Flow` seam the
     * streaming path uses. Mirrors the master non-streaming behavior (b64_json or url responses).
     */
    private fun nonStreamingImageRequest(request: Request): Flow<ImageGenerationItem> = flow {
        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to generate image: ${response.code} ${response.body.string()}")
            }
            val bodyStr = response.body.string()
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: error("No data in response")
            parseImageGenerationItems(data)
        }
        items.forEach { emit(it) }
    }

    private suspend fun parseImageGenerationItems(data: JsonArray): List<ImageGenerationItem> {
        return data.map { imageJson ->
            val imageObj = imageJson.jsonObject
            val b64Json = imageObj["b64_json"]?.jsonPrimitive?.contentOrNull

            if (b64Json != null) {
                ImageGenerationItem(
                    data = b64Json,
                    mimeType = "image/png",
                )
            } else {
                val url = imageObj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("No b64_json or url in response")
                downloadImageAsBase64(url)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadImageAsBase64(url: String): ImageGenerationItem {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to download generated image: ${response.code} ${response.body.string()}")
        }

        val body = response.body
        val mimeType = body.contentType()?.toString() ?: "image/png"
        val base64 = Base64.encode(body.bytes())

        return ImageGenerationItem(
            data = base64,
            mimeType = mimeType,
        )
    }

    private fun File.imageMediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private fun JsonObject.forceImageStream(): JsonObject =
        JsonObject(toMutableMap().apply {
            put("stream", JsonPrimitive(true))
        })

    companion object {
        private val SUPPORTED_EDIT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}

/**
 * Whether [modelId] is in the OpenAI gpt-image-* family, the only models that accept
 * `stream`/`partial_images` on /images/generations and /images/edits. dall-e-2/dall-e-3 (and any
 * other IMAGE-typed model the user enters) reject those params with a 400, so they must take the
 * one-shot path. Matched case-insensitively on a `gpt-image` prefix — covers gpt-image-1, gpt-image-2,
 * and dated/aliased variants — while excluding dall-e-* and arbitrary IDs. Pure, so JVM-testable.
 */
internal fun supportsImageStreaming(modelId: String): Boolean =
    modelId.lowercase().startsWith("gpt-image")

/**
 * Decode a single streaming-image SSE frame into an [ImageGenerationItem], or null when the frame
 * must be skipped (unrelated event type, or a matching type with no `b64_json` payload).
 *
 * Pure (no network, no OkHttp) so the partial-vs-final + mime-mapping wire contract the UI relies on
 * is unit-testable in isolation. [eventType] is the SSE `type:` line, used as a fallback when the
 * JSON body omits its own `type`.
 */
internal fun parseImageStreamEvent(
    event: JsonObject,
    partialEventType: String,
    completedEventType: String,
    eventType: String?,
): ImageGenerationItem? {
    val type = event["type"]?.jsonPrimitive?.contentOrNull ?: eventType
    if (type != partialEventType && type != completedEventType) return null

    val b64Json = event["b64_json"]?.jsonPrimitive?.contentOrNull ?: return null
    val outputFormat = event["output_format"]?.jsonPrimitive?.contentOrNull ?: "png"
    val partialImageIndex = event["partial_image_index"]?.jsonPrimitive?.intOrNull

    return ImageGenerationItem(
        data = b64Json,
        mimeType = outputFormat.toImageMimeType(),
        partial = type == partialEventType,
        partialImageIndex = partialImageIndex,
    )
}

private fun String.toImageMimeType(): String = when (lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "image/png"
}
