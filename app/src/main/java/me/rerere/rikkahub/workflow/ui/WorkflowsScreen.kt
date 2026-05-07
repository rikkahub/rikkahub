package me.rerere.rikkahub.workflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.RelativeTimeStrings
import me.rerere.rikkahub.utils.formatRelativeAgo
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowRunStatus
import me.rerere.rikkahub.workflow.repository.WorkflowRepository.Loaded
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkflowsScreen(vm: WorkflowsViewModel = koinViewModel()) {
    val nav = LocalNavController.current
    val workflows by vm.workflows.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showHowItWorks by remember { mutableStateOf(false) }

    if (showHowItWorks) {
        AlertDialog(
            onDismissRequest = { showHowItWorks = false },
            title = { Text(stringResource(R.string.setting_page_workflows_how_it_works_dialog_title)) },
            text = { Text(stringResource(R.string.setting_page_workflows_how_it_works_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showHowItWorks = false }) {
                    Text(stringResource(R.string.setting_page_workflows_how_it_works_dialog_dismiss))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_workflows)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (workflows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.setting_page_workflows_empty),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(workflows, key = { it.entity.id }) { loaded ->
                    WorkflowRow(
                        loaded = loaded,
                        onToggle = { enabled -> vm.setEnabled(loaded.entity.id, enabled) },
                        onTap = { nav.navigate(Screen.WorkflowDetail(loaded.entity.id)) },
                    )
                }
                item {
                    TextButton(
                        onClick = { showHowItWorks = true },
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text(stringResource(R.string.setting_page_workflows_how_it_works))
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowRow(
    loaded: Loaded,
    onToggle: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val rel = relativeStrings()
    val nowMs by rememberTickingNowMs()
    val triggerSummary = remember(loaded.definition) {
        oneLineTriggerSummary(loaded.definition)
    }
    val statusLine: String = when {
        loaded.entity.lastRunAtMs == null -> stringResource(R.string.setting_page_workflows_subtitle_never_run)
        else -> {
            val ago = formatRelativeAgo(loaded.entity.lastRunAtMs, nowMs, rel)
            when (loaded.entity.lastRunStatus) {
                WorkflowRunStatus.SUCCESS.name ->
                    stringResource(R.string.setting_page_workflows_subtitle_ran_success, ago)
                WorkflowRunStatus.FAILED.name ->
                    stringResource(R.string.setting_page_workflows_subtitle_ran_failed, ago)
                else ->
                    stringResource(R.string.setting_page_workflows_subtitle_ran_skipped, ago)
            }
        }
    }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() }
            .padding(horizontal = 8.dp),
        headlineContent = { Text(loaded.entity.name) },
        supportingContent = {
            Text(
                text = "${stringResource(R.string.setting_page_workflows_subtitle_when, triggerSummary)}\n$statusLine",
                maxLines = 3,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Switch(checked = loaded.entity.enabled, onCheckedChange = onToggle)
        },
    )
    HorizontalDivider()
}

internal fun oneLineTriggerSummary(def: WorkflowDefinition): String = when (val t = def.trigger) {
    is TriggerSpec.TimeCron ->
        if (!t.timeOfDay.isNullOrBlank()) "every ${t.timeOfDay}" else "schedule"
    is TriggerSpec.WifiConnected -> "WiFi connects" + (t.ssid?.let { " to $it" }.orEmpty())
    is TriggerSpec.WifiDisconnected -> "WiFi disconnects" + (t.ssid?.let { " from $it" }.orEmpty())
    is TriggerSpec.BluetoothDeviceConnected -> "Bluetooth connects"
    is TriggerSpec.BluetoothDeviceDisconnected -> "Bluetooth disconnects"
    is TriggerSpec.HeadphonesPlugged -> "headphones plugged"
    is TriggerSpec.HeadphonesUnplugged -> "headphones unplugged"
    is TriggerSpec.PowerConnected -> "power connected"
    is TriggerSpec.PowerDisconnected -> "power disconnected"
    is TriggerSpec.BatteryBelow -> "battery < ${t.thresholdPercent}%"
    is TriggerSpec.BatteryAbove -> "battery > ${t.thresholdPercent}%"
    is TriggerSpec.GeofenceEnter -> "you arrive at ${t.label ?: "a place"}"
    is TriggerSpec.GeofenceExit -> "you leave ${t.label ?: "a place"}"
    is TriggerSpec.AppLaunched -> "${t.packageName} launches"
    is TriggerSpec.AppClosed -> "${t.packageName} closes"
    is TriggerSpec.NotificationReceived -> "notification${t.packageName?.let { " from $it" } ?: ""}"
    is TriggerSpec.BootCompleted -> "device boots"
    is TriggerSpec.ScreenOn -> "screen on"
    is TriggerSpec.ScreenOff -> "screen off"
    is TriggerSpec.Manual -> "manual run only"
}

@Composable
internal fun relativeStrings(): RelativeTimeStrings = RelativeTimeStrings(
    justNow = stringResource(R.string.relative_time_just_now),
    secondsAgo = stringResource(R.string.relative_time_seconds_ago),
    minutesAgo = stringResource(R.string.relative_time_minutes_ago),
    hoursAgo = stringResource(R.string.relative_time_hours_ago),
    daysAgo = stringResource(R.string.relative_time_days_ago),
)

/**
 * A [State<Long>] of the current wall-clock millis, refreshed every 30s while the calling
 * Composable is in the composition. Used to keep "ran 2m ago" subtitles fresh without
 * fully re-deriving every recomposition. The audit found the old `remember { now }` pattern
 * silently froze the relative-time at the moment the row first composed.
 */
@Composable
internal fun rememberTickingNowMs(): State<Long> {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            state.longValue = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    return state
}
