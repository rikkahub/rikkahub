package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

// Tokyo Night (https://github.com/enkia/tokyo-night-vscode-theme). The dark scheme is the canonical
// Tokyo Night "Night" variant — deep navy-indigo base (#1a1b26, never pure black) with the signature
// blue/purple/cyan accents — which fits the 2026 "Dark Mode 2.0 / Electric Twilight" trend (hue-shifted
// dark surfaces, single saturated accents, OLED-friendly). The light scheme is "Tokyo Night Day".
val TokyoNightThemePreset by lazy {
    PresetTheme(
        id = "tokyo_night",
        name = {
            Text(stringResource(id = R.string.theme_name_tokyo_night))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

// --- Tokyo Night Day (light) ---
// Darkened from Tokyo Night Day's #2E7DE9 so white onPrimary clears WCAG AA 4.5:1 (button text).
private val primaryLight = Color(0xFF1F6AD9)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFD6E3FF)
private val onPrimaryContainerLight = Color(0xFF001B3D)
// Darkened from Tokyo Night Day's #9854F1 so white onSecondary clears WCAG AA 4.5:1.
private val secondaryLight = Color(0xFF8546E0)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFECDDFF)
private val onSecondaryContainerLight = Color(0xFF2A004F)
private val tertiaryLight = Color(0xFF007197)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFC4E7FF)
private val onTertiaryContainerLight = Color(0xFF001E2C)
private val errorLight = Color(0xFFC4003E)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFFFD9DF)
private val onErrorContainerLight = Color(0xFF40000F)
private val backgroundLight = Color(0xFFE1E2E7)
private val onBackgroundLight = Color(0xFF2A2E3F)
private val surfaceLight = Color(0xFFE1E2E7)
private val onSurfaceLight = Color(0xFF2A2E3F)
private val surfaceVariantLight = Color(0xFFD3D7E3)
private val onSurfaceVariantLight = Color(0xFF4A4F66)
private val outlineLight = Color(0xFF7B819E)
private val outlineVariantLight = Color(0xFFC4C8DA)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF2A2E3F)
private val inverseOnSurfaceLight = Color(0xFFE9EAF0)
private val inversePrimaryLight = Color(0xFFA8C7FF)
private val surfaceDimLight = Color(0xFFC5C6CC)
private val surfaceBrightLight = Color(0xFFE1E2E7)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFEBECF1)
private val surfaceContainerLight = Color(0xFFE5E6EC)
private val surfaceContainerHighLight = Color(0xFFDFE0E6)
private val surfaceContainerHighestLight = Color(0xFFD9DAE1)

// --- Tokyo Night (dark, the canonical theme) ---
private val primaryDark = Color(0xFF7AA2F7)
private val onPrimaryDark = Color(0xFF16161E)
private val primaryContainerDark = Color(0xFF3D59A1)
private val onPrimaryContainerDark = Color(0xFFD5E0FF)
private val secondaryDark = Color(0xFFBB9AF7)
private val onSecondaryDark = Color(0xFF16161E)
private val secondaryContainerDark = Color(0xFF44355F)
private val onSecondaryContainerDark = Color(0xFFECDCFF)
private val tertiaryDark = Color(0xFF7DCFFF)
private val onTertiaryDark = Color(0xFF16161E)
private val tertiaryContainerDark = Color(0xFF2B5063)
private val onTertiaryContainerDark = Color(0xFFC8EEFF)
private val errorDark = Color(0xFFF7768E)
private val onErrorDark = Color(0xFF16161E)
private val errorContainerDark = Color(0xFF6B2737)
private val onErrorContainerDark = Color(0xFFFFD9DF)
private val backgroundDark = Color(0xFF1A1B26)
private val onBackgroundDark = Color(0xFFC0CAF5)
private val surfaceDark = Color(0xFF1A1B26)
private val onSurfaceDark = Color(0xFFC0CAF5)
private val surfaceVariantDark = Color(0xFF292E42)
private val onSurfaceVariantDark = Color(0xFFA9B1D6)
private val outlineDark = Color(0xFF565F89)
private val outlineVariantDark = Color(0xFF3B4261)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFC0CAF5)
private val inverseOnSurfaceDark = Color(0xFF1A1B26)
private val inversePrimaryDark = Color(0xFF34548A)
private val surfaceDimDark = Color(0xFF16161E)
private val surfaceBrightDark = Color(0xFF292E42)
private val surfaceContainerLowestDark = Color(0xFF16161E)
private val surfaceContainerLowDark = Color(0xFF1A1B26)
private val surfaceContainerDark = Color(0xFF1F2335)
private val surfaceContainerHighDark = Color(0xFF24283B)
private val surfaceContainerHighestDark = Color(0xFF292E42)

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
