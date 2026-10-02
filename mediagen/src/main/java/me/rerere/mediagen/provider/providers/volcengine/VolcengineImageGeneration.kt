package me.rerere.mediagen.provider.providers.volcengine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.MediaGenerationError
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaGenerationUsage
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.bearerRequest
import me.rerere.mediagen.provider.providers.executeJson
import me.rerere.mediagen.provider.providers.imageMimeType
import me.rerere.mediagen.provider.providers.long
import me.rerere.mediagen.provider.providers.obj
import me.rerere.mediagen.provider.providers.postJson
import me.rerere.mediagen.provider.providers.string
import me.rerere.mediagen.provider.providers.toUrlOrDataUri
import me.rerere.mediagen.provider.providers.urlFileExtension
import okhttp3.OkHttpClient
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * Seedream 图像生成。带图片输入时是图生图 / 多图参考，接口同步返回结果。
 */
internal class VolcengineImageGeneration(
    private val client: OkHttpClient,
) {
    private val id = VolcengineMediaGenerationProvider.PROVIDER_ID

    suspend fun create(
        setting: MediaGenerationProviderSetting.Volcengine,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): MediaGenerationTask {
        // 本地图片要读出来编码成 data URI
        val body = withContext(Dispatchers.IO) { buildBody(model, request) }
        val httpRequest = bearerRequest("${setting.baseUrl.trimEnd('/')}/images/generations", setting.apiKey)
            .postJson(body)
            .build()
        return parseTask(client.executeJson(httpRequest, id), model.modelId)
    }

    internal fun buildBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        validate(request)
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", model.modelId)
        request.prompt?.takeIf(String::isNotBlank)?.let { put("prompt", it) }
        val images = request.inputs.map { input ->
            require(input is MediaGenerationInput.Image) {
                "Volcengine image generation does not support ${input::class.simpleName} input"
            }
            JsonPrimitive(input.toUrlOrDataUri())
        }
        when (images.size) {
            0 -> Unit
            1 -> put("image", images.single())
            else -> put("image", JsonArray(images))
        }
        request.resolution?.takeIf(String::isNotBlank)?.let { put("size", it) }
        request.watermark?.let { put("watermark", it) }
    }

    internal fun parseTask(root: JsonObject, fallbackModel: String? = null): MediaGenerationTask {
        val items = (root["data"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        // 组图里单张失败的项只有 error，没有图片
        val outputs = items.mapNotNull { item ->
            val url = item.string("url")
            val base64 = item.string("b64_json")
            if (url == null && base64 == null) return@mapNotNull null
            MediaGenerationOutput(
                url = url,
                data = base64?.let { Base64.decode(it) },
                mimeType = imageMimeType(item.string("output_format") ?: url?.urlFileExtension(), "image/jpeg"),
                resolution = item.string("size"),
            )
        }
        val errorObject = root.obj("error") ?: items.firstNotNullOfOrNull { it.obj("error") }
        return MediaGenerationTask(
            // 同步接口没有服务端任务 ID
            id = Uuid.random().toString(),
            provider = id,
            model = root.string("model") ?: fallbackModel,
            status = if (outputs.isEmpty()) MediaGenerationStatus.FAILED else MediaGenerationStatus.SUCCEEDED,
            outputs = outputs,
            error = if (outputs.isEmpty()) {
                MediaGenerationError(
                    code = errorObject?.string("code"),
                    message = errorObject?.string("message") ?: "Volcengine returned no image",
                )
            } else {
                null
            },
            createdAtEpochSeconds = root.long("created"),
            usage = root.obj("usage")?.let {
                MediaGenerationUsage(totalTokens = it.long("total_tokens"))
            },
            // 保留图层等逐图信息，但不把整张图片的 base64 放进 metadata
            metadata = JsonObject(root + ("data" to JsonArray(items.map { JsonObject(it - "b64_json") }))),
        )
    }

    private fun validate(request: MediaGenerationRequest) {
        require(request.count == null || request.count == 1) {
            "Volcengine generates one image per request; use sequential_image_generation in extraParameters for image sets"
        }
        require(request.aspectRatio == null) {
            "Volcengine image generation takes the aspect ratio from the prompt or a pixel resolution"
        }
        require(request.durationSeconds == null) { "Volcengine image generation does not expose duration" }
        require(request.generateAudio == null) { "Volcengine image generation does not expose generateAudio" }
        require(request.seed == null) { "Volcengine image generation does not expose seed" }
        require(request.promptEnhancement == null) {
            "Volcengine image generation configures prompt optimization through optimize_prompt_options in extraParameters"
        }
        require(request.callbackUrl == null) { "Volcengine image generation returns results synchronously" }
    }
}
