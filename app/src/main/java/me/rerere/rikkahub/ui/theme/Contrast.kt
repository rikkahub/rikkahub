package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

private fun contrastRatio(foreground: Color, background: Color): Float {
    val foregroundLuminance = foreground.luminance()
    val backgroundLuminance = background.luminance()
    val lighter = max(foregroundLuminance, backgroundLuminance)
    val darker = min(foregroundLuminance, backgroundLuminance)
    return (lighter + 0.05f) / (darker + 0.05f)
}

fun Color.preferredContentColor(): Color {
    val blackContrast = contrastRatio(Color.Black, this)
    val whiteContrast = contrastRatio(Color.White, this)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}
