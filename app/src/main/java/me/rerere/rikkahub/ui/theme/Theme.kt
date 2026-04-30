package me.rerere.rikkahub.ui.theme

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }

private val AMOLED_DARK_BACKGROUND = Color(0xFF000000)

// === 赛博朋克硬编码颜色 - 纯黑底 + RGB纯色 ===
private val PureRed = Color(0xFFFF0000)
private val PureGreen = Color(0xFF00FF00)
private val PureBlue = Color(0xFF0000FF)
private val PureBlack = Color(0xFF000000)
private val PureWhite = Color(0xFFFFFFFF)
private val DimWhite = Color(0xFF888888)

// 暗色模式 - 纯黑背景，纯白线条，RGB纯色点缀
private val CyberDarkScheme = darkColorScheme(
    primary = PureRed,
    onPrimary = PureBlack,
    primaryContainer = Color(0xFFFF0000).copy(alpha = 0.15f),
    onPrimaryContainer = PureRed,
    secondary = PureGreen,
    onSecondary = PureBlack,
    secondaryContainer = Color(0xFF00FF00).copy(alpha = 0.15f),
    onSecondaryContainer = PureGreen,
    tertiary = PureBlue,
    onTertiary = PureBlack,
    tertiaryContainer = Color(0xFF0000FF).copy(alpha = 0.15f),
    onTertiaryContainer = PureBlue,
    error = PureRed,
    onError = PureBlack,
    errorContainer = Color(0xFFFF0000).copy(alpha = 0.15f),
    onErrorContainer = PureRed,
    background = PureBlack,
    onBackground = PureWhite,
    surface = PureBlack,
    onSurface = PureWhite,
    surfaceVariant = Color(0xFF111111),
    onSurfaceVariant = DimWhite,
    outline = PureWhite,
    outlineVariant = Color(0xFF333333),
    scrim = PureBlack,
    inverseSurface = PureWhite,
    inverseOnSurface = PureBlack,
    inversePrimary = PureRed,
    surfaceDim = PureBlack,
    surfaceBright = Color(0xFF111111),
    surfaceContainerLowest = PureBlack,
    surfaceContainerLow = PureBlack,
    surfaceContainer = PureBlack,
    surfaceContainerHigh = Color(0xFF111111),
    surfaceContainerHighest = Color(0xFF222222),
)

// 亮色模式 - 同样使用纯黑底（赛博朋克无亮色）
private val CyberLightScheme = lightColorScheme(
    primary = PureRed,
    onPrimary = PureBlack,
    primaryContainer = Color(0xFFFF0000).copy(alpha = 0.15f),
    onPrimaryContainer = PureRed,
    secondary = PureGreen,
    onSecondary = PureBlack,
    secondaryContainer = Color(0xFF00FF00).copy(alpha = 0.15f),
    onSecondaryContainer = PureGreen,
    tertiary = PureBlue,
    onTertiary = PureBlack,
    tertiaryContainer = Color(0xFF0000FF).copy(alpha = 0.15f),
    onTertiaryContainer = PureBlue,
    error = PureRed,
    onError = PureBlack,
    errorContainer = Color(0xFFFF0000).copy(alpha = 0.15f),
    onErrorContainer = PureRed,
    background = PureBlack,
    onBackground = PureWhite,
    surface = PureBlack,
    onSurface = PureWhite,
    surfaceVariant = Color(0xFF111111),
    onSurfaceVariant = DimWhite,
    outline = PureWhite,
    outlineVariant = Color(0xFF333333),
    scrim = PureBlack,
    inverseSurface = PureWhite,
    inverseOnSurface = PureBlack,
    inversePrimary = PureRed,
    surfaceDim = PureBlack,
    surfaceBright = Color(0xFF111111),
    surfaceContainerLowest = PureBlack,
    surfaceContainerLow = PureBlack,
    surfaceContainer = PureBlack,
    surfaceContainerHigh = Color(0xFF111111),
    surfaceContainerHighest = Color(0xFF222222),
)

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun RikkahubTheme(
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()

    val colorMode by rememberColorMode()
    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val amoledDarkMode by rememberAmoledDarkMode()

    // 强制使用赛博朋克主题，不走任何中间层
    val colorScheme = if (darkTheme) CyberDarkScheme else CyberLightScheme
    val colorSchemeConverted = remember(darkTheme, amoledDarkMode, colorScheme) {
        if (darkTheme && amoledDarkMode) {
            colorScheme.copy(
                background = AMOLED_DARK_BACKGROUND,
                surface = AMOLED_DARK_BACKGROUND,
            )
        } else {
            colorScheme
        }
    }
    val extendColors = if (darkTheme) ExtendDarkColors else ExtendLightColors

    // 调试日志：确认主题加载
    Log.d("RikkahubTheme", "Theme loaded: dark=$darkTheme, amoled=$amoledDarkMode, " +
            "bg=${colorSchemeConverted.background}, primary=${colorSchemeConverted.primary}")

    // 更新状态栏图标颜色
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalExtendColors provides extendColors,
        LocalOverscrollFactory provides null
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorSchemeConverted,
            typography = Typography,
            shapes = CyberpunkShapes,
            content = content,
            motionScheme = MotionScheme.expressive()
        )
    }
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current
