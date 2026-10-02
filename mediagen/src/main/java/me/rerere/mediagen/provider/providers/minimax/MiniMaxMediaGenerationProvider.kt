package me.rerere.mediagen.provider.providers.minimax

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationError
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaGenerationUsage
import me.rerere.mediagen.provider.MediaGenerationProvider
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.double
import me.rerere.mediagen.provider.providers.executeJson
import me.rerere.mediagen.provider.providers.int
import me.rerere.mediagen.provider.providers.long
import me.rerere.mediagen.provider.providers.obj
import me.rerere.mediagen.provider.providers.postJson
import me.rerere.mediagen.provider.providers.string
import okhttp3.OkHttpClient
import okhttp3.Request

class MiniMaxMediaGenerationProvider(
    private val client: OkHttpClient,
) : MediaGenerationProvider<MediaGenerationProviderSetting.MiniMax> {
    override val id: String = PROVIDER_ID

    override suspend fun create(
        setting: MediaGenerationProviderSetting.MiniMax,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> = runCatching {
        require(!request.prompt.isNullOrBlank()) { "MiniMax H3 requires a non-empty prompt" }
        val httpRequest = authorizedRequest(
            setting,
            "${setting.baseUrl.trimEnd('/')}/video_generation",
        ).postJson(buildCreateBody(model, request)).build()
        val root = client.executeJson(httpRequest, id)
        MediaGenerationTask(
            id = root.string("task_id") ?: error("MiniMax response does not contain task_id"),
            provider = id,
            model = model.modelId,
            status = MediaGenerationStatus.QUEUED,
            metadata = root,
        )
    }

    override suspend fun query(
        setting: MediaGenerationProviderSetting.MiniMax,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> = runCatching {
        val httpRequest = authorizedRequest(
            setting,
            "${setting.baseUrl.trimEnd('/')}/query/video_generation/$taskId",
        ).get().build()
        parseTask(client.executeJson(httpRequest, id))
    }

    internal fun buildCreateBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", model.modelId)
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
                        is MediaGenerationInput.Image -> {
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

                        is MediaGenerationInput.Video -> {
                            put("type", "video_url")
                            put("video_url", buildJsonObject { put("url", input.url) })
                            put("role", "reference_video")
                        }

                        is MediaGenerationInput.Audio -> {
                            put("type", "audio_url")
                            put("audio_url", buildJsonObject { put("url", input.url) })
                            put("role", "reference_audio")
                        }

                        is MediaGenerationInput.Document,
                        is MediaGenerationInput.WebPage ->
                            error("MiniMax H3 does not support ${input::class.simpleName} input")

                        is MediaGenerationInput.Raw ->
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

    internal fun parseTask(root: JsonObject): MediaGenerationTask {
        val task = root.obj("task") ?: error("MiniMax response does not contain task")
        val content = task.obj("content")
        val usageObject = task.obj("usage")
        val errorObject = task.obj("error")
        val videoUrl = content?.string("url")
        return MediaGenerationTask(
            id = task.string("id") ?: error("MiniMax task does not contain id"),
            provider = id,
            model = task.string("model"),
            status = mapStatus(task.string("status")),
            outputs = videoUrl?.let {
                listOf(
                    MediaGenerationOutput(
                        url = it,
                        mimeType = "video/mp4",
                        durationSeconds = task.double("duration"),
                        resolution = task.string("resolution"),
                        aspectRatio = task.string("ratio"),
                    )
                )
            }.orEmpty(),
            error = errorObject?.string("message")?.let {
                MediaGenerationError(errorObject.string("code") ?: errorObject.string("type"), it)
            },
            createdAtEpochSeconds = task.long("created_at"),
            updatedAtEpochSeconds = task.long("updated_at"),
            usage = usageObject?.let {
                MediaGenerationUsage(
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
        setting: MediaGenerationProviderSetting.MiniMax,
        url: String,
    ): Request.Builder = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer ${setting.apiKey}")
        .addHeader("Content-Type", "application/json")

    private fun mapStatus(status: String?): MediaGenerationStatus = when (status?.lowercase()) {
        "queued" -> MediaGenerationStatus.QUEUED
        "running" -> MediaGenerationStatus.RUNNING
        "succeeded" -> MediaGenerationStatus.SUCCEEDED
        "failed" -> MediaGenerationStatus.FAILED
        "cancelled", "canceled" -> MediaGenerationStatus.CANCELLED
        else -> MediaGenerationStatus.UNKNOWN
    }

    private companion object {
        const val PROVIDER_ID = "minimax"
    }
}
