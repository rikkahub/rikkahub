package me.rerere.mediagen.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** 模型产出的媒体类型。 */
@Serializable
enum class MediaKind {
    IMAGE,
    VIDEO,
}

/**
 * 厂商下的一个模型。[modelId] 是下发给接口的模型名，[kind] 决定适配器走图像还是视频协议。
 */
@Serializable
data class MediaGenerationModel(
    val modelId: String,
    val kind: MediaKind,
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
)
