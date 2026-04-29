package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

val CyberpunkThemePreset by lazy {
    PresetTheme(
        id = "cyberpunk",
        name = {
            Text(stringResource(id = R.string.theme_name_cyberpunk))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

// === 硬朗赛博朋克工业色系 ===
val NeonCyan = Color(0xFF00FFFF)
val NeonPink = Color(0xFFFF007F)
val NeonPurple = Color(0xFF8B5CF6)
val NeonGreen = Color(0xFF39FF14)
val NeonYellow = Color(0xFFFFD700)
val DeepBlack = Color(0xFF0A0A0F)
val PanelBlack = Color(0xFF111118)
val GridLine = Color(0xFF1A1A24)
val TextPrimary = Color(0xFFE0E0E8)
val TextSecondary = Color(0xFF6B6B80)
val AlertOrange = Color(0xFFFF4400)
val SteelGray = Color(0xFF2A2A35)
val WarningRed = Color(0xFFFF2222)

// Light scheme (fallback)
private val primaryLight = NeonCyan
private val onPrimaryLight = DeepBlack
private val primaryContainerLight = NeonCyan.copy(alpha = 0.15f)
private val onPrimaryContainerLight = NeonCyan
private val secondaryLight = NeonPink
private val onSecondaryLight = DeepBlack
private val secondaryContainerLight = NeonPink.copy(alpha = 0.15f)
private val onSecondaryContainerLight = NeonPink
private val tertiaryLight = NeonPurple
private val onTertiaryLight = DeepBlack
private val tertiaryContainerLight = NeonPurple.copy(alpha = 0.15f)
private val onTertiaryContainerLight = NeonPurple
private val errorLight = WarningRed
private val onErrorLight = Color.White
private val errorContainerLight = WarningRed.copy(alpha = 0.15f)
private val onErrorContainerLight = WarningRed
private val backgroundLight = Color(0xFFF5F5F8)
private val onBackgroundLight = DeepBlack
private val surfaceLight = Color(0xFFFFFFFF)
private val onSurfaceLight = DeepBlack
private val surfaceVariantLight = Color(0xFFE8E8EC)
private val onSurfaceVariantLight = Color(0xFF44444C)
private val outlineLight = SteelGray
private val outlineVariantLight = Color(0xFFCCCCD0)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = DeepBlack
private val inverseOnSurfaceLight = TextPrimary
private val inversePrimaryLight = NeonCyan
private val surfaceDimLight = Color(0xFFE0E0E4)
private val surfaceBrightLight = Color(0xFFFFFFFF)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF8F8FC)
private val surfaceContainerLight = Color(0xFFF0F0F4)
private val surfaceContainerHighLight = Color(0xFFE8E8EC)
private val surfaceContainerHighestLight = Color(0xFFE0E0E4)

// Dark scheme - 硬朗赛博朋克
private val primaryDark = NeonCyan
private val onPrimaryDark = DeepBlack
private val primaryContainerDark = NeonCyan.copy(alpha = 0.1f)
private val onPrimaryContainerDark = NeonCyan
private val secondaryDark = NeonPink
private val onSecondaryDark = DeepBlack
private val secondaryContainerDark = NeonPink.copy(alpha = 0.1f)
private val onSecondaryContainerDark = NeonPink
private val tertiaryDark = NeonPurple
private val onTertiaryDark = DeepBlack
private val tertiaryContainerDark = NeonPurple.copy(alpha = 0.1f)
private val onTertiaryContainerDark = NeonPurple
private val errorDark = WarningRed
private val onErrorDark = Color.White
private val errorContainerDark = WarningRed.copy(alpha = 0.1f)
private val onErrorContainerDark = WarningRed
private val backgroundDark = DeepBlack
private val onBackgroundDark = TextPrimary
private val surfaceDark = PanelBlack
private val onSurfaceDark = TextPrimary
private val surfaceVariantDark = GridLine
private val onSurfaceVariantDark = TextSecondary
private val outlineDark = SteelGray
private val outlineVariantDark = GridLine
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = TextPrimary
private val inverseOnSurfaceDark = DeepBlack
private val inversePrimaryDark = NeonCyan
private val surfaceDimDark = Color(0xFF08080C)
private val surfaceBrightDark = Color(0xFF181820)
private val surfaceContainerLowestDark = Color(0xFF050508)
private val surfaceContainerLowDark = Color(0xFF0A0A0F)
private val surfaceContainerDark = PanelBlack
private val surfaceContainerHighDark = Color(0xFF15151C)
private val surfaceContainerHighestDark = Color(0xFF1A1A22)

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)
