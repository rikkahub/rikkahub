package me.rerere.mediagen.provider.providers.apimart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.mediagen.model.MediaGenerationError
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProvider
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.bearerRequest
import me.rerere.mediagen.provider.providers.executeJson
import me.rerere.mediagen.provider.providers.imageMimeType
import me.rerere.mediagen.provider.providers.long
import me.rerere.mediagen.provider.providers.obj
import me.rerere.mediagen.provider.providers.string
import me.rerere.mediagen.provider.providers.urlFileExtension
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * APIMart 聚合平台：图像和视频都是异步任务，各自提交后得到任务 ID，再统一用 `GET /tasks/{id}` 轮询。
 */
class ApiMartMediaGenerationProvider(
    private val client: OkHttpClient,
) : MediaGenerationProvider<MediaGenerationProviderSetting.ApiMart> {
    override val id: String = PROVIDER_ID

    private val image = ApiMartImageGeneration(client)
    private val video = ApiMartVideoGeneration(client)

    override suspend fun create(
        setting: MediaGenerationProviderSetting.ApiMart,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> = runCatching {
        when (model.kind) {
            MediaKind.IMAGE -> image.create(setting, model, request)
            MediaKind.VIDEO -> video.create(setting, model, request)
        }
    }

    override suspend fun query(
        setting: MediaGenerationProviderSetting.ApiMart,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> = runCatching {
        val httpRequest = setting.request("/tasks/$taskId").get().build()
        parseTask(client.executeJson(httpRequest, id), model.modelId)
    }

    internal companion object {
        const val PROVIDER_ID = "apimart"

        // 提交响应的 data 是数组，每项带 task_id 和 submitted 状态
        fun parseSubmission(root: JsonObject, model: MediaGenerationModel): MediaGenerationTask {
            val item = when (val data = root["data"]) {
                is JsonArray -> data.firstOrNull() as? JsonObject
                is JsonObject -> data
                else -> null
            } ?: error("APIMart response does not contain data")
            return MediaGenerationTask(
                id = item.string("task_id") ?: error("APIMart response does not contain task_id"),
                provider = PROVIDER_ID,
                model = model.modelId,
                status = mapStatus(item.string("status")),
                metadata = item,
            )
        }

        // 查询响应的 data 是对象；结果在 result.images[].url[] 或 result.videos[].url
        fun parseTask(root: JsonObject, fallbackModel: String? = null): MediaGenerationTask {
            val data = root.obj("data") ?: error("APIMart response does not contain data")
            val result = data.obj("result")
            val outputs = result.urls("images").map { url ->
                MediaGenerationOutput(url = url, mimeType = imageMimeType(url.urlFileExtension(), "image/png"))
            } + result.urls("videos").map { url ->
                MediaGenerationOutput(url = url, mimeType = "video/mp4")
            }
            val errorObject = data.obj("error")
            return MediaGenerationTask(
                id = data.string("id") ?: error("APIMart task does not contain id"),
                provider = PROVIDER_ID,
                model = fallbackModel,
                status = mapStatus(data.string("status")),
                outputs = outputs,
                error = errorObject?.string("message")?.let {
                    MediaGenerationError(errorObject.string("code") ?: errorObject.string("type"), it)
                },
                createdAtEpochSeconds = data.long("created"),
                updatedAtEpochSeconds = data.long("completed"),
                // cost、credits_cost、progress、expires_at 等都留在这里
                metadata = data,
            )
        }

        // 文档里 url 是字符串数组，这里也接受单个字符串
        private fun JsonObject?.urls(name: String): List<String> =
            (this?.get(name) as? JsonArray).orEmpty().filterIsInstance<JsonObject>().flatMap { item ->
                when (val url = item["url"]) {
                    is JsonArray -> url.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    is JsonPrimitive -> listOfNotNull(url.contentOrNull)
                    else -> emptyList()
                }
            }

        private fun mapStatus(status: String?): MediaGenerationStatus = when (status?.lowercase()) {
            "submitted", "pending" -> MediaGenerationStatus.QUEUED
            "processing" -> MediaGenerationStatus.RUNNING
            "completed" -> MediaGenerationStatus.SUCCEEDED
            "failed" -> MediaGenerationStatus.FAILED
            "cancelled", "canceled" -> MediaGenerationStatus.CANCELLED
            else -> MediaGenerationStatus.UNKNOWN
        }
    }
}

internal fun MediaGenerationProviderSetting.ApiMart.request(path: String): Request.Builder =
    bearerRequest("${baseUrl.trimEnd('/')}$path", apiKey)
