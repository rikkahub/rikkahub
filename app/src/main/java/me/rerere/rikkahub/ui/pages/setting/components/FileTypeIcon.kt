package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Text
import me.rerere.hugeicons.stroke.Video01
import java.io.File

@Composable
fun FileTypeIcon(
    mimeType: String,
    filePath: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    when {
        mimeType.startsWith("image/") && filePath != null -> {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(File(filePath))
                    .build(),
                contentDescription = null,
                modifier = modifier
                    .size(size)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop,
            )
        }

        mimeType.startsWith("image/") -> {
            Icon(
                imageVector = HugeIcons.Image02,
                contentDescription = null,
                modifier = modifier.size(size),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        mimeType.startsWith("video/") -> {
            Icon(
                imageVector = HugeIcons.Video01,
                contentDescription = null,
                modifier = modifier.size(size),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        mimeType.startsWith("audio/") -> {
            Icon(
                imageVector = HugeIcons.MusicNote03,
                contentDescription = null,
                modifier = modifier.size(size),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        mimeType == "application/pdf" -> {
            Icon(
                imageVector = HugeIcons.BookOpen01,
                contentDescription = null,
                modifier = modifier.size(size),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        mimeType in listOf("application/zip", "application/gzip", "application/x-tar",
            "application/x-rar-compressed", "application/x-7z-compressed")
                || mimeType.contains("compressed") -> {
            Icon(
                imageVector = HugeIcons.Package01,
                contentDescription = null,
                modifier = modifier.size(size),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        mimeType == "application/json"
                || (mimeType.startsWith("text/") && mimeType != "text/plain" && mimeType != "text/markdown") -> {
            Icon(
                imageVector = HugeIcons.Code,
                contentDescription = null,
                modifier = modifier.size(size),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        mimeType in listOf("text/plain", "text/markdown") -> {
            Icon(
                imageVector = HugeIcons.Text,
                contentDescription = null,
                modifier = modifier.size(size),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> {
            Icon(
                imageVector = HugeIcons.File01,
                contentDescription = null,
                modifier = modifier.size(size),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
