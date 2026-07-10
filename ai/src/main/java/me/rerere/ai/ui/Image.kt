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
enum class ImageGenSize(val value: String) {
    AUTO("auto"),
    SQUARE_1024("1024x1024"),
    LANDSCAPE_1536("1536x1024"),
    PORTRAIT_1536("1024x1536"),
    SQUARE_256("256x256"),
    SQUARE_512("512x512"),
    LANDSCAPE_1792("1792x1024"),
    PORTRAIT_1792("1024x1792"),
}
