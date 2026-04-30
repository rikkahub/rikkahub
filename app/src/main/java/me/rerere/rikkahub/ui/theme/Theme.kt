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

// === 赛博朋克硬编码颜色 ===
private val CyberCyan = Color(0xFF00FFFF)
private val CyberPink = Color(0xFFFF007F)
private val CyberPurple = Color(0xFF8B5CF6)
private val CyberGreen = Color(0xFF39FF14)
private val CyberBlack = Color(0xFF0A0A0F)
private val CyberPanel = Color(0xFF111118)
private val CyberGrid = Color(0xFF1A1A24)
private val CyberText = Color(0xFFE0E0E8)
private val CyberTextDim = Color(0xFF6B6B80)
private val CyberSteel = Color(0xFF2A2A35)
private val CyberRed = Color(0xFFFF2222)

// 暗色模式 - 纯黑底霓虹色
private val CyberDarkScheme = darkColorScheme(
    primary = CyberCyan,
    onPrimary = CyberBlack,
    primaryContainer = Color(0xFF00FFFF).copy(alpha = 0.12f),
    onPrimaryContainer = CyberCyan,
    secondary = CyberPink,
    onSecondary = CyberBlack,
    secondaryContainer = Color(0xFFFF007F).copy(alpha = 0.12f),
    onSecondaryContainer = CyberPink,
    tertiary = CyberPurple,
    onTertiary = CyberBlack,
    tertiaryContainer = Color(0xFF8B5CF6).copy(alpha = 0.12f),
    onTertiaryContainer = CyberPurple,
    error = CyberRed,
    onError = Color.White,
    errorContainer = Color(0xFFFF2222).copy(alpha = 0.12f),
    onErrorContainer = CyberRed,
    background = CyberBlack,
    onBackground = CyberText,
    surface = CyberPanel,
    onSurface = CyberText,
    surfaceVariant = CyberGrid,
    onSurfaceVariant = CyberTextDim,
    outline = CyberSteel,
    outlineVariant = CyberGrid,
    scrim = Color(0xFF000000),
    inverseSurface = CyberText,
    inverseOnSurface = CyberBlack,
    inversePrimary = CyberCyan,
    surfaceDim = Color(0xFF08080C),
    surfaceBright = Color(0xFF181820),
    surfaceContainerLowest = Color(0xFF050508),
    surfaceContainerLow = Color(0xFF0A0A0F),
    surfaceContainer = CyberPanel,
    surfaceContainerHigh = Color(0xFF15151C),
    surfaceContainerHighest = Color(0xFF1A1A22),
)

// 亮色模式 - 深色底（赛博朋克没有真正的亮色）
private val CyberLightScheme = lightColorScheme(
    primary = CyberCyan,
    onPrimary = CyberBlack,
    primaryContainer = Color(0xFF00FFFF).copy(alpha = 0.15f),
    onPrimaryContainer = Color(0xFF006666),
    secondary = CyberPink,
    onSecondary = CyberBlack,
    secondaryContainer = Color(0xFFFF007F).copy(alpha = 0.15f),
    onSecondaryContainer = Color(0xFF99004D),
    tertiary = CyberPurple,
    onTertiary = CyberBlack,
    tertiaryContainer = Color(0xFF8B5CF6).copy(alpha = 0.15f),
    onTertiaryContainer = Color(0xFF5B3FA6),
    error = CyberRed,
    onError = Color.White,
    errorContainer = Color(0xFFFF2222).copy(alpha = 0.15f),
    onErrorContainer = Color(0xFF991111),
    background = Color(0xFF1A1A24),
    onBackground = CyberText,
    surface = Color(0xFF22222E),
    onSurface = CyberText,
    surfaceVariant = Color(0xFF2A2A38),
    onSurfaceVariant = CyberTextDim,
    outline = CyberSteel,
    outlineVariant = Color(0xFF3A3A48),
    scrim = Color(0xFF000000),
    inverseSurface = CyberText,
    inverseOnSurface = CyberBlack,
    inversePrimary = CyberCyan,
    surfaceDim = Color(0xFF15151E),
    surfaceBright = Color(0xFF2A2A38),
    surfaceContainerLowest = Color(0xFF111118),
    surfaceContainerLow = Color(0xFF181820),
    surfaceContainer = Color(0xFF22222E),
    surfaceContainerHigh = Color(0xFF2A2A38),
    surfaceContainerHighest = Color(0xFF333344),
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
