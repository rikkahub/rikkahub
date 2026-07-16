package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.cloud.CloudPullProgressTracker
import me.rerere.rikkahub.data.sync.cloud.UploadProgressTracker
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun CloudPullProgressHost(
    modifier: Modifier = Modifier,
) {
    val pullTracker: CloudPullProgressTracker = koinInject()
    val pullState by pullTracker.state.collectAsStateWithLifecycle()
    val transferTracker: UploadProgressTracker = koinInject()
    val transferState by transferTracker.state.collectAsStateWithLifecycle()
    val isDownloadingFile = transferState.active && transferState.isDownload
    val isActive = pullState.isPullingConversations || isDownloadingFile
    var visible by remember { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(isActive, pullState.dismissedForSession) {
        if (!isActive || pullState.dismissedForSession) {
            visible = false
        } else {
            delay(SHOW_DELAY_MS)
            if (isActive && !pullTracker.state.value.dismissedForSession) visible = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(20f),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        ) {
            PullProgressBanner(
                isDownloadingFile = isDownloadingFile,
                fileName = transferState.displayName,
                fileIndex = transferState.index,
                fileTotal = transferState.total,
                fileProgress = transferState.fraction,
                onDismiss = pullTracker::dismissForSession,
            )
        }
    }
}

@Composable
private fun PullProgressBanner(
    isDownloadingFile: Boolean,
    fileName: String,
    fileIndex: Int,
    fileTotal: Int,
    fileProgress: Float?,
    onDismiss: () -> Unit,
) {
    var offsetY by remember { mutableFloatStateOf(0f) }
    var heightPx by remember { mutableIntStateOf(1) }
    val dragState = rememberDraggableState { delta ->
        offsetY = (offsetY + delta).coerceIn(-heightPx.toFloat(), 0f)
    }
    val title = if (isDownloadingFile) {
        stringResource(R.string.cloud_pull_loading_file)
    } else {
        stringResource(R.string.cloud_pull_syncing_conversations)
    }
    val detail = if (isDownloadingFile && fileName.isNotBlank()) {
        if (fileTotal > 0) {
            stringResource(R.string.file_transfer_progress_detail, fileName, fileIndex, fileTotal)
        } else {
            fileName
        }
    } else {
        stringResource(R.string.cloud_pull_from_server)
    }

    Surface(
        modifier = Modifier
            .statusBarsPadding()
            .padding(start = 12.dp, top = 64.dp, end = 12.dp)
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    val dismiss = offsetY <= -heightPx * DISMISS_FRACTION || velocity < -DISMISS_VELOCITY
                    val target = if (dismiss) -heightPx.toFloat() else 0f
                    animate(offsetY, target) { value, _ -> offsetY = value }
                    if (dismiss) onDismiss()
                },
            ),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 5.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Download01,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (fileProgress != null && isDownloadingFile) {
                    LinearWavyProgressIndicator(
                        progress = { fileProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private const val SHOW_DELAY_MS = 250L
private const val DISMISS_FRACTION = 0.28f
private const val DISMISS_VELOCITY = 900f
