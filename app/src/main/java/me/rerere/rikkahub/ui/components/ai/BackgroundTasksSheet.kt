package me.rerere.rikkahub.ui.components.ai

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.BackgroundShellLogTail
import me.rerere.rikkahub.service.BackgroundShellManager
import me.rerere.rikkahub.service.BackgroundShellStatus
import me.rerere.rikkahub.service.BackgroundShellTask
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import org.koin.compose.koinInject
import java.io.IOException

private const val TAG = "BackgroundTasksSheet"

private const val LOG_POLL_INTERVAL_MS = 1_000L

/** 会话的后台命令：列表里看状态、终止，点进去看日志 */
@Composable
fun BackgroundTasksSheet(
    tasks: List<BackgroundShellTask>,
    onStop: suspend (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<String?>(null) }
    // 终止要等宽限期，等待期间按钮显示进行中
    var stopping by remember { mutableStateOf(emptySet<String>()) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val stop: (String) -> Unit = { id ->
        stopping = stopping + id
        scope.launch {
            onStop(id)
            stopping = stopping - id
        }
    }
    val selected = tasks.find { it.id == selectedId }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selected != null) {
                BackHandler { selectedId = null }
                TaskDetail(
                    task = selected,
                    now = now,
                    stopping = selected.id in stopping,
                    onBack = { selectedId = null },
                    onStop = { stop(selected.id) },
                )
            } else {
                TaskList(
                    tasks = tasks,
                    now = now,
                    stopping = stopping,
                    onSelect = { selectedId = it },
                    onStop = stop,
                )
            }
        }
    }
}

@Composable
private fun TaskList(
    tasks: List<BackgroundShellTask>,
    now: Long,
    stopping: Set<String>,
    onSelect: (String) -> Unit,
    onStop: (String) -> Unit,
) {
    Text(text = stringResource(R.string.background_tasks_title), style = MaterialTheme.typography.titleMedium)
    if (tasks.isEmpty()) {
        Text(
            text = stringResource(R.string.background_tasks_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        CardGroup {
            // 最新启动的排在最前
            tasks.asReversed().forEach { task ->
                item(
                    onClick = { onSelect(task.id) },
                    leadingContent = { TaskStatusIcon(task) },
                    supportingContent = { Text(task.statusText(now)) },
                    trailingContent = if (task.status == BackgroundShellStatus.RUNNING) {
                        { StopButton(stopping = task.id in stopping, onClick = { onStop(task.id) }) }
                    } else {
                        null
                    },
                    headlineContent = {
                        Text(
                            text = task.command.replace("\n", " ").trim(),
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.TaskDetail(
    task: BackgroundShellTask,
    now: Long,
    stopping: Boolean,
    onBack: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = task.id, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
            Text(
                text = task.statusText(now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (task.status == BackgroundShellStatus.RUNNING) {
            FilledTonalButton(onClick = onStop, enabled = !stopping) {
                Text(stringResource(if (stopping) R.string.background_tasks_stopping else R.string.stop))
            }
        }
    }
    HighlightCodeBlock(
        code = task.command,
        language = "bash",
        modifier = Modifier.fillMaxWidth(),
    )
    BackgroundTaskLog(
        task = task,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
    )
}

@Composable
private fun TaskStatusIcon(task: BackgroundShellTask) {
    val colors = MaterialTheme.colorScheme
    val (icon, container, content) = when {
        task.status == BackgroundShellStatus.RUNNING ->
            Triple(HugeIcons.ComputerTerminal01, colors.primaryContainer, colors.onPrimaryContainer)

        task.status == BackgroundShellStatus.KILLED ->
            Triple(HugeIcons.Cancel01, colors.surfaceContainerHighest, colors.onSurfaceVariant)

        task.exitCode == 0 ->
            Triple(HugeIcons.Tick01, colors.secondaryContainer, colors.onSecondaryContainer)

        else -> Triple(HugeIcons.Alert01, colors.errorContainer, colors.onErrorContainer)
    }
    CardGroupIcon(icon = icon, containerColor = container, contentColor = content)
}

@Composable
private fun StopButton(stopping: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = !stopping) {
        if (stopping) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(HugeIcons.StopCircle, contentDescription = stringResource(R.string.stop))
        }
    }
}

/** 日志末尾，命令运行时持续刷新；不影响模型读取输出的进度 */
@Composable
fun BackgroundTaskLog(
    task: BackgroundShellTask,
    modifier: Modifier = Modifier,
) {
    val manager = koinInject<BackgroundShellManager>()
    val running = task.status == BackgroundShellStatus.RUNNING
    var log by remember(task.id) { mutableStateOf<BackgroundShellLogTail?>(null) }
    LaunchedEffect(task.id, running) {
        while (true) {
            try {
                manager.readLogTail(task.id)?.let { log = it }
            } catch (e: IOException) {
                // 日志可能正好被截断或删除，保留上一次的内容
                Log.w(TAG, "read log failed: ${task.id}", e)
            }
            if (!running) break
            delay(LOG_POLL_INTERVAL_MS)
        }
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        // 倒序滚动让视图停在底部，新输出进来时自动跟随
        Box(
            modifier = Modifier
                .verticalScroll(rememberScrollState(), reverseScrolling = true)
                .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (log?.truncated == true) {
                    Text(
                        text = stringResource(R.string.background_tasks_log_truncated, task.logPath),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val text = log?.text.orEmpty()
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(if (running) R.string.background_tasks_no_output_yet else R.string.background_tasks_no_output),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundShellTask.statusText(now: Long): String {
    val duration = formatDuration((endedAt ?: now) - startedAt)
    return when (status) {
        BackgroundShellStatus.RUNNING -> stringResource(R.string.background_tasks_status_running, duration)
        BackgroundShellStatus.EXITED ->
            stringResource(R.string.background_tasks_status_exited, exitCode ?: -1, duration)

        BackgroundShellStatus.KILLED -> stringResource(R.string.background_tasks_status_stopped, duration)
    }
}

@Composable
private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0).toInt()
    return when {
        seconds < 60 -> stringResource(R.string.background_tasks_duration_seconds, seconds)
        seconds < 3600 -> stringResource(R.string.background_tasks_duration_minutes, seconds / 60, seconds % 60)
        else -> stringResource(R.string.background_tasks_duration_hours, seconds / 3600, seconds % 3600 / 60)
    }
}
