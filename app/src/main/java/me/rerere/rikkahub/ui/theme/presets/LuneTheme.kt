package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

val LuneThemePreset by lazy {
    PresetTheme(
        id = "lune",
        name = {
            Text(stringResource(id = R.string.theme_name_lune))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

private val primaryLight = Color(0xFF4B6CB2)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFD8E4FF)
private val onPrimaryContainerLight = Color(0xFF1D3568)
private val secondaryLight = Color(0xFF6F7EA4)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFE3E9F8)
private val onSecondaryContainerLight = Color(0xFF283657)
private val tertiaryLight = Color(0xFF9B7C33)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFFFE8B0)
private val onTertiaryContainerLight = Color(0xFF3B2B00)
private val errorLight = Color(0xFFB3261E)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFF9DEDC)
private val onErrorContainerLight = Color(0xFF410E0B)
private val backgroundLight = Color(0xFFF7FAFF)
private val onBackgroundLight = Color(0xFF182033)
private val surfaceLight = Color(0xFFF7FAFF)
private val onSurfaceLight = Color(0xFF182033)
private val surfaceVariantLight = Color(0xFFDDE4F1)
private val onSurfaceVariantLight = Color(0xFF414A5C)
private val outlineLight = Color(0xFF717A8C)
private val outlineVariantLight = Color(0xFFC1C8D6)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF2D3240)
private val inverseOnSurfaceLight = Color(0xFFF1F4FA)
private val inversePrimaryLight = Color(0xFFB7CCFF)
private val surfaceDimLight = Color(0xFFD8DEE8)
private val surfaceBrightLight = Color(0xFFF7FAFF)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF1F5FD)
private val surfaceContainerLight = Color(0xFFEBF0F8)
private val surfaceContainerHighLight = Color(0xFFE4EAF4)
private val surfaceContainerHighestLight = Color(0xFFDDE3EE)

private val primaryDark = Color(0xFFA8C8FF)
private val onPrimaryDark = Color(0xFF082352)
private val primaryContainerDark = Color(0xFF26457E)
private val onPrimaryContainerDark = Color(0xFFD8E5FF)
private val secondaryDark = Color(0xFFBEC8E6)
private val onSecondaryDark = Color(0xFF27324C)
private val secondaryContainerDark = Color(0xFF3E4964)
private val onSecondaryContainerDark = Color(0xFFDDE4F8)
private val tertiaryDark = Color(0xFFF2D8A7)
private val onTertiaryDark = Color(0xFF402D00)
private val tertiaryContainerDark = Color(0xFF5A4311)
private val onTertiaryContainerDark = Color(0xFFFFE8BE)
private val errorDark = Color(0xFFF2B8B5)
private val onErrorDark = Color(0xFF601410)
private val errorContainerDark = Color(0xFF8C1D18)
private val onErrorContainerDark = Color(0xFFF9DEDC)
private val backgroundDark = Color(0xFF0A0D14)
private val onBackgroundDark = Color(0xFFE7EDF7)
private val surfaceDark = Color(0xFF0A0D14)
private val onSurfaceDark = Color(0xFFE7EDF7)
private val surfaceVariantDark = Color(0xFF434957)
private val onSurfaceVariantDark = Color(0xFFC3C9D8)
private val outlineDark = Color(0xFF8D93A1)
private val outlineVariantDark = Color(0xFF434957)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFE7EDF7)
private val inverseOnSurfaceDark = Color(0xFF171C28)
private val inversePrimaryDark = Color(0xFF4A6CB5)
private val surfaceDimDark = Color(0xFF0A0D14)
private val surfaceBrightDark = Color(0xFF2B3243)
private val surfaceContainerLowestDark = Color(0xFF06080E)
private val surfaceContainerLowDark = Color(0xFF111622)
private val surfaceContainerDark = Color(0xFF141B29)
private val surfaceContainerHighDark = Color(0xFF182133)
private val surfaceContainerHighestDark = Color(0xFF212A3D)

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
