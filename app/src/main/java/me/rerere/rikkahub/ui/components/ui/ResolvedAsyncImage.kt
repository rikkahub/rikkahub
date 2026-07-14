package me.rerere.rikkahub.ui.components.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.CloudMediaResolver
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.compose.koinInject

/**
 * Local-first image: resolve managed/cloud files before Coil load.
 * Missing remote bytes trigger a single-file download, then re-resolve.
 */
@Composable
fun ResolvedAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    crossfade: Boolean = true,
    onResolved: ((Any?) -> Unit)? = null,
) {
    val resolver: CloudMediaResolver = koinInject()
    val context = LocalContext.current
    val placeholder = if (LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current
    var resolved by remember(model) { mutableStateOf<Any?>(model) }
    var tick by remember(model) { mutableStateOf(0) }

    LaunchedEffect(model, tick) {
        val next = resolver.resolveForDisplay(model)
        resolved = next
        onResolved?.invoke(next)
        // If still only a remote reference, poll a few times while worker downloads.
        if (next == null || (next is String && next.startsWith("perry-file://"))) {
            kotlinx.coroutines.delay(800)
            if (tick < 8) tick += 1
        } else if (next is String && (next.startsWith("file:") || next.startsWith("/"))) {
            // foreign path waiting for download
            val again = resolver.resolveForDisplay(model)
            if (again != next && tick < 8) {
                kotlinx.coroutines.delay(800)
                tick += 1
            }
        }
    }

    val coilModel = ImageRequest.Builder(context)
        .data(resolved ?: model)
        .placeholder(placeholder)
        .crossfade(crossfade)
        .allowHardware(!export)
        .build()

    AsyncImage(
        model = coilModel,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alpha = alpha,
        alignment = alignment,
    )
}
