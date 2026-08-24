package me.rerere.videogen.provider

import me.rerere.videogen.model.VideoGenerationRequest
import me.rerere.videogen.model.VideoGenerationTask

/**
 * 视频生成供应商只负责异步任务的提交与查询，不在网络层内部隐式轮询。
 */
interface VideoGenerationProvider<S : VideoGenerationProviderSetting> {
    val id: String

    suspend fun create(
        setting: S,
        request: VideoGenerationRequest,
    ): Result<VideoGenerationTask>

    suspend fun query(
        setting: S,
        taskId: String,
    ): Result<VideoGenerationTask>
}
