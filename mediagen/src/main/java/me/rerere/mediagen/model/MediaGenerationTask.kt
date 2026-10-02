package me.rerere.mediagen.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

@Serializable
data class MediaGenerationTask(
    val id: String,
    val provider: String,
    val model: String? = null,
    val status: MediaGenerationStatus,
    val outputs: List<MediaGenerationOutput> = emptyList(),
    val error: MediaGenerationError? = null,
    val createdAtEpochSeconds: Long? = null,
    val updatedAtEpochSeconds: Long? = null,
    val usage: MediaGenerationUsage? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
) {
    val isTerminal: Boolean
        get() = status in TERMINAL_STATUSES

    companion object {
        private val TERMINAL_STATUSES = setOf(
            MediaGenerationStatus.SUCCEEDED,
            MediaGenerationStatus.FAILED,
            MediaGenerationStatus.CANCELLED,
            MediaGenerationStatus.EXPIRED,
        )
    }
}

@Serializable
enum class MediaGenerationStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED,
    UNKNOWN,
}

/**
 * 一项产出。异步任务返回有时效的 [url]，需要上层及时下载；同步接口内联返回的内容放在 [data]，
 * 它不参与序列化，持久化任务前要先把字节落盘。
 */
@Serializable
data class MediaGenerationOutput(
    val url: String? = null,
    @Transient val data: ByteArray? = null,
    val mimeType: String,
    val durationSeconds: Double? = null,
    val resolution: String? = null,
    val aspectRatio: String? = null,
    val lastFrameUrl: String? = null,
)

@Serializable
data class MediaGenerationError(
    val code: String? = null,
    val message: String,
)

@Serializable
data class MediaGenerationUsage(
    val inputSeconds: Double? = null,
    val outputSeconds: Double? = null,
    val totalSeconds: Double? = null,
    val inputImageCount: Int? = null,
    val totalTokens: Long? = null,
)
