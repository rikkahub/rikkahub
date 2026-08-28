package me.rerere.videogen.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.videogen.model.ImageRole
import me.rerere.videogen.model.VideoGenerationError
import me.rerere.videogen.model.VideoGenerationInput
import me.rerere.videogen.model.VideoGenerationOutput
import me.rerere.videogen.model.VideoGenerationRequest
import me.rerere.videogen.model.VideoGenerationStatus
import me.rerere.videogen.model.VideoGenerationTask
import me.rerere.videogen.model.VideoGenerationUsage
import me.rerere.videogen.provider.VideoGenerationProvider
import me.rerere.videogen.provider.VideoGenerationProviderSetting
import okhttp3.OkHttpClient
import okhttp3.Request

class VolcengineVideoGenerationProvider(
    private val client: OkHttpClient,
) : VideoGenerationProvider<VideoGenerationProviderSetting.Volcengine> {
    override val id: String = PROVIDER_ID

    override suspend fun create(
        setting: VideoGenerationProviderSetting.Volcengine,
        request: VideoGenerationRequest,
    ): Result<VideoGenerationTask> = runCatching {
        val httpRequest = authorizedRequest(
            setting,
            "${setting.baseUrl.trimEnd('/')}/contents/generations/tasks",
        ).postJson(buildCreateBody(setting, request)).build()
        parseTask(client.executeJson(httpRequest, id), setting.model)
    }

    override suspend fun query(
        setting: VideoGenerationProviderSetting.Volcengine,
        taskId: String,
    ): Result<VideoGenerationTask> = runCatching {
        val httpRequest = authorizedRequest(
            setting,
            "${setting.baseUrl.trimEnd('/')}/contents/generations/tasks/$taskId",
        ).get().build()
        parseTask(client.executeJson(httpRequest, id), setting.model)
    }

    internal fun buildCreateBody(
        setting: VideoGenerationProviderSetting.Volcengine,
        request: VideoGenerationRequest,
    ): JsonObject = buildJsonObject {
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", setting.model)
        put("content", buildJsonArray {
            request.prompt?.takeIf(String::isNotBlank)?.let { prompt ->
                add(buildJsonObject {
                    put("type", "text")
                    put("text", prompt)
                })
            }
            request.inputs.forEach { input ->
                add(buildJsonObject {
                    input.extra.forEach { (key, value) -> put(key, value) }
                    when (input) {
                        is VideoGenerationInput.Image -> {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", input.url) })
                            put(
                                "role", when (input.role) {
                                    ImageRole.FIRST_FRAME -> "first_frame"
                                    ImageRole.LAST_FRAME -> "last_frame"
                                    ImageRole.REFERENCE -> "reference_image"
                                }
                            )
                        }

                        is VideoGenerationInput.Video -> {
                            put("type", "video_url")
                            put("video_url", buildJsonObject { put("url", input.url) })
                            put("role", "reference_video")
                        }

                        is VideoGenerationInput.Audio -> {
                            put("type", "audio_url")
                            put("audio_url", buildJsonObject { put("url", input.url) })
                            put("role", "reference_audio")
                        }

                        is VideoGenerationInput.Document,
                        is VideoGenerationInput.WebPage ->
                            error("Volcengine does not support document or web page inputs")

                        is VideoGenerationInput.Raw ->
                            input.value.forEach { (key, value) -> put(key, value) }
                    }
                })
            }
        })
        request.resolution?.let { put("resolution", it) }
        request.aspectRatio?.let { put("ratio", it) }
        request.durationSeconds?.let { put("duration", it) }
        request.generateAudio?.let { put("generate_audio", it) }
        request.watermark?.let { put("watermark", it) }
        request.seed?.let { put("seed", it) }
        require(request.promptEnhancement == null) {
            "Volcengine does not expose prompt enhancement in the video generation API"
        }
        request.callbackUrl?.let { put("callback_url", it) }
    }

    internal fun parseTask(root: JsonObject, fallbackModel: String? = null): VideoGenerationTask {
        val content = root.obj("content")
        val usageObject = root.obj("usage")
        val errorObject = root.obj("error")
        val videoUrl = content?.string("video_url")
        return VideoGenerationTask(
            id = root.string("id") ?: error("Volcengine response does not contain id"),
            provider = id,
            model = root.string("model") ?: fallbackModel,
            status = mapStatus(root.string("status")),
            outputs = videoUrl?.let {
                listOf(
                    VideoGenerationOutput(
                        url = it,
                        durationSeconds = root.double("duration"),
                        resolution = root.string("resolution"),
                        aspectRatio = root.string("ratio"),
                        lastFrameUrl = content.string("last_frame_url"),
                    )
                )
            }.orEmpty(),
            error = errorObject?.string("message")?.let {
                VideoGenerationError(errorObject.string("code"), it)
            },
            createdAtEpochSeconds = root.long("created_at"),
            updatedAtEpochSeconds = root.long("updated_at"),
            usage = usageObject?.let {
                VideoGenerationUsage(totalTokens = it.long("total_tokens"))
            },
            metadata = root,
        )
    }

    private fun authorizedRequest(
        setting: VideoGenerationProviderSetting.Volcengine,
        url: String,
    ): Request.Builder = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer ${setting.apiKey}")
        .addHeader("Content-Type", "application/json")

    private fun mapStatus(status: String?): VideoGenerationStatus = when (status?.lowercase()) {
        "queued" -> VideoGenerationStatus.QUEUED
        "running" -> VideoGenerationStatus.RUNNING
        "succeeded" -> VideoGenerationStatus.SUCCEEDED
        "failed" -> VideoGenerationStatus.FAILED
        "cancelled", "canceled" -> VideoGenerationStatus.CANCELLED
        "expired" -> VideoGenerationStatus.EXPIRED
        else -> VideoGenerationStatus.UNKNOWN
    }

    private companion object {
        const val PROVIDER_ID = "volcengine"
    }
}
