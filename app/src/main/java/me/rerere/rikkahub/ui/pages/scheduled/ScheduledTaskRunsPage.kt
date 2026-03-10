package me.rerere.rikkahub.ui.pages.scheduled

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.ScheduledTaskRun
import me.rerere.rikkahub.data.model.TaskRunStatus
import me.rerere.rikkahub.data.repository.ScheduledTaskRunRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.compose.koinInject
import java.time.Instant

@Composable
fun ScheduledTaskRunsPage() {
    val navController = LocalNavController.current
    val repository: ScheduledTaskRunRepository = koinInject()
    val runs by repository.getRecentFinishedRuns(limit = 200).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var pendingDeleteRun by remember { mutableStateOf<ScheduledTaskRun?>(null) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.scheduled_task_runs_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    if (runs.isNotEmpty()) {
                        IconButton(onClick = { showDeleteAllDialog = true }) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.scheduled_task_runs_delete_all)
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (runs.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.scheduled_task_runs_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(runs, key = { it.id.toString() }) { run ->
                    ScheduledTaskRunItem(
                        run = run,
                        onClick = {
                            navController.navigate(Screen.ScheduledTaskRunDetail(run.id.toString()))
                        },
                        onDelete = {
                            pendingDeleteRun = run
                        }
                    )
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = showDeleteAllDialog,
        title = stringResource(R.string.scheduled_task_runs_delete_all_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteAllDialog = false
            scope.launch {
                repository.deleteAllFinishedRuns()
            }
        },
        onDismiss = { showDeleteAllDialog = false }
    ) {
        Text(stringResource(R.string.scheduled_task_runs_delete_all_confirmation))
    }

    RikkaConfirmDialog(
        show = pendingDeleteRun != null,
        title = stringResource(R.string.scheduled_task_runs_delete_one_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            val runId = pendingDeleteRun?.id ?: return@RikkaConfirmDialog
            pendingDeleteRun = null
            scope.launch {
                repository.deleteRun(runId)
            }
        },
        onDismiss = { pendingDeleteRun = null }
    ) {
        Text(stringResource(R.string.scheduled_task_runs_delete_one_confirmation))
    }
}

@Composable
private fun ScheduledTaskRunItem(
    run: ScheduledTaskRun,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = run.taskTitleSnapshot.ifBlank { stringResource(R.string.assistant_schedule_untitled) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                val started = Instant.ofEpochMilli(run.startedAt).toLocalDateTime()
                Text("$started · ${run.status.shortLabel()}")
            },
            trailingContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = run.status.shortLabel(),
                        color = run.status.color(),
                        style = MaterialTheme.typography.labelMedium
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.delete)
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun TaskRunStatus.color() = when (this) {
    TaskRunStatus.SUCCESS -> MaterialTheme.colorScheme.primary
    TaskRunStatus.FAILED -> MaterialTheme.colorScheme.error
    TaskRunStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
    TaskRunStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun TaskRunStatus.shortLabel(): String = when (this) {
    TaskRunStatus.SUCCESS -> stringResource(R.string.assistant_schedule_status_success)
    TaskRunStatus.FAILED -> stringResource(R.string.assistant_schedule_status_failed)
    TaskRunStatus.RUNNING -> stringResource(R.string.assistant_schedule_status_running)
    TaskRunStatus.IDLE -> stringResource(R.string.assistant_schedule_status_idle)
}
