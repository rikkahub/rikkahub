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

class AliyunVideoGenerationProvider(
    private val client: OkHttpClient,
) : VideoGenerationProvider<VideoGenerationProviderSetting.Aliyun> {
    override val id: String = PROVIDER_ID

    override suspend fun create(
        setting: VideoGenerationProviderSetting.Aliyun,
        request: VideoGenerationRequest,
    ): Result<VideoGenerationTask> = runCatching {
        val body = buildCreateBody(setting, request)
        val httpRequest = authorizedRequest(
            setting,
            "${setting.baseUrl.trimEnd('/')}/services/aigc/video-generation/video-synthesis",
        ).addHeader("X-DashScope-Async", "enable")
            .postJson(body)
            .build()
        parseTask(client.executeJson(httpRequest, id), setting.model)
    }

    override suspend fun query(
        setting: VideoGenerationProviderSetting.Aliyun,
        taskId: String,
    ): Result<VideoGenerationTask> = runCatching {
        val httpRequest = authorizedRequest(
            setting,
            "${setting.baseUrl.trimEnd('/')}/tasks/$taskId",
        ).get().build()
        parseTask(client.executeJson(httpRequest, id), setting.model)
    }

    internal fun buildCreateBody(
        setting: VideoGenerationProviderSetting.Aliyun,
        request: VideoGenerationRequest,
    ): JsonObject = buildJsonObject {
        put("model", setting.model)
        put("input", buildJsonObject {
            request.prompt?.takeIf(String::isNotBlank)?.let { put("prompt", it) }
            if (request.inputs.isNotEmpty()) {
                put("media", buildJsonArray {
                    request.inputs.forEach { input ->
                        add(buildJsonObject {
                            input.extra.forEach { (key, value) -> put(key, value) }
                            when (input) {
                                is VideoGenerationInput.Image -> {
                                    put(
                                        "type", when (input.role) {
                                            ImageRole.FIRST_FRAME -> "first_frame"
                                            ImageRole.LAST_FRAME -> "last_frame"
                                            ImageRole.REFERENCE -> "reference_image"
                                        }
                                    )
                                    put("url", input.url)
                                }

                                is VideoGenerationInput.Video -> {
                                    put("type", "reference_video")
                                    put("url", input.url)
                                }

                                is VideoGenerationInput.Audio -> {
                                    put("type", "reference_audio")
                                    put("url", input.url)
                                }

                                is VideoGenerationInput.Document -> {
                                    put("type", "file")
                                    put("url", input.url)
                                }

                                is VideoGenerationInput.WebPage -> {
                                    put("type", "link")
                                    put("url", input.url)
                                }

                                is VideoGenerationInput.Raw ->
                                    input.value.forEach { (key, value) -> put(key, value) }
                            }
                        })
                    }
                })
            }
        })
        put("parameters", buildJsonObject {
            request.extraParameters.forEach { (key, value) -> put(key, value) }
            request.resolution?.let { put("resolution", it) }
            request.aspectRatio?.let { put("ratio", it) }
            request.durationSeconds?.let { put("duration", it) }
            request.generateAudio?.let { put("audio", it) }
            request.watermark?.let { put("watermark", it) }
            request.seed?.let { put("seed", it) }
            request.promptEnhancement?.let { put("prompt_extend", it) }
        })
        require(request.callbackUrl == null) {
            "Aliyun uses account-level asynchronous callbacks instead of a callback_url request field"
        }
    }

    internal fun parseTask(root: JsonObject, fallbackModel: String? = null): VideoGenerationTask {
        val output = root.obj("output") ?: root
        val usageObject = root.obj("usage")
        val videoUrl = output.string("video_url")
        val errorMessage = output.string("message") ?: root.string("message")
        return VideoGenerationTask(
            id = output.string("task_id") ?: error("Aliyun response does not contain task_id"),
            provider = id,
            model = output.string("model") ?: fallbackModel,
            status = mapStatus(output.string("task_status")),
            outputs = videoUrl?.let {
                listOf(
                    VideoGenerationOutput(
                        url = it,
                        durationSeconds = usageObject?.double("output_video_duration")
                            ?: usageObject?.double("duration"),
                        resolution = usageObject?.string("SR"),
                        aspectRatio = usageObject?.string("ratio"),
                    )
                )
            }.orEmpty(),
            error = errorMessage?.let { VideoGenerationError(root.string("code"), it) },
            usage = usageObject?.let {
                VideoGenerationUsage(
                    inputSeconds = it.double("input_video_duration"),
                    outputSeconds = it.double("output_video_duration"),
                    totalSeconds = it.double("duration"),
                )
            },
            metadata = root,
        )
    }

    private fun authorizedRequest(
        setting: VideoGenerationProviderSetting.Aliyun,
        url: String,
    ): Request.Builder = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer ${setting.apiKey}")
        .addHeader("Content-Type", "application/json")

    private fun mapStatus(status: String?): VideoGenerationStatus = when (status?.uppercase()) {
        "PENDING" -> VideoGenerationStatus.QUEUED
        "RUNNING" -> VideoGenerationStatus.RUNNING
        "SUCCEEDED" -> VideoGenerationStatus.SUCCEEDED
        "FAILED" -> VideoGenerationStatus.FAILED
        "CANCELED", "CANCELLED" -> VideoGenerationStatus.CANCELLED
        else -> VideoGenerationStatus.UNKNOWN
    }

    private companion object {
        const val PROVIDER_ID = "aliyun"
    }
}
