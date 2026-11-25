package me.rerere.mediagen.provider.providers.apimart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.executeJson
import me.rerere.mediagen.provider.providers.postJson
import okhttp3.OkHttpClient

/**
 * APIMart 的视频接口（MiniMax H3 等）：`POST /videos/generations` 提交任务。生成方式由带了哪些字段决定：
 * 首帧 / 尾帧是图生视频，参考图、参考视频、参考音频是多模态参考，两者不能混用。
 */
internal class ApiMartVideoGeneration(
    private val client: OkHttpClient,
) {
    private val id = ApiMartMediaGenerationProvider.PROVIDER_ID

    suspend fun create(
        setting: MediaGenerationProviderSetting.ApiMart,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): MediaGenerationTask {
        val httpRequest = setting.request("/videos/generations").postJson(buildCreateBody(model, request)).build()
        return ApiMartMediaGenerationProvider.parseSubmission(client.executeJson(httpRequest, id), model)
    }

    internal fun buildCreateBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", model.modelId)
        request.prompt?.takeIf(String::isNotBlank)?.let { put("prompt", it) }

        // 图片角色写在 image_with_roles 里，不依赖图片数量推断首尾帧
        val images = mutableListOf<JsonObject>()
        val videos = mutableListOf<JsonPrimitive>()
        val audios = mutableListOf<JsonPrimitive>()
        request.inputs.forEach { input ->
            when (input) {
                is MediaGenerationInput.Image -> images += buildJsonObject {
                    input.extra.forEach { (key, value) -> put(key, value) }
                    put("url", input.url)
                    put(
                        "role",
                        when (input.role) {
                            ImageRole.FIRST_FRAME -> "first_frame"
                            ImageRole.LAST_FRAME -> "last_frame"
                            ImageRole.REFERENCE -> "reference_image"
                        }
                    )
                }

                is MediaGenerationInput.Video -> videos += JsonPrimitive(input.url)
                is MediaGenerationInput.Audio -> audios += JsonPrimitive(input.url)

                is MediaGenerationInput.Document,
                is MediaGenerationInput.WebPage ->
                    error("APIMart video generation does not support ${input::class.simpleName} input")

                // 原样作为 image_with_roles 的一项（url + role）
                is MediaGenerationInput.Raw -> images += JsonObject(input.extra + input.value)
            }
        }
        if (images.isNotEmpty()) put("image_with_roles", JsonArray(images))
        if (videos.isNotEmpty()) put("video_urls", JsonArray(videos))
        if (audios.isNotEmpty()) put("audio_urls", JsonArray(audios))

        request.resolution?.let { put("resolution", it) }
        request.durationSeconds?.let { put("duration", it) }
        request.aspectRatio?.let { put("aspect_ratio", it) }
        request.watermark?.let { put("watermark", it) }
        request.callbackUrl?.let { put("webhook", it) }
        require(request.count == null || request.count == 1) { "APIMart generates one video per task" }
        require(request.generateAudio == null) { "APIMart video generation does not expose generateAudio" }
        require(request.seed == null) { "APIMart video generation does not expose seed" }
        require(request.promptEnhancement == null) {
            "APIMart video generation does not expose prompt enhancement"
        }
    }
}
