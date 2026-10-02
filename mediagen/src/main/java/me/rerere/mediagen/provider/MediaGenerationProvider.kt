package me.rerere.mediagen.provider

import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationTask

/**
 * 媒体生成供应商只负责任务的提交与查询，不在网络层内部隐式轮询。
 *
 * 一个供应商对应一个厂商，内部按 [MediaGenerationModel.kind] 路由到图像或视频接口。
 * 同步返回结果的接口（如 OpenAI 图像）在 [create] 里直接给出终态任务，无需实现 [query]。
 *
 * 模型的 kind 是否受支持由 [MediaGenerationManager] 按 [MediaGenerationProviderSetting.supportedKinds] 统一校验。
 */
interface MediaGenerationProvider<S : MediaGenerationProviderSetting> {
    val id: String

    suspend fun create(
        setting: S,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask>

    suspend fun query(
        setting: S,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> =
        Result.failure(
            UnsupportedOperationException(
                "$id ${model.kind} generation returns results synchronously and has no task to query"
            )
        )
}
