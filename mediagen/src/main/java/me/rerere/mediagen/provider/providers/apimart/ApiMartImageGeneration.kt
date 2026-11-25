package me.rerere.mediagen.provider.providers.apimart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.executeJson
import me.rerere.mediagen.provider.providers.postJson
import me.rerere.mediagen.provider.providers.toUrlOrDataUri
import okhttp3.OkHttpClient

/**
 * APIMart 的图像接口（GPT-Image 等）：`POST /images/generations` 提交任务，带 `image_urls` 就是编辑 / 参考图生图。
 */
internal class ApiMartImageGeneration(
    private val client: OkHttpClient,
) {
    private val id = ApiMartMediaGenerationProvider.PROVIDER_ID

    suspend fun create(
        setting: MediaGenerationProviderSetting.ApiMart,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): MediaGenerationTask {
        // 本地图片要读出来编码成 data URI
        val body = withContext(Dispatchers.IO) { buildBody(model, request) }
        val httpRequest = setting.request("/images/generations").postJson(body).build()
        return ApiMartMediaGenerationProvider.parseSubmission(client.executeJson(httpRequest, id), model)
    }

    internal fun buildBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        validate(request)
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", model.modelId)
        put("prompt", request.prompt)
        val images = request.inputs.map { input ->
            require(input is MediaGenerationInput.Image) {
                "APIMart image generation does not support ${input::class.simpleName} input"
            }
            JsonPrimitive(input.toUrlOrDataUri())
        }
        if (images.isNotEmpty()) put("image_urls", JsonArray(images))
        request.count?.let { put("n", it) }

        // size 既可以是比例（配合 resolution 档位），也可以是像素尺寸（此时 resolution 被忽略）
        val resolution = request.resolution?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
        val aspectRatio = request.aspectRatio?.takeIf(String::isNotBlank)
        if (resolution != null && PIXEL_SIZE.matches(resolution)) {
            require(aspectRatio == null) { "APIMart image size takes either a pixel size or an aspect ratio" }
            put("size", resolution.lowercase())
        } else {
            // 档位只接受小写的 1k / 2k / 4k
            resolution?.let { put("resolution", it.lowercase()) }
            aspectRatio?.let { put("size", it) }
        }
    }

    private fun validate(request: MediaGenerationRequest) {
        require(!request.prompt.isNullOrBlank()) { "APIMart image generation requires a non-empty prompt" }
        require(request.durationSeconds == null) { "APIMart image generation does not expose duration" }
        require(request.generateAudio == null) { "APIMart image generation does not expose generateAudio" }
        require(request.watermark == null) { "APIMart image generation does not expose watermark" }
        require(request.seed == null) { "APIMart image generation does not expose seed" }
        require(request.promptEnhancement == null) {
            "APIMart image generation does not expose prompt enhancement"
        }
        require(request.callbackUrl == null) { "APIMart image generation does not expose a callback url" }
    }
}

private val PIXEL_SIZE = Regex("""\d+[xX]\d+""")
