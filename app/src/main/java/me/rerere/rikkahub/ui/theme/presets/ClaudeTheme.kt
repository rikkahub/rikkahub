package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

/**
 * Claude 风格：象牙白/米色的暖中性底色，赤陶橙作为强调色，
 * 牛皮棕与马尼拉黄作为点缀色。
 */
val ClaudeThemePreset by lazy {
    PresetTheme(
        id = "claude",
        name = {
            Text(stringResource(id = R.string.theme_name_claude))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

private val primaryLight = Color(0xFFC96442)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFF7E3D9)
private val onPrimaryContainerLight = Color(0xFF8A3E23)
private val secondaryLight = Color(0xFF6F675C)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFE9E6DC)
private val onSecondaryContainerLight = Color(0xFF3D3929)
private val tertiaryLight = Color(0xFF9A6C3E)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFEBDBBC)
private val onTertiaryContainerLight = Color(0xFF5A4526)
private val errorLight = Color(0xFFB03D2E)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFF8E1DC)
private val onErrorContainerLight = Color(0xFF7E2418)
private val backgroundLight = Color(0xFFFAF9F5)
private val onBackgroundLight = Color(0xFF262624)
private val surfaceLight = Color(0xFFFAF9F5)
private val onSurfaceLight = Color(0xFF262624)
private val surfaceVariantLight = Color(0xFFEDEAE0)
private val onSurfaceVariantLight = Color(0xFF6F675C)
private val outlineLight = Color(0xFFBDB7A9)
private val outlineVariantLight = Color(0xFFE5E1D6)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF33312C)
private val inverseOnSurfaceLight = Color(0xFFF7F5EF)
private val inversePrimaryLight = Color(0xFFFFB59B)
private val surfaceDimLight = Color(0xFFE7E3D8)
private val surfaceBrightLight = Color(0xFFFFFFFF)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF7F5EF)
private val surfaceContainerLight = Color(0xFFF2F0E8)
private val surfaceContainerHighLight = Color(0xFFEDEAE0)
private val surfaceContainerHighestLight = Color(0xFFE7E3D8)

private val primaryDark = Color(0xFFE4906E)
private val onPrimaryDark = Color(0xFF4A1B0B)
private val primaryContainerDark = Color(0xFF7A3620)
private val onPrimaryContainerDark = Color(0xFFFFDBCE)
private val secondaryDark = Color(0xFFD3CCBF)
private val onSecondaryDark = Color(0xFF383429)
private val secondaryContainerDark = Color(0xFF4A453A)
private val onSecondaryContainerDark = Color(0xFFEDE7DA)
private val tertiaryDark = Color(0xFFE0BE8C)
private val onTertiaryDark = Color(0xFF422C0C)
private val tertiaryContainerDark = Color(0xFF5C4525)
private val onTertiaryContainerDark = Color(0xFFFFDDB0)
private val errorDark = Color(0xFFFFB4A6)
private val onErrorDark = Color(0xFF5F1508)
private val errorContainerDark = Color(0xFF82271A)
private val onErrorContainerDark = Color(0xFFFFDAD3)
private val backgroundDark = Color(0xFF1F1E1D)
private val onBackgroundDark = Color(0xFFEDEAE3)
private val surfaceDark = Color(0xFF1F1E1D)
private val onSurfaceDark = Color(0xFFEDEAE3)
private val surfaceVariantDark = Color(0xFF3A372F)
private val onSurfaceVariantDark = Color(0xFFCBC5B8)
private val outlineDark = Color(0xFF5A564C)
private val outlineVariantDark = Color(0xFF3A372F)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFEDEAE3)
private val inverseOnSurfaceDark = Color(0xFF33312C)
private val inversePrimaryDark = Color(0xFFC96442)
private val surfaceDimDark = Color(0xFF1F1E1D)
private val surfaceBrightDark = Color(0xFF44423E)
private val surfaceContainerLowestDark = Color(0xFF171614)
private val surfaceContainerLowDark = Color(0xFF262624)
private val surfaceContainerDark = Color(0xFF2B2A27)
private val surfaceContainerHighDark = Color(0xFF343230)
private val surfaceContainerHighestDark = Color(0xFF3E3C37)

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
