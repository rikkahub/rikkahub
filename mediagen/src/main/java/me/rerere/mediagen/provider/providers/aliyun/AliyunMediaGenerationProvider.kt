package me.rerere.mediagen.provider.providers.aliyun

import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProvider
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import okhttp3.OkHttpClient

/**
 * 阿里云百炼：万相 / 千问图像走同步接口直接返回结果，万相视频是异步任务。
 */
class AliyunMediaGenerationProvider(
    client: OkHttpClient,
) : MediaGenerationProvider<MediaGenerationProviderSetting.Aliyun> {
    override val id: String = PROVIDER_ID

    private val image = AliyunImageGeneration(client)
    private val video = AliyunVideoGeneration(client)

    override suspend fun create(
        setting: MediaGenerationProviderSetting.Aliyun,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> = runCatching {
        when (model.kind) {
            MediaKind.IMAGE -> image.create(setting, model, request)
            MediaKind.VIDEO -> video.create(setting, model, request)
        }
    }

    override suspend fun query(
        setting: MediaGenerationProviderSetting.Aliyun,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> = when (model.kind) {
        MediaKind.IMAGE -> super.query(setting, model, taskId)
        MediaKind.VIDEO -> runCatching { video.query(setting, model, taskId) }
    }

    internal companion object {
        const val PROVIDER_ID = "aliyun"
    }
}

internal fun MediaGenerationProviderSetting.Aliyun.resolvedBaseUrl(): String {
    val placeholder = MediaGenerationProviderSetting.Aliyun.WORKSPACE_PLACEHOLDER
    val base = baseUrl.trimEnd('/')
    if (placeholder !in base) return base
    require(workspaceId.isNotBlank()) { "Aliyun workspaceId is required for $baseUrl" }
    return base.replace(placeholder, workspaceId.trim())
}
