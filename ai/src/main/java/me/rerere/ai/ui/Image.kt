package me.rerere.ai.ui

import kotlinx.serialization.Serializable

@Serializable
data class ImageGenerationItem(
    val data: String,
    val mimeType: String,
    val partial: Boolean = false,
    val partialImageIndex: Int? = null,
)

@Serializable
enum class ImageAspectRatio {
    SQUARE,
    LANDSCAPE,
    PORTRAIT
}
