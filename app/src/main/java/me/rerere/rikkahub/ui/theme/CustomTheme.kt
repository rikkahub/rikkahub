package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class CustomTheme(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val primaryColorArgb: Long = 0xFF6750A4,
    val secondaryColorArgb: Long? = null,
    val tertiaryColorArgb: Long? = null,
) {
    fun generateColorScheme(dark: Boolean): ColorScheme {
        val primary = Color(primaryColorArgb.toInt())
        val secondary = if (secondaryColorArgb != null) {
            Color(secondaryColorArgb.toInt())
        } else {
            deriveSecondary(primary)
        }
        val tertiary = if (tertiaryColorArgb != null) {
            Color(tertiaryColorArgb.toInt())
        } else {
            deriveTertiary(primary)
        }
        return if (dark) buildDarkScheme(primary, secondary, tertiary)
        else buildLightScheme(primary, secondary, tertiary)
    }
}

private fun deriveSecondary(primary: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(primary.toArgb(), hsl)
    hsl[0] = (hsl[0] + 30f) % 360f
    hsl[1] = (hsl[1] * 0.6f).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun deriveTertiary(primary: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(primary.toArgb(), hsl)
    hsl[0] = (hsl[0] + 120f) % 360f
    hsl[1] = (hsl[1] * 0.7f).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun tone(color: Color, lightness: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[2] = lightness.coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun toneWithSaturation(color: Color, lightness: Float, saturationRatio: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[1] = (hsl[1] * saturationRatio).coerceIn(0f, 1f)
    hsl[2] = lightness.coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun buildLightScheme(primary: Color, secondary: Color, tertiary: Color): ColorScheme {
    return lightColorScheme(
        primary = tone(primary, 0.40f),
        onPrimary = Color.White,
        primaryContainer = tone(primary, 0.90f),
        onPrimaryContainer = tone(primary, 0.30f),
        secondary = tone(secondary, 0.40f),
        onSecondary = Color.White,
        secondaryContainer = tone(secondary, 0.90f),
        onSecondaryContainer = tone(secondary, 0.30f),
        tertiary = tone(tertiary, 0.40f),
        onTertiary = Color.White,
        tertiaryContainer = tone(tertiary, 0.90f),
        onTertiaryContainer = tone(tertiary, 0.30f),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        background = toneWithSaturation(primary, 0.98f, 0.2f),
        onBackground = toneWithSaturation(primary, 0.10f, 0.1f),
        surface = toneWithSaturation(primary, 0.98f, 0.2f),
        onSurface = toneWithSaturation(primary, 0.10f, 0.1f),
        surfaceVariant = toneWithSaturation(primary, 0.90f, 0.3f),
        onSurfaceVariant = toneWithSaturation(primary, 0.30f, 0.2f),
        outline = toneWithSaturation(primary, 0.50f, 0.2f),
        outlineVariant = toneWithSaturation(primary, 0.80f, 0.2f),
        scrim = Color.Black,
        inverseSurface = toneWithSaturation(primary, 0.20f, 0.1f),
        inverseOnSurface = toneWithSaturation(primary, 0.95f, 0.1f),
        inversePrimary = tone(primary, 0.80f),
        surfaceDim = toneWithSaturation(primary, 0.87f, 0.2f),
        surfaceBright = toneWithSaturation(primary, 0.98f, 0.2f),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = toneWithSaturation(primary, 0.96f, 0.2f),
        surfaceContainer = toneWithSaturation(primary, 0.94f, 0.2f),
        surfaceContainerHigh = toneWithSaturation(primary, 0.92f, 0.2f),
        surfaceContainerHighest = toneWithSaturation(primary, 0.90f, 0.2f),
    )
}

private fun buildDarkScheme(primary: Color, secondary: Color, tertiary: Color): ColorScheme {
    return darkColorScheme(
        primary = tone(primary, 0.80f),
        onPrimary = tone(primary, 0.20f),
        primaryContainer = tone(primary, 0.30f),
        onPrimaryContainer = tone(primary, 0.90f),
        secondary = tone(secondary, 0.80f),
        onSecondary = tone(secondary, 0.20f),
        secondaryContainer = tone(secondary, 0.30f),
        onSecondaryContainer = tone(secondary, 0.90f),
        tertiary = tone(tertiary, 0.80f),
        onTertiary = tone(tertiary, 0.20f),
        tertiaryContainer = tone(tertiary, 0.30f),
        onTertiaryContainer = tone(tertiary, 0.90f),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = toneWithSaturation(primary, 0.06f, 0.2f),
        onBackground = toneWithSaturation(primary, 0.90f, 0.1f),
        surface = toneWithSaturation(primary, 0.06f, 0.2f),
        onSurface = toneWithSaturation(primary, 0.90f, 0.1f),
        surfaceVariant = toneWithSaturation(primary, 0.30f, 0.2f),
        onSurfaceVariant = toneWithSaturation(primary, 0.80f, 0.2f),
        outline = toneWithSaturation(primary, 0.60f, 0.2f),
        outlineVariant = toneWithSaturation(primary, 0.30f, 0.2f),
        scrim = Color.Black,
        inverseSurface = toneWithSaturation(primary, 0.90f, 0.1f),
        inverseOnSurface = toneWithSaturation(primary, 0.20f, 0.1f),
        inversePrimary = tone(primary, 0.40f),
        surfaceDim = toneWithSaturation(primary, 0.06f, 0.2f),
        surfaceBright = toneWithSaturation(primary, 0.24f, 0.2f),
        surfaceContainerLowest = toneWithSaturation(primary, 0.04f, 0.2f),
        surfaceContainerLow = toneWithSaturation(primary, 0.10f, 0.2f),
        surfaceContainer = toneWithSaturation(primary, 0.12f, 0.2f),
        surfaceContainerHigh = toneWithSaturation(primary, 0.17f, 0.2f),
        surfaceContainerHighest = toneWithSaturation(primary, 0.22f, 0.2f),
    )
}
