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
import me.rerere.rikkahub.ui.theme.LocalAmoledDarkMode
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import kotlin.math.min

@Composable
fun luneGlassContainerColor(): Color {
    val base = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
    return if (LocalDarkMode.current) {
        base.copy(alpha = 0.84f)
    } else {
        base.copy(alpha = 0.96f)
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
    }
}

@Composable
fun LuneSection(
    modifier: Modifier = Modifier,
    border: BorderStroke? = BorderStroke(1.dp, luneGlassBorderColor()),
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = luneGlassContainerColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = border,
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
    border: BorderStroke? = BorderStroke(1.dp, luneGlassBorderColor()),
    content: @Composable () -> Unit,
) {
    val hazeModifier = if (hazeState != null) {
        Modifier.hazeEffect(
            state = hazeState,
            style = HazeMaterials.thin(containerColor = luneGlassContainerColor()),
        )
    } else {
        Modifier
    }
    Surface(
        modifier = modifier.then(hazeModifier),
        shape = RoundedCornerShape(24.dp),
        color = luneGlassContainerColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        content()
    }
}
