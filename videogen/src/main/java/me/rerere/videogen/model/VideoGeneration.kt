package me.rerere.videogen.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 与供应商无关的视频生成请求。
 *
 * [extraParameters] 用于尚未进入公共抽象的供应商参数。适配器会先写入这些参数，再用公共字段
 * 覆盖同名项，避免调用方绕过公共字段的统一语义。
 */
@Serializable
data class VideoGenerationRequest(
    val prompt: String? = null,
    val inputs: List<VideoGenerationInput> = emptyList(),
    val resolution: String? = null,
    val aspectRatio: String? = null,
    val durationSeconds: Int? = null,
    val generateAudio: Boolean? = null,
    val watermark: Boolean? = null,
    val seed: Long? = null,
    val promptEnhancement: Boolean? = null,
    val callbackUrl: String? = null,
    val extraParameters: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(!prompt.isNullOrBlank() || inputs.isNotEmpty()) {
            "prompt and inputs cannot both be empty"
        }
        require(durationSeconds == null || durationSeconds == -1 || durationSeconds > 0) {
            "durationSeconds must be positive or -1"
        }
        require(seed == null || seed >= 0) { "seed must be non-negative" }
    }
}

@Serializable
sealed class VideoGenerationInput {
    abstract val extra: JsonObject

    @Serializable
    @SerialName("image")
    data class Image(
        val url: String,
        val role: ImageRole = ImageRole.REFERENCE,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : VideoGenerationInput()

    @Serializable
    @SerialName("video")
    data class Video(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : VideoGenerationInput()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : VideoGenerationInput()

    /** 阿里百炼万相 3.0 支持的文件输入。 */
    @Serializable
    @SerialName("document")
    data class Document(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : VideoGenerationInput()

    /** 阿里百炼万相 3.0 支持的公开网页输入。 */
    @Serializable
    @SerialName("web_page")
    data class WebPage(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : VideoGenerationInput()

    /**
     * 尚未进入公共抽象的供应商输入项，例如模型特有的样片任务引用。
     * 该对象会作为一个 content/media 元素原样交给适配器。
     */
    @Serializable
    @SerialName("raw")
    data class Raw(
        val value: JsonObject,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : VideoGenerationInput()
}

@Serializable
enum class ImageRole {
    @SerialName("first_frame")
    FIRST_FRAME,

    @SerialName("last_frame")
    LAST_FRAME,

    @SerialName("reference")
    REFERENCE,
}

@Serializable
data class VideoGenerationTask(
    val id: String,
    val provider: String,
    val model: String? = null,
    val status: VideoGenerationStatus,
    val outputs: List<VideoGenerationOutput> = emptyList(),
    val error: VideoGenerationError? = null,
    val createdAtEpochSeconds: Long? = null,
    val updatedAtEpochSeconds: Long? = null,
    val usage: VideoGenerationUsage? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
) {
    val isTerminal: Boolean
        get() = status in TERMINAL_STATUSES

    companion object {
        private val TERMINAL_STATUSES = setOf(
            VideoGenerationStatus.SUCCEEDED,
            VideoGenerationStatus.FAILED,
            VideoGenerationStatus.CANCELLED,
            VideoGenerationStatus.EXPIRED,
        )
    }
}

@Serializable
enum class VideoGenerationStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED,
    UNKNOWN,
}

@Serializable
data class VideoGenerationOutput(
    val url: String,
    val mimeType: String = "video/mp4",
    val durationSeconds: Double? = null,
    val resolution: String? = null,
    val aspectRatio: String? = null,
    val lastFrameUrl: String? = null,
)

@Serializable
data class VideoGenerationError(
    val code: String? = null,
    val message: String,
)

@Serializable
data class VideoGenerationUsage(
    val inputSeconds: Double? = null,
    val outputSeconds: Double? = null,
    val totalSeconds: Double? = null,
    val inputImageCount: Int? = null,
    val totalTokens: Long? = null,
)
