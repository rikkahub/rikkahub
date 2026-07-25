package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

/**
 * 极简中性风格：纯白/近黑底色，中性灰的表面层级，细淡边框，
 * 低饱和蓝作为强调色，少量琥珀作为点缀色。
 */
val MinimalThemePreset by lazy {
    PresetTheme(
        id = "minimal",
        name = {
            Text(stringResource(id = R.string.theme_name_minimal))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

private val primaryLight = Color(0xFF2563EB)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFE7F0FE)
private val onPrimaryContainerLight = Color(0xFF1B4ACB)
private val secondaryLight = Color(0xFF565A61)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFEFF0F2)
private val onSecondaryContainerLight = Color(0xFF3B3E44)
private val tertiaryLight = Color(0xFF8A6A16)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFFCEFCE)
private val onTertiaryContainerLight = Color(0xFF6A5008)
private val errorLight = Color(0xFFD93A32)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFFDE7E5)
private val onErrorContainerLight = Color(0xFFA31710)
private val backgroundLight = Color(0xFFFFFFFF)
private val onBackgroundLight = Color(0xFF16181D)
private val surfaceLight = Color(0xFFFFFFFF)
private val onSurfaceLight = Color(0xFF16181D)
private val surfaceVariantLight = Color(0xFFF1F2F4)
private val onSurfaceVariantLight = Color(0xFF6A6E75)
private val outlineLight = Color(0xFFC7CACF)
private val outlineVariantLight = Color(0xFFE8E9EC)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF2A2C31)
private val inverseOnSurfaceLight = Color(0xFFF3F4F6)
private val inversePrimaryLight = Color(0xFFAAC7FF)
private val surfaceDimLight = Color(0xFFECEDEF)
private val surfaceBrightLight = Color(0xFFFFFFFF)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFFAFAFB)
private val surfaceContainerLight = Color(0xFFF6F7F8)
private val surfaceContainerHighLight = Color(0xFFF1F2F4)
private val surfaceContainerHighestLight = Color(0xFFEAEBEE)

private val primaryDark = Color(0xFF8CB0FF)
private val onPrimaryDark = Color(0xFF082C6E)
private val primaryContainerDark = Color(0xFF1D3E7F)
private val onPrimaryContainerDark = Color(0xFFD9E4FF)
private val secondaryDark = Color(0xFFC3C6CC)
private val onSecondaryDark = Color(0xFF2B2E33)
private val secondaryContainerDark = Color(0xFF3A3D43)
private val onSecondaryContainerDark = Color(0xFFE2E4E8)
private val tertiaryDark = Color(0xFFE8CB84)
private val onTertiaryDark = Color(0xFF3F2E00)
private val tertiaryContainerDark = Color(0xFF5B4400)
private val onTertiaryContainerDark = Color(0xFFFFE6AA)
private val errorDark = Color(0xFFFFB4AB)
private val onErrorDark = Color(0xFF690005)
private val errorContainerDark = Color(0xFF93000A)
private val onErrorContainerDark = Color(0xFFFFDAD6)
private val backgroundDark = Color(0xFF0F1012)
private val onBackgroundDark = Color(0xFFE6E7EA)
private val surfaceDark = Color(0xFF0F1012)
private val onSurfaceDark = Color(0xFFE6E7EA)
private val surfaceVariantDark = Color(0xFF2B2D31)
private val onSurfaceVariantDark = Color(0xFFA0A4AB)
private val outlineDark = Color(0xFF4C4F55)
private val outlineVariantDark = Color(0xFF2E3035)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFE6E7EA)
private val inverseOnSurfaceDark = Color(0xFF2A2C31)
private val inversePrimaryDark = Color(0xFF2563EB)
private val surfaceDimDark = Color(0xFF0F1012)
private val surfaceBrightDark = Color(0xFF35373C)
private val surfaceContainerLowestDark = Color(0xFF0A0B0C)
private val surfaceContainerLowDark = Color(0xFF151619)
private val surfaceContainerDark = Color(0xFF1A1B1E)
private val surfaceContainerHighDark = Color(0xFF222327)
private val surfaceContainerHighestDark = Color(0xFF2A2C30)

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
