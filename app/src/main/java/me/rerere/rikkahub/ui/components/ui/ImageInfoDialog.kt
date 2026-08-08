package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.fileSizeToString
import kotlin.math.roundToInt
import me.rerere.rikkahub.utils.ImageUtils
import java.io.File

@Composable
fun ImageInfoDialog(
    part: UIMessagePart.Image,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isCompressing by remember { mutableStateOf(false) }
    var fileSize by remember { mutableStateOf(0L) }

    val uri = remember { part.url.toUri() }
    val file = remember { uri.path?.let { File(it) } }
    var imageInfo by remember { mutableStateOf<ImageUtils.ImageInfo?>(null) }
    val width = imageInfo?.width ?: 0
    val height = imageInfo?.height ?: 0
    val maxDimension = maxOf(width, height)

    LaunchedEffect(uri) {
        fileSize = file?.length() ?: 0L
        imageInfo = withContext(Dispatchers.IO) {
            ImageUtils.getImageInfo(context, uri)
        }
    }

    fun doCompress(targetDimension: Int) {
        if (isCompressing || targetDimension >= maxDimension) return
        isCompressing = true
        scope.launch {
            val origSize = fileSize
            val newSize = withContext(Dispatchers.IO) {
                ImageUtils.compressImage(context, uri, targetDimension)
            }
            if (newSize != null && newSize > 0L && newSize < origSize) {
                fileSize = newSize
                runCatching {
                    val loader = coil3.SingletonImageLoader.get(context)
                    loader.memoryCache?.remove(MemoryCache.Key(part.url))
                    loader.diskCache?.remove(part.url)
                }
                val saved = origSize - newSize
                val pct = (saved.toFloat() / origSize * 100f).roundToInt()
                toaster.show(
                    "${origSize.fileSizeToString()} → ${newSize.fileSizeToString()} (-${pct}%)",
                    type = ToastType.Success,
                )
            }
            isCompressing = false
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = { if (!isCompressing) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = part.url,
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = file?.name ?: "",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${fileSize.fileSizeToString()} · ${width}×${height}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.image_compression_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val presets = listOf(
                        "1/2" to (maxDimension / 2),
                        "1/4" to (maxDimension / 4),
                        "1024" to 1024,
                    )
                    presets.forEach { (label, target) ->
                        if (target in 1 until maxDimension) {
                            Button(
                                onClick = { doCompress(target) },
                                enabled = !isCompressing,
                                modifier = Modifier.height(36.dp),
                            ) {
                                Text(label)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isCompressing,
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}
