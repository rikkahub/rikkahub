package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

val WarmThemePreset by lazy {
    PresetTheme(
        id = "warm",
        name = {
            Text(stringResource(R.string.theme_name_warm))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

// Warm earth-tone palette: moonstone/parchment canvas, slate-blue ink, near-black
// primary (black CTAs / send button), tidal-blue + terracotta accents.
private val primaryLight = Color(0xFF13110F)
private val onPrimaryLight = Color(0xFFF3F2F0)
private val primaryContainerLight = Color(0xFFE0D7CE)
private val onPrimaryContainerLight = Color(0xFF3A332C)
private val secondaryLight = Color(0xFF4B607C)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFD6DEE8)
private val onSecondaryContainerLight = Color(0xFF2A3543)
private val tertiaryLight = Color(0xFF844F3B)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFF0D9CE)
private val onTertiaryContainerLight = Color(0xFF4A2A1E)
private val errorLight = Color(0xFF8F3222)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFF5D9D1)
private val onErrorContainerLight = Color(0xFF5C1A10)
private val backgroundLight = Color(0xFFEBE7E4)
private val onBackgroundLight = Color(0xFF252F3D)
private val surfaceLight = Color(0xFFEBE7E4)
private val onSurfaceLight = Color(0xFF252F3D)
private val surfaceVariantLight = Color(0xFFE2DBD2)
private val onSurfaceVariantLight = Color(0xFF5C5752)
private val outlineLight = Color(0xFF8B847D)
private val outlineVariantLight = Color(0xFFD6CEC6)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF2F3742)
private val inverseOnSurfaceLight = Color(0xFFF0EEEB)
private val inversePrimaryLight = Color(0xFFC9BFB4)
private val surfaceDimLight = Color(0xFFD8D2CB)
private val surfaceBrightLight = Color(0xFFF7F4F1)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF5F1ED)
private val surfaceContainerLight = Color(0xFFEFEAE5)
private val surfaceContainerHighLight = Color(0xFFE9E3DD)
private val surfaceContainerHighestLight = Color(0xFFE3DCD5)

private val primaryDark = Color(0xFFF3F2F0)
private val onPrimaryDark = Color(0xFF1A1815)
private val primaryContainerDark = Color(0xFF3A332C)
private val onPrimaryContainerDark = Color(0xFFE5DDD5)
private val secondaryDark = Color(0xFF6A9FCC)
private val onSecondaryDark = Color(0xFF10243A)
private val secondaryContainerDark = Color(0xFF2A3A4C)
private val onSecondaryContainerDark = Color(0xFFC5DAEC)
private val tertiaryDark = Color(0xFFD48D73)
private val onTertiaryDark = Color(0xFF3A1C12)
private val tertiaryContainerDark = Color(0xFF5A3526)
private val onTertiaryContainerDark = Color(0xFFF0D9CE)
private val errorDark = Color(0xFFE8704F)
private val onErrorDark = Color(0xFF4A1409)
private val errorContainerDark = Color(0xFF6E2418)
private val onErrorContainerDark = Color(0xFFF5D9D1)
private val backgroundDark = Color(0xFF141210)
private val onBackgroundDark = Color(0xFFEBE7E4)
private val surfaceDark = Color(0xFF141210)
private val onSurfaceDark = Color(0xFFEBE7E4)
private val surfaceVariantDark = Color(0xFF3A352F)
private val onSurfaceVariantDark = Color(0xFFB3ABA2)
private val outlineDark = Color(0xFF8B847D)
private val outlineVariantDark = Color(0xFF3A352F)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFEBE7E4)
private val inverseOnSurfaceDark = Color(0xFF2F2B26)
private val inversePrimaryDark = Color(0xFF13110F)
private val surfaceDimDark = Color(0xFF141210)
private val surfaceBrightDark = Color(0xFF38332D)
private val surfaceContainerLowestDark = Color(0xFF0E0D0B)
private val surfaceContainerLowDark = Color(0xFF1B1916)
private val surfaceContainerDark = Color(0xFF1F1D1A)
private val surfaceContainerHighDark = Color(0xFF29251F)
private val surfaceContainerHighestDark = Color(0xFF332E28)

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
