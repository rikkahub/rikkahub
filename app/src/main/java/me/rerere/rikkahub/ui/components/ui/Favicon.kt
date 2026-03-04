package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Link01
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun Favicon(
    url: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(25),
) {
    val faviconUrl = remember(url) {
        url.toHttpUrlOrNull()?.host?.let { host ->
            "https://favicone.com/$host"
        }
    }
    AsyncImage(
        model = faviconUrl,
        modifier = modifier
            .size(20.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        placeholder = rememberVectorPainter(HugeIcons.Link01),
        fallback = rememberVectorPainter(HugeIcons.Link01),
    )
}

@Composable
fun FaviconRow(
    urls: List<String>,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    val displayUrls = remember(urls) {
        urls.distinctBy { it.toHttpUrlOrNull()?.host }
    }.take(3)
    Layout(
        modifier = modifier,
        content = {
            displayUrls.forEachIndexed { index, url ->
                Favicon(
                    url = url,
                    modifier = Modifier
                        .shadow(1.dp, CircleShape)
                        .zIndex(index.toFloat())
                        .size(size),
                    shape = CircleShape,
                )
            }
        }
    ) { measurables, constraints ->
        val placeables = measurables.map { measurable ->
            measurable.measure(constraints)
        }
        val faviconSize = size.roundToPx()
        val overlap = 4.dp.roundToPx()
        val step = faviconSize - overlap

        val width = if (placeables.isEmpty()) {
            0
        } else {
            faviconSize + (placeables.size - 1) * step
        }
        val height = if (placeables.isEmpty()) 0 else placeables.maxOfOrNull { it.height } ?: 0

        layout(width, height) {
            var xPosition = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x = xPosition, y = 0)
                xPosition += step
            }
        }
    }
}
