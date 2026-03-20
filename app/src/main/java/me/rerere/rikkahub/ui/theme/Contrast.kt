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

fun Color.contrastRatioAgainst(background: Color): Float {
    return contrastRatio(this, background)
}

fun Color.hasMinimumContrastAgainst(
    background: Color,
    minimumRatio: Float = 4.5f,
): Boolean {
    return contrastRatioAgainst(background) >= minimumRatio
}

fun Color.compositeAgainst(background: Color): Color {
    if (alpha >= 1f) {
        return this
    }

    val foregroundAlpha = alpha
    val backgroundAlpha = background.alpha
    val resultAlpha = foregroundAlpha + backgroundAlpha * (1f - foregroundAlpha)
    if (resultAlpha <= 0f) {
        return Color.Transparent
    }

    fun compositeChannel(foreground: Float, backgroundChannel: Float): Float {
        return (
            foreground * foregroundAlpha +
                backgroundChannel * backgroundAlpha * (1f - foregroundAlpha)
            ) / resultAlpha
    }

    return Color(
        red = compositeChannel(red, background.red),
        green = compositeChannel(green, background.green),
        blue = compositeChannel(blue, background.blue),
        alpha = resultAlpha,
    )
}

fun Color.preferredContentColor(): Color {
    val blackContrast = contrastRatio(Color.Black, this)
    val whiteContrast = contrastRatio(Color.White, this)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}
