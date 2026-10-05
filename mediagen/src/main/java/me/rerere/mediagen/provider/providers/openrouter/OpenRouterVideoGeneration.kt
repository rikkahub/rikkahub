package me.rerere.mediagen.provider.providers.openrouter

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationError
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.executeJson
import me.rerere.mediagen.provider.providers.postJson
import me.rerere.mediagen.provider.providers.string
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * OpenRouter 的视频接口：提交后得到任务 ID，轮询到 `completed` 再从 `unsigned_urls` 下载。
 * 这些地址没有签名，下载时要带 [MediaGenerationProviderSetting.downloadHeaders]。
 */
internal class OpenRouterVideoGeneration(
    private val client: OkHttpClient,
) {
    private val id = OpenRouterMediaGenerationProvider.PROVIDER_ID

    suspend fun create(
        setting: MediaGenerationProviderSetting.OpenRouter,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): MediaGenerationTask {
        val httpRequest = setting.request("/videos").postJson(buildCreateBody(model, request)).build()
        return parseTask(setting, client.executeJson(httpRequest, id), model.modelId)
    }

    suspend fun query(
        setting: MediaGenerationProviderSetting.OpenRouter,
        model: MediaGenerationModel,
        taskId: String,
    ): MediaGenerationTask {
        val httpRequest = setting.request("/videos/$taskId").get().build()
        return parseTask(setting, client.executeJson(httpRequest, id), model.modelId)
    }

    internal fun buildCreateBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", model.modelId)
        request.prompt?.takeIf(String::isNotBlank)?.let { put("prompt", it) }

        // 首帧 / 尾帧和参考素材是两个字段
        val frames = mutableListOf<JsonObject>()
        val references = mutableListOf<JsonObject>()
        request.inputs.forEach { input ->
            when (input) {
                is MediaGenerationInput.Image -> {
                    val part = imageUrlPart(input.url, input.extra)
                    val frameType = when (input.role) {
                        ImageRole.FIRST_FRAME -> "first_frame"
                        ImageRole.LAST_FRAME -> "last_frame"
                        ImageRole.REFERENCE -> null
                    }
                    if (frameType == null) {
                        references += part
                    } else {
                        frames += JsonObject(part + ("frame_type" to JsonPrimitive(frameType)))
                    }
                }

                is MediaGenerationInput.Video -> references += buildJsonObject {
                    input.extra.forEach { (key, value) -> put(key, value) }
                    put("type", "video_url")
                    put("video_url", buildJsonObject { put("url", input.url) })
                }

                is MediaGenerationInput.Audio -> references += buildJsonObject {
                    input.extra.forEach { (key, value) -> put(key, value) }
                    put("type", "audio_url")
                    put("audio_url", buildJsonObject { put("url", input.url) })
                }

                is MediaGenerationInput.Document,
                is MediaGenerationInput.WebPage ->
                    error("OpenRouter does not support document or web page inputs")

                is MediaGenerationInput.Raw -> {
                    val part = JsonObject(input.extra + input.value)
                    if ("frame_type" in part) frames += part else references += part
                }
            }
        }
        if (frames.isNotEmpty()) put("frame_images", JsonArray(frames))
        if (references.isNotEmpty()) put("input_references", JsonArray(references))

        putResolution(request.resolution)
        request.aspectRatio?.let { put("aspect_ratio", it) }
        request.durationSeconds?.let { put("duration", it) }
        request.generateAudio?.let { put("generate_audio", it) }
        request.seed?.let { put("seed", it) }
        request.callbackUrl?.let { put("callback_url", it) }
        require(request.count == null || request.count == 1) { "OpenRouter generates one video per job" }
        require(request.watermark == null) { "OpenRouter does not expose watermark in the video generation API" }
        require(request.promptEnhancement == null) {
            "OpenRouter does not expose prompt enhancement in the video generation API"
        }
    }

    internal fun parseTask(
        setting: MediaGenerationProviderSetting.OpenRouter,
        root: JsonObject,
        fallbackModel: String? = null,
    ): MediaGenerationTask {
        val baseUrl = setting.baseUrl.toHttpUrlOrNull()
        val urls = (root["unsigned_urls"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        // 文档里失败原因是一句话，这里也接受和其它接口一样的 error 对象
        val errorMessage = when (val failure = root["error"]) {
            is JsonObject -> failure.string("message")
            is JsonPrimitive -> failure.contentOrNull
            else -> null
        }
        return MediaGenerationTask(
            id = root.string("id") ?: error("OpenRouter response does not contain id"),
            provider = id,
            model = fallbackModel,
            status = mapStatus(root.string("status")),
            outputs = urls.map { url ->
                MediaGenerationOutput(
                    // 文档示例里的 polling_url 是相对地址，这里的地址也按 baseUrl 解析成绝对地址
                    url = baseUrl?.resolve(url)?.toString() ?: url,
                    mimeType = "video/mp4",
                )
            },
            error = errorMessage?.let { MediaGenerationError(message = it) },
            metadata = root,
        )
    }

    private fun mapStatus(status: String?): MediaGenerationStatus = when (status?.lowercase()) {
        "pending" -> MediaGenerationStatus.QUEUED
        "in_progress" -> MediaGenerationStatus.RUNNING
        "completed" -> MediaGenerationStatus.SUCCEEDED
        "failed" -> MediaGenerationStatus.FAILED
        "cancelled", "canceled" -> MediaGenerationStatus.CANCELLED
        "expired" -> MediaGenerationStatus.EXPIRED
        else -> MediaGenerationStatus.UNKNOWN
    }
}
