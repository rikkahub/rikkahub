package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.dokar.sonner.ToastType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import me.rerere.rikkahub.ui.context.LocalToaster

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundShellJobsSheet(
    jobs: List<UiBackgroundJob>,
    onDismiss: () -> Unit,
    loadTail: suspend (UiBackgroundJob) -> String,
    onCancel: (UiBackgroundJob) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val toaster = LocalToaster.current
    var tails by remember { mutableStateOf<Map<String, TailState>>(emptyMap()) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(jobs) {
        nowMillis = System.currentTimeMillis()
        jobs.forEach { job ->
            tails = tails + (job.taskId to TailState.Loading)
            runCatching {
                loadTail(job)
            }.onSuccess { tail ->
                tails = tails + (job.taskId to TailState.Loaded(tail))
            }.onFailure { error ->
                if (error is CancellationException) throw error
                tails = tails + (job.taskId to TailState.Failed(error.message ?: "Unknown error"))
                toaster.show("Failed to load background job output", type = ToastType.Error)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Background jobs",
                style = MaterialTheme.typography.titleLarge,
            )
            if (jobs.isEmpty()) {
                Text(
                    text = "No running background jobs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    jobs.forEach { job ->
                        BackgroundJobItem(
                            job = job,
                            tailState = tails[job.taskId] ?: TailState.Loading,
                            nowMillis = nowMillis,
                            onCancel = { onCancel(job) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundJobItem(
    job: UiBackgroundJob,
    tailState: TailState,
    nowMillis: Long,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = job.command,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (job.cancellable) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Lucide.X,
                            contentDescription = "Cancel job",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = job.status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = formatBackgroundJobElapsed(job.elapsedStartMillis(), nowMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = tailText(tailState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private sealed interface TailState {
    data object Loading : TailState
    data class Loaded(val text: String) : TailState
    data class Failed(val message: String) : TailState
}

private fun tailText(state: TailState): String =
    when (state) {
        TailState.Loading -> "Loading output..."
        is TailState.Loaded -> state.text.ifBlank { "No output yet" }
        is TailState.Failed -> "Output unavailable: ${state.message}"
    }

internal fun formatBackgroundJobElapsed(startMillis: Long?, nowMillis: Long): String {
    if (startMillis == null) return "Elapsed unknown"
    val elapsedSeconds = max(0L, nowMillis - startMillis) / 1000L
    val hours = elapsedSeconds / 3600L
    val minutes = (elapsedSeconds % 3600L) / 60L
    val seconds = elapsedSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
