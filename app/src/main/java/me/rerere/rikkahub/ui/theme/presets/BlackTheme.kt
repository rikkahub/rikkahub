package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

val BlackThemePreset by lazy {
    PresetTheme(
        id = "black",
        name = {
            Text(stringResource(id = R.string.theme_name_black))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

private val primaryLight = Color(0xFF606060)
private val onPrimaryLight = Color(0xFFFFFCFC)
private val primaryContainerLight = Color(0xFFE6E6E6)
private val onPrimaryContainerLight = Color(0xFF424242)
private val secondaryLight = Color(0xFF424242)
private val onSecondaryLight = Color(0xFF6E6E6E)
private val secondaryContainerLight = Color(0xFFF3F3F3)
private val onSecondaryContainerLight = Color(0xFF575757)
private val tertiaryLight = Color(0xFF343434)
private val onTertiaryLight = Color(0xFFFCFCFC)
private val tertiaryContainerLight = Color(0xFF444444)
private val onTertiaryContainerLight = Color(0xFFB5B5B5)
private val errorLight = Color(0xFFDC2626)
private val onErrorLight = Color(0xFFFCFCFC)
private val errorContainerLight = Color(0xFFFEE2E2)
private val onErrorContainerLight = Color(0xFF991B1B)
private val backgroundLight = Color(0xFFFFFFFF)
private val onBackgroundLight = Color(0xFF252525)
private val surfaceLight = Color(0xFFFFFFFF)
private val onSurfaceLight = Color(0xFF252525)
private val surfaceVariantLight = Color(0xFFF7F7F7)
private val onSurfaceVariantLight = Color(0xFF444444)
private val outlineLight = Color(0xFFB5B5B5)
private val outlineVariantLight = Color(0xFFEBEBEB)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF343434)
private val inverseOnSurfaceLight = Color(0xFFEEEEF0)
private val inversePrimaryLight = Color(0xFFEBEBEB)
private val surfaceDimLight = Color(0xFFF7F7F7)
private val surfaceBrightLight = Color(0xFFFFFFFF)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF8F8F8)
private val surfaceContainerLight = Color(0xFFF7F7F7)
private val surfaceContainerHighLight = Color(0xFFEBEBEB)
private val surfaceContainerHighestLight = Color(0xFFE8E8E8)

private val primaryDark = Color(0xFFEBEBEB)
private val onPrimaryDark = Color(0xFF343434)
private val primaryContainerDark = Color(0xFF3B3B3B)
private val onPrimaryContainerDark = Color(0xFFB5B5B5)
private val secondaryDark = Color(0xFFB5B5B5)
private val onSecondaryDark = Color(0xFF343434)
private val secondaryContainerDark = Color(0xFF444444)
private val onSecondaryContainerDark = Color(0xFFFCFCFC)
private val tertiaryDark = Color(0xFFEBEBEB)
private val onTertiaryDark = Color(0xFF343434)
private val tertiaryContainerDark = Color(0xFF444444)
private val onTertiaryContainerDark = Color(0xFFB5B5B5)
private val errorDark = Color(0xFFEF4444)
private val onErrorDark = Color(0xFF7F1D1D)
private val errorContainerDark = Color(0xFF991B1B)
private val onErrorContainerDark = Color(0xFFFEE2E2)
private val backgroundDark = Color(0xFF1C1C1C)
private val onBackgroundDark = Color(0xFFFCFCFC)
private val surfaceDark = Color(0xFF1C1C1C)
private val onSurfaceDark = Color(0xFFFCFCFC)
private val surfaceVariantDark = Color(0xFF444444)
private val onSurfaceVariantDark = Color(0xFFB5B5B5)
private val outlineDark = Color(0xFF8E8E8E)
private val outlineVariantDark = Color(0xFF444444)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFFCFCFC)
private val inverseOnSurfaceDark = Color(0xFF343434)
private val inversePrimaryDark = Color(0xFF8E8E8E)
private val surfaceDimDark = Color(0xFF252525)
private val surfaceBrightDark = Color(0xFF444444)
private val surfaceContainerLowestDark = Color(0xFF1A1A1A)
private val surfaceContainerLowDark = Color(0xFF252525)
private val surfaceContainerDark = Color(0xFF2A2A2A)
private val surfaceContainerHighDark = Color(0xFF343434)
private val surfaceContainerHighestDark = Color(0xFF3F3F3F)

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
