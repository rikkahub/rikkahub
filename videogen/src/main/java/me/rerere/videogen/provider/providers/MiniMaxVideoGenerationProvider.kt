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

class MiniMaxVideoGenerationProvider(
    private val client: OkHttpClient,
) : VideoGenerationProvider<VideoGenerationProviderSetting.MiniMax> {
    override val id: String = PROVIDER_ID

    override suspend fun create(
        setting: VideoGenerationProviderSetting.MiniMax,
        request: VideoGenerationRequest,
    ): Result<VideoGenerationTask> = runCatching {
        require(!request.prompt.isNullOrBlank()) { "MiniMax H3 requires a non-empty prompt" }
        val httpRequest = authorizedRequest(
            setting,
            "${setting.baseUrl.trimEnd('/')}/video_generation",
        ).postJson(buildCreateBody(setting, request)).build()
        val root = client.executeJson(httpRequest, id)
        VideoGenerationTask(
            id = root.string("task_id") ?: error("MiniMax response does not contain task_id"),
            provider = id,
            model = setting.model,
            status = VideoGenerationStatus.QUEUED,
            metadata = root,
        )
    }

    override suspend fun query(
        setting: VideoGenerationProviderSetting.MiniMax,
        taskId: String,
    ): Result<VideoGenerationTask> = runCatching {
        val httpRequest = authorizedRequest(
            setting,
            "${setting.baseUrl.trimEnd('/')}/query/video_generation/$taskId",
        ).get().build()
        parseTask(client.executeJson(httpRequest, id))
    }

    internal fun buildCreateBody(
        setting: VideoGenerationProviderSetting.MiniMax,
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
                            error("MiniMax H3 does not support ${input::class.simpleName} input")

                        is VideoGenerationInput.Raw ->
                            input.value.forEach { (key, value) -> put(key, value) }
                    }
                })
            }
        })
        request.resolution?.let { put("resolution", it) }
        request.durationSeconds?.let { put("duration", it) }
        request.aspectRatio?.let { put("ratio", it) }
        request.callbackUrl?.let { put("callback_url", it) }
        request.watermark?.let { put("aigc_watermark", it) }
        require(request.generateAudio == null) { "MiniMax H3 does not expose generateAudio as an output option" }
        require(request.seed == null) { "MiniMax H3 does not expose seed" }
        require(request.promptEnhancement == null) { "MiniMax H3 does not expose prompt enhancement" }
    }

    internal fun parseTask(root: JsonObject): VideoGenerationTask {
        val task = root.obj("task") ?: error("MiniMax response does not contain task")
        val content = task.obj("content")
        val usageObject = task.obj("usage")
        val errorObject = task.obj("error")
        val videoUrl = content?.string("url")
        return VideoGenerationTask(
            id = task.string("id") ?: error("MiniMax task does not contain id"),
            provider = id,
            model = task.string("model"),
            status = mapStatus(task.string("status")),
            outputs = videoUrl?.let {
                listOf(
                    VideoGenerationOutput(
                        url = it,
                        durationSeconds = task.double("duration"),
                        resolution = task.string("resolution"),
                        aspectRatio = task.string("ratio"),
                    )
                )
            }.orEmpty(),
            error = errorObject?.string("message")?.let {
                VideoGenerationError(errorObject.string("code") ?: errorObject.string("type"), it)
            },
            createdAtEpochSeconds = task.long("created_at"),
            updatedAtEpochSeconds = task.long("updated_at"),
            usage = usageObject?.let {
                VideoGenerationUsage(
                    inputSeconds = it.double("input_seconds"),
                    outputSeconds = it.double("output_seconds"),
                    totalSeconds = it.double("total_seconds"),
                    inputImageCount = it.int("input_image_count"),
                    totalTokens = it.long("total_tokens"),
                )
            },
            metadata = task,
        )
    }

    private fun authorizedRequest(
        setting: VideoGenerationProviderSetting.MiniMax,
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
        else -> VideoGenerationStatus.UNKNOWN
    }

    private companion object {
        const val PROVIDER_ID = "minimax"
    }
}
