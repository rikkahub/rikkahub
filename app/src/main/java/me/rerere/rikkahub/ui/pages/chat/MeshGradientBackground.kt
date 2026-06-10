package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gemini-style dynamic gradient background.
 *
 * How it works:
 *  1. A base linear gradient (bluish at the top, white at the bottom).
 *  2. A few radialGradient blobs on top (colored center -> transparent edge) which are
 *     inherently soft.
 *  3. Each blob runs on its own independent infinite-animation period, drifting slowly along a
 *     sine/cosine path.
 *
 * It does not rely on Modifier.blur, so it works on all API levels and performs better.
 */
@Composable
fun MeshGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val transition = rememberInfiniteTransition(label = "aurora")

    // One phase per blob (0..2π); different durations -> staggered drift, avoiding a uniform look.
    @Composable
    fun phase(durationMillis: Int, label: String) = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing)),
        label = label,
    )

    val p1 by phase(14_000, "p1")
    val p2 by phase(17_000, "p2")
    val p3 by phase(20_000, "p3")

    val dark = LocalDarkMode.current
    val baseGradient = if (dark) {
        // Dark: deep blue at the top, fading down to near-black.
        arrayOf(
            0.0f to Color(0xFF1B2A45),
            0.22f to Color(0xFF15223A),
            0.45f to Color(0xFF0D1626),
            0.65f to Color(0xFF0A0F18),
            1.0f to Color(0xFF080B12),
        )
    } else {
        // Light: bluish at the top, fading down to white.
        arrayOf(
            0.0f to Color(0xFFAFD0F2),
            0.22f to Color(0xFFCBE0F6),
            0.45f to Color(0xFFF1F7FD),
            0.65f to Color(0xFFFFFFFF),
            1.0f to Color(0xFFFFFFFF),
        )
    }

    // Blob palette (blue / teal / light blue) and intensity, one set each for light and dark.
    val blobBlue = if (dark) Color(0xFF3E6FB0) else Color(0xFF9EC5F0)
    val blobTeal = if (dark) Color(0xFF2E7D74) else Color(0xFFA8E6E0)
    val blobLightBlue = if (dark) Color(0xFF4A6E96) else Color(0xFFB6D7F2)
    val alphaBlue = if (dark) 0.40f else 0.55f
    val alphaTeal = if (dark) 0.28f else 0.35f
    val alphaLightBlue = if (dark) 0.32f else 0.40f

    // The colour stops only depend on the theme, so build the lists ONCE here (per recomposition),
    // not inside the Canvas draw lambda — that lambda re-runs every animation frame and would
    // otherwise allocate three fresh List<Color> per frame. Only the radial Brush itself is rebuilt
    // per frame (its center is animated and Brush is immutable), which is inherent to the effect.
    val blueStops = listOf(blobBlue.copy(alpha = alphaBlue), Color.Transparent)
    val tealStops = listOf(blobTeal.copy(alpha = alphaTeal), Color.Transparent)
    val lightBlueStops = listOf(blobLightBlue.copy(alpha = alphaLightBlue), Color.Transparent)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colorStops = baseGradient)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Blob radius scales with the longer screen edge; large enough to stay soft.
            val r = maxOf(w, h)

            // Blobs all cluster near the top and fade downward, leaving the lower half clear.
            // Top blue (primary color, slow horizontal drift).
            drawBlob(
                center = Offset(w * 0.45f + sin(p1) * w * 0.20f, h * 0.02f + cos(p1) * h * 0.05f),
                radius = r * 0.55f,
                colorStops = blueStops,
            )
            // Top-left teal accent.
            drawBlob(
                center = Offset(w * 0.12f + sin(p2) * w * 0.12f, h * 0.22f + cos(p2) * h * 0.08f),
                radius = r * 0.40f,
                colorStops = tealStops,
            )
            // Top-right light blue.
            drawBlob(
                center = Offset(w * 0.88f + sin(p3) * w * -0.14f, h * 0.08f + cos(p3) * h * 0.06f),
                radius = r * 0.42f,
                colorStops = lightBlueStops,
            )
        }

        content()
    }
}

/**
 * Draws one soft blob: colored center fading outward to transparent. [colorStops] (center color ->
 * transparent) is precomputed by the caller and reused across frames; only the animated [center]
 * forces a fresh radial Brush per frame.
 */
private fun DrawScope.drawBlob(
    center: Offset,
    radius: Float,
    colorStops: List<Color>,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = colorStops,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
