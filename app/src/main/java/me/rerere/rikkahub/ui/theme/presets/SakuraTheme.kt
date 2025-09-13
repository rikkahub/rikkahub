package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

val SakuraThemePreset by lazy {
    PresetTheme(
        id = "sakura",
        name = {
            Text(stringResource(id = R.string.theme_name_sakura))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

//region Sakura Theme Colors
private val primaryLight = Color(0xFF8E4955)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFFFD9DD)
private val onPrimaryContainerLight = Color(0xFF72333E)
private val secondaryLight = Color(0xFF76565A)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFFFD9DD)
private val onSecondaryContainerLight = Color(0xFF5C3F43)
private val tertiaryLight = Color(0xFF785831)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFFFDDB8)
private val onTertiaryContainerLight = Color(0xFF5E411C)
private val errorLight = Color(0xFFBA1A1A)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFFFDAD6)
private val onErrorContainerLight = Color(0xFF93000A)
private val backgroundLight = Color(0xFFFFF8F7)
private val onBackgroundLight = Color(0xFF22191A)
private val surfaceLight = Color(0xFFFFF8F7)
private val onSurfaceLight = Color(0xFF22191A)
private val surfaceVariantLight = Color(0xFFF3DDDF)
private val onSurfaceVariantLight = Color(0xFF524345)
private val outlineLight = Color(0xFF847374)
private val outlineVariantLight = Color(0xFFD7C1C3)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF382E2F)
private val inverseOnSurfaceLight = Color(0xFFFEEDED)
private val inversePrimaryLight = Color(0xFFFFB2BC)
private val surfaceDimLight = Color(0xFFE7D6D7)
private val surfaceBrightLight = Color(0xFFFFF8F7)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFFFF0F1)
private val surfaceContainerLight = Color(0xFFFBEAEB)
private val surfaceContainerHighLight = Color(0xFFF6E4E5)
private val surfaceContainerHighestLight = Color(0xFFF0DEDF)

private val primaryDark = Color(0xFFFFB2BC)
private val onPrimaryDark = Color(0xFF561D28)
private val primaryContainerDark = Color(0xFF72333E)
private val onPrimaryContainerDark = Color(0xFFFFD9DD)
private val secondaryDark = Color(0xFFE5BDC1)
private val onSecondaryDark = Color(0xFF43292D)
private val secondaryContainerDark = Color(0xFF5C3F43)
private val onSecondaryContainerDark = Color(0xFFFFD9DD)
private val tertiaryDark = Color(0xFFEABF8F)
private val onTertiaryDark = Color(0xFF452B07)
private val tertiaryContainerDark = Color(0xFF5E411C)
private val onTertiaryContainerDark = Color(0xFFFFDDB8)
private val errorDark = Color(0xFFFFB4AB)
private val onErrorDark = Color(0xFF690005)
private val errorContainerDark = Color(0xFF93000A)
private val onErrorContainerDark = Color(0xFFFFDAD6)
private val backgroundDark = Color(0xFF1A1112)
private val onBackgroundDark = Color(0xFFF0DEDF)
private val surfaceDark = Color(0xFF1A1112)
private val onSurfaceDark = Color(0xFFF0DEDF)
private val surfaceVariantDark = Color(0xFF524345)
private val onSurfaceVariantDark = Color(0xFFD7C1C3)
private val outlineDark = Color(0xFF9F8C8E)
private val outlineVariantDark = Color(0xFF524345)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFF0DEDF)
private val inverseOnSurfaceDark = Color(0xFF382E2F)
private val inversePrimaryDark = Color(0xFF8E4955)
private val surfaceDimDark = Color(0xFF1A1112)
private val surfaceBrightDark = Color(0xFF413738)
private val surfaceContainerLowestDark = Color(0xFF140C0D)
private val surfaceContainerLowDark = Color(0xFF22191A)
private val surfaceContainerDark = Color(0xFF261D1E)
private val surfaceContainerHighDark = Color(0xFF312828)
private val surfaceContainerHighestDark = Color(0xFF3D3233)

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
