package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.utils.base64Encode

internal fun svgCodeToDataUri(code: String): String {
    return "data:image/svg+xml;base64,${code.base64Encode()}"
}

@Composable
internal fun SvgRenderedCodeBlock(
    code: String,
    modifier: Modifier = Modifier,
) {
    val svgDataUri = remember(code) {
        svgCodeToDataUri(code)
    }
    ZoomableAsyncImage(
        model = svgDataUri,
        contentDescription = null,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .heightIn(min = 120.dp, max = 480.dp),
        contentScale = ContentScale.Fit,
    )
}
