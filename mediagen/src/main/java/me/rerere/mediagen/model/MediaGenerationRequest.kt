package me.rerere.mediagen.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 与供应商无关的媒体生成请求，图像和视频共用。
 *
 * [resolution] 对视频是清晰度档位（如 `1080P`），对图像是像素尺寸（如 `1536x1024`）；[count] 是期望的
 * 产出数量。适配器不支持的公共字段会在提交前直接报错，而不是静默忽略。
 *
 * [extraParameters] 用于尚未进入公共抽象的供应商参数。适配器会先写入这些参数，再用公共字段
 * 覆盖同名项，避免调用方绕过公共字段的统一语义。
 */
@Serializable
data class MediaGenerationRequest(
    val prompt: String? = null,
    val inputs: List<MediaGenerationInput> = emptyList(),
    val count: Int? = null,
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
        require(count == null || count > 0) { "count must be positive" }
        require(durationSeconds == null || durationSeconds == -1 || durationSeconds > 0) {
            "durationSeconds must be positive or -1"
        }
        require(seed == null || seed >= 0) { "seed must be non-negative" }
    }
}
