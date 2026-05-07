package me.rerere.rikkahub.workflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.formatRelativeAgo
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowRun
import me.rerere.rikkahub.workflow.repository.WorkflowRepository.Loaded
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkflowDetailScreen(
    workflowId: String,
    vm: WorkflowsViewModel = koinViewModel(),
) {
    val nav = LocalNavController.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }

    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var history by remember { mutableStateOf<List<WorkflowRun>>(emptyList()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(workflowId) {
        loaded = vm.get(workflowId)
        history = vm.history(workflowId)
    }

    val currentLoaded = loaded
    if (currentLoaded == null) {
        Scaffold(
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.setting_page_workflows)) },
                    navigationIcon = { BackButton() },
                    colors = CustomColors.topBarColors,
                )
            },
            containerColor = CustomColors.topBarColors.containerColor,
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                Text(
                    text = stringResource(R.string.setting_page_workflows_empty),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.setting_page_workflow_detail_delete_confirm_title)) },
            text = { Text(stringResource(R.string.setting_page_workflow_detail_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.delete(currentLoaded.entity.id) {
                        nav.popBackStack()
                    }
                }) {
                    Text(
                        stringResource(R.string.setting_page_workflow_detail_delete_confirm_yes),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.setting_page_workflow_detail_delete_confirm_no))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(currentLoaded.entity.name) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Description
            currentLoaded.definition.description?.takeIf { it.isNotBlank() }?.let { desc ->
                item {
                    SectionHeader(stringResource(R.string.setting_page_workflow_detail_section_description))
                    Text(desc, style = MaterialTheme.typography.bodyMedium)
                }
            }
            // Trigger
            item {
                SectionHeader(stringResource(R.string.setting_page_workflow_detail_section_trigger))
                Text(oneLineTriggerSummary(currentLoaded.definition))
            }
            // Conditions
            item {
                SectionHeader(stringResource(R.string.setting_page_workflow_detail_section_conditions))
                if (currentLoaded.definition.conditions.isEmpty()) {
                    Text(stringResource(R.string.setting_page_workflow_detail_no_conditions))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in currentLoaded.definition.conditions) {
                            Text("• ${conditionLine(c)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            // Actions
            item {
                SectionHeader(stringResource(R.string.setting_page_workflow_detail_section_actions))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((idx, a) in currentLoaded.definition.actions.withIndex()) {
                        ActionRow(idx + 1, a)
                    }
                }
            }
            // Stats
            item {
                SectionHeader(stringResource(R.string.setting_page_workflow_detail_section_stats))
                StatsBlock(currentLoaded)
            }
            // History
            item {
                SectionHeader(stringResource(R.string.setting_page_workflow_detail_section_history))
                if (history.isEmpty()) {
                    Text(stringResource(R.string.setting_page_workflow_detail_history_empty))
                } else {
                    val (rel, _) = relativeStrings()
                    val nowMs = remember { System.currentTimeMillis() }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (r in history) {
                            val ago = formatRelativeAgo(r.firedAtMs, nowMs, rel)
                            val line = "$ago — ${r.status.name}" +
                                (r.errorMessage?.let { " — ${it.take(60)}" } ?: "")
                            Text(line, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            // Footer buttons
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Button(onClick = {
                        scope.launch {
                            val outcome = vm.runNow(currentLoaded.entity.id)
                            history = vm.history(currentLoaded.entity.id)
                            loaded = vm.get(currentLoaded.entity.id)
                            snackbarHostState.showSnackbar(
                                ctx.getString(R.string.setting_page_workflow_detail_run_now_done, outcome.status.name)
                            )
                        }
                    }) {
                        Text(stringResource(R.string.setting_page_workflow_detail_run_now))
                    }
                    TextButton(onClick = {
                        // Edit-in-chat: deep-link to a fresh chat with a pre-filled prompt.
                        // Chat(id="") is the existing convention for "new conversation" — see
                        // RouteActivity's Screen.Chat default elsewhere in nav.
                        nav.navigate(Screen.Chat(
                            id = "",
                            text = ctx.getString(
                                R.string.setting_page_workflow_detail_edit_prefill,
                                currentLoaded.entity.name,
                            ),
                        ))
                    }) {
                        Text(stringResource(R.string.setting_page_workflow_detail_edit))
                    }
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text(
                            stringResource(R.string.setting_page_workflow_detail_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun ActionRow(index: Int, action: WorkflowAction) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("$index. ", style = MaterialTheme.typography.bodyMedium)
            Text(
                action.tool,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Text(" (${action.timeoutSeconds}s)", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp))
        }
        if (action.args.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                Text(if (expanded) "hide args" else "show args",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (expanded) {
                Text(
                    action.args.toString(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StatsBlock(loaded: Loaded) {
    val (rel, _) = relativeStrings()
    val nowMs = remember { System.currentTimeMillis() }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val lastRunText = loaded.entity.lastRunAtMs?.let {
            stringResource(R.string.setting_page_workflow_detail_stat_last_run, formatRelativeAgo(it, nowMs, rel))
        } ?: stringResource(R.string.setting_page_workflow_detail_stat_last_run_never)
        Text(lastRunText, style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(R.string.setting_page_workflow_detail_stat_runs_today, loaded.entity.runsTodayCount),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (loaded.definition.cooldownSeconds == 0)
                stringResource(R.string.setting_page_workflow_detail_stat_cooldown_none)
            else
                stringResource(R.string.setting_page_workflow_detail_stat_cooldown, loaded.definition.cooldownSeconds),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            loaded.definition.maxRunsPerDay?.let {
                stringResource(R.string.setting_page_workflow_detail_stat_daily_cap, it)
            } ?: stringResource(R.string.setting_page_workflow_detail_stat_daily_cap_unlimited),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun conditionLine(c: me.rerere.rikkahub.workflow.model.ConditionSpec): String = when (c) {
    is me.rerere.rikkahub.workflow.model.ConditionSpec.TimeBetween -> "between ${c.start} and ${c.end}"
    is me.rerere.rikkahub.workflow.model.ConditionSpec.TimeAfterSunset -> "after sunset" +
        if (c.offsetMinutes != 0) " (${c.offsetMinutes}m offset)" else ""
    is me.rerere.rikkahub.workflow.model.ConditionSpec.TimeBeforeSunrise -> "before sunrise" +
        if (c.offsetMinutes != 0) " (${c.offsetMinutes}m offset)" else ""
    is me.rerere.rikkahub.workflow.model.ConditionSpec.DayOfWeekIn -> "day(s) ${c.days.joinToString(",")}"
    is me.rerere.rikkahub.workflow.model.ConditionSpec.WifiSsidIs -> "WiFi is ${c.ssid}"
    is me.rerere.rikkahub.workflow.model.ConditionSpec.WifiSsidIn -> "WiFi in ${c.ssids.joinToString(",")}"
    is me.rerere.rikkahub.workflow.model.ConditionSpec.BatteryAbove -> "battery > ${c.percent}%"
    is me.rerere.rikkahub.workflow.model.ConditionSpec.BatteryBelow -> "battery < ${c.percent}%"
    is me.rerere.rikkahub.workflow.model.ConditionSpec.IsCharging -> "charging"
    is me.rerere.rikkahub.workflow.model.ConditionSpec.IsNotCharging -> "not charging"
    is me.rerere.rikkahub.workflow.model.ConditionSpec.ForegroundAppIs -> "foreground app = ${c.packageName}"
    is me.rerere.rikkahub.workflow.model.ConditionSpec.ForegroundAppIn -> "foreground in ${c.packageNames.size} pkgs"
    is me.rerere.rikkahub.workflow.model.ConditionSpec.ScreenIsOn -> "screen on"
    is me.rerere.rikkahub.workflow.model.ConditionSpec.ScreenIsOff -> "screen off"
}
