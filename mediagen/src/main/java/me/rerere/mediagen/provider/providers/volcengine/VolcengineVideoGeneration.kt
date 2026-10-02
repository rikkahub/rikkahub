package me.rerere.mediagen.provider.providers.volcengine

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
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.double
import me.rerere.mediagen.provider.providers.executeJson
import me.rerere.mediagen.provider.providers.long
import me.rerere.mediagen.provider.providers.obj
import me.rerere.mediagen.provider.providers.postJson
import me.rerere.mediagen.provider.providers.string
import okhttp3.OkHttpClient
import okhttp3.Request

internal class VolcengineVideoGeneration(
    private val client: OkHttpClient,
) {
    private val id = VolcengineMediaGenerationProvider.PROVIDER_ID

    suspend fun create(
        setting: MediaGenerationProviderSetting.Volcengine,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): MediaGenerationTask {
        val httpRequest = authorizedRequest(
            setting,
            "${setting.baseUrl.trimEnd('/')}/contents/generations/tasks",
        ).postJson(buildCreateBody(model, request)).build()
        return parseTask(client.executeJson(httpRequest, id), model.modelId)
    }

    suspend fun query(
        setting: MediaGenerationProviderSetting.Volcengine,
        model: MediaGenerationModel,
        taskId: String,
    ): MediaGenerationTask {
        val httpRequest = authorizedRequest(
            setting,
            "${setting.baseUrl.trimEnd('/')}/contents/generations/tasks/$taskId",
        ).get().build()
        return parseTask(client.executeJson(httpRequest, id), model.modelId)
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
                            error("Volcengine does not support document or web page inputs")

                        is MediaGenerationInput.Raw ->
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

    internal fun parseTask(root: JsonObject, fallbackModel: String? = null): MediaGenerationTask {
        val content = root.obj("content")
        val usageObject = root.obj("usage")
        val errorObject = root.obj("error")
        val videoUrl = content?.string("video_url")
        return MediaGenerationTask(
            id = root.string("id") ?: error("Volcengine response does not contain id"),
            provider = id,
            model = root.string("model") ?: fallbackModel,
            status = mapStatus(root.string("status")),
            outputs = videoUrl?.let {
                listOf(
                    MediaGenerationOutput(
                        url = it,
                        mimeType = "video/mp4",
                        durationSeconds = root.double("duration"),
                        resolution = root.string("resolution"),
                        aspectRatio = root.string("ratio"),
                        lastFrameUrl = content.string("last_frame_url"),
                    )
                )
            }.orEmpty(),
            error = errorObject?.string("message")?.let {
                MediaGenerationError(errorObject.string("code"), it)
            },
            createdAtEpochSeconds = root.long("created_at"),
            updatedAtEpochSeconds = root.long("updated_at"),
            usage = usageObject?.let {
                MediaGenerationUsage(totalTokens = it.long("total_tokens"))
            },
            metadata = root,
        )
    }

    private fun authorizedRequest(
        setting: MediaGenerationProviderSetting.Volcengine,
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
        "expired" -> MediaGenerationStatus.EXPIRED
        else -> MediaGenerationStatus.UNKNOWN
    }
}
