package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.min
import me.rerere.rikkahub.ui.theme.LocalAmoledDarkMode
import me.rerere.rikkahub.ui.theme.LocalDarkMode

private data class LuneStar(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
)

private val luneStars = listOf(
    LuneStar(0.08f, 0.10f, 1.3f, 0.58f),
    LuneStar(0.14f, 0.22f, 0.9f, 0.42f),
    LuneStar(0.21f, 0.08f, 1.5f, 0.64f),
    LuneStar(0.28f, 0.17f, 1.0f, 0.38f),
    LuneStar(0.34f, 0.26f, 1.2f, 0.52f),
    LuneStar(0.43f, 0.12f, 0.9f, 0.40f),
    LuneStar(0.51f, 0.06f, 1.7f, 0.60f),
    LuneStar(0.62f, 0.18f, 1.0f, 0.44f),
    LuneStar(0.70f, 0.08f, 1.3f, 0.46f),
    LuneStar(0.79f, 0.20f, 1.1f, 0.40f),
    LuneStar(0.88f, 0.10f, 1.5f, 0.56f),
    LuneStar(0.92f, 0.24f, 0.9f, 0.36f),
    LuneStar(0.16f, 0.38f, 0.8f, 0.30f),
    LuneStar(0.30f, 0.44f, 1.0f, 0.32f),
    LuneStar(0.48f, 0.36f, 0.9f, 0.28f),
    LuneStar(0.66f, 0.42f, 1.0f, 0.28f),
    LuneStar(0.84f, 0.34f, 0.8f, 0.26f),
)

@Composable
fun luneGlassContainerColor(): Color {
    val base = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
    return if (LocalDarkMode.current) {
        base.copy(alpha = 0.74f)
    } else {
        base.copy(alpha = 0.92f)
    }
}

@Composable
fun luneGlassBorderColor(): Color {
    return if (LocalDarkMode.current) {
        Color.White.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f)
    }
}

@Composable
fun LuneBackdrop(
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkMode.current
    val amoledDarkMode = LocalAmoledDarkMode.current
    val colorScheme = MaterialTheme.colorScheme
    val pureBlackBackdrop = dark && (
        amoledDarkMode ||
            (colorScheme.background == Color.Black && colorScheme.surface == Color.Black)
        )

    val verticalColors = when {
        pureBlackBackdrop -> {
            List(4) { colorScheme.background }
        }
        dark -> {
            listOf(
                colorScheme.background,
                colorScheme.surface,
                colorScheme.surfaceContainerLow,
                colorScheme.surfaceContainer,
            )
        }
        else -> {
            listOf(
                colorScheme.background,
                colorScheme.surface,
                colorScheme.surfaceContainerLow,
                colorScheme.surfaceContainerHigh,
            )
        }
    }
    val moonGlow = when {
        pureBlackBackdrop -> Color.Transparent
        dark -> colorScheme.tertiary.copy(alpha = 0.14f)
        else -> colorScheme.tertiaryContainer.copy(alpha = 0.56f)
    }
    val blueGlow = when {
        pureBlackBackdrop -> Color.Transparent
        dark -> colorScheme.primary.copy(alpha = 0.16f)
        else -> colorScheme.primaryContainer.copy(alpha = 0.32f)
    }
    val horizonGlow = when {
        pureBlackBackdrop -> Color.Transparent
        dark -> colorScheme.secondary.copy(alpha = 0.10f)
        else -> colorScheme.secondaryContainer.copy(alpha = 0.24f)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(verticalColors)
        )

        val radiusBase = min(size.width, size.height)

        if (!pureBlackBackdrop) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(moonGlow, Color.Transparent),
                    center = Offset(size.width * 0.82f, size.height * 0.12f),
                    radius = radiusBase * 0.45f,
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(blueGlow, Color.Transparent),
                    center = Offset(size.width * 0.12f, size.height * 0.06f),
                    radius = radiusBase * 0.52f,
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(horizonGlow, Color.Transparent),
                    center = Offset(size.width * 0.26f, size.height * 0.95f),
                    radius = radiusBase * 0.78f,
                )
            )
        }

        if (dark && !pureBlackBackdrop) {
            luneStars.forEach { star ->
                drawCircle(
                    color = Color.White.copy(alpha = star.alpha),
                    radius = star.radius.dp.toPx(),
                    center = Offset(size.width * star.x, size.height * star.y),
                )
            }
        }
    }
}

@Composable
fun LuneSection(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = luneGlassContainerColor(),
        border = BorderStroke(1.dp, luneGlassBorderColor()),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(content = content)
    }
}

@Composable
fun LuneTopBarSurface(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    content: @Composable () -> Unit,
) {
    val hazeModifier = if (hazeState != null) {
        Modifier.hazeEffect(
            state = hazeState,
            style = HazeMaterials.ultraThin(containerColor = luneGlassContainerColor()),
        )
    } else {
        Modifier
    }
    Surface(
        modifier = modifier.then(hazeModifier),
        shape = RoundedCornerShape(24.dp),
        color = luneGlassContainerColor(),
        border = BorderStroke(1.dp, luneGlassBorderColor()),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        content()
    }
}
