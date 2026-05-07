package me.rerere.rikkahub.ui.pages.setting.scheduledjobs

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.formatRelativeAgo
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun ScheduledJobDetailScreen(
    jobId: String,
    vm: ScheduledJobsViewModel = koinViewModel(),
) {
    val nav = LocalNavController.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }

    var job by remember { mutableStateOf<ScheduledJobEntity?>(null) }
    var history by remember { mutableStateOf<List<ScheduledJobRunEntity>>(emptyList()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(jobId) {
        job = vm.get(jobId)
        history = vm.history(jobId)
    }

    val current = job
    if (current == null) {
        Scaffold(
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.setting_page_scheduled_jobs)) },
                    navigationIcon = { BackButton() },
                    colors = CustomColors.topBarColors,
                )
            },
            containerColor = CustomColors.topBarColors.containerColor,
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                Text(
                    text = stringResource(R.string.setting_page_scheduled_jobs_empty),
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
            title = { Text(stringResource(R.string.setting_page_scheduled_jobs_delete_confirm_title)) },
            text = { Text(stringResource(R.string.setting_page_scheduled_jobs_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.delete(current.id) { nav.popBackStack() }
                }) {
                    Text(
                        stringResource(R.string.setting_page_scheduled_jobs_delete_confirm_yes),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.setting_page_scheduled_jobs_delete_confirm_no))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(current.name) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        // Sticky bottom bar so Run-now / Edit / Delete stay reachable as history grows.
        bottomBar = {
            androidx.compose.material3.Surface(
                tonalElevation = 3.dp,
                color = CustomColors.topBarColors.containerColor,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    androidx.compose.material3.Button(onClick = {
                        scope.launch {
                            val outcome = vm.runNow(current.id)
                            history = vm.history(current.id)
                            job = vm.get(current.id)
                            val msgRes = when (outcome) {
                                ScheduledJobsViewModel.RunNowOutcome.Fired ->
                                    R.string.setting_page_scheduled_jobs_run_now_fired
                                ScheduledJobsViewModel.RunNowOutcome.Disabled ->
                                    R.string.setting_page_scheduled_jobs_run_now_disabled
                                ScheduledJobsViewModel.RunNowOutcome.NotFound ->
                                    R.string.setting_page_scheduled_jobs_run_now_not_found
                            }
                            snackbarHostState.showSnackbar(ctx.getString(msgRes))
                        }
                    }) {
                        Text(stringResource(R.string.setting_page_scheduled_jobs_run_now))
                    }
                    TextButton(onClick = {
                        nav.navigate(
                            Screen.Chat(
                                id = kotlin.uuid.Uuid.random().toString(),
                                text = ctx.getString(
                                    R.string.setting_page_scheduled_jobs_edit_prefill,
                                    current.name,
                                ).base64Encode(),
                            )
                        )
                    }) {
                        Text(stringResource(R.string.setting_page_scheduled_jobs_edit))
                    }
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text(
                            stringResource(R.string.setting_page_scheduled_jobs_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
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
            current.description?.takeIf { it.isNotBlank() }?.let { desc ->
                item {
                    SectionHeader(stringResource(R.string.setting_page_scheduled_jobs_section_description))
                    Text(desc, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                SectionHeader(stringResource(R.string.setting_page_scheduled_jobs_section_schedule))
                Text(summariseSchedule(current))
                current.timezone?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        stringResource(R.string.setting_page_scheduled_jobs_schedule_tz, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                current.startAtUnixMs?.let {
                    Text(
                        stringResource(R.string.setting_page_scheduled_jobs_schedule_starts, formatAbsoluteForDetail(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                current.endAtUnixMs?.let {
                    Text(
                        stringResource(R.string.setting_page_scheduled_jobs_schedule_ends, formatAbsoluteForDetail(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionHeader(stringResource(R.string.setting_page_scheduled_jobs_section_what_runs))
                Text(modeLabel(current))
                if (current.mode == "llm") {
                    current.prompt?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(8.dp),
                        )
                    }
                } else {
                    val actions = remember(current.actionsJson) { parseActions(current.actionsJson) }
                    if (actions.isEmpty()) {
                        Text(
                            stringResource(R.string.setting_page_scheduled_jobs_no_actions),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            actions.forEachIndexed { idx, a -> ActionRow(idx + 1, a) }
                        }
                    }
                }
            }
            item {
                SectionHeader(stringResource(R.string.setting_page_scheduled_jobs_section_stats))
                StatsBlock(current)
            }
            item {
                SectionHeader(stringResource(R.string.setting_page_scheduled_jobs_section_history))
                if (history.isEmpty()) {
                    Text(stringResource(R.string.setting_page_scheduled_jobs_history_empty))
                } else {
                    val rel = relativeStrings()
                    val nowMs by rememberTickingNowMs()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (r in history) {
                            val ago = formatRelativeAgo(r.startedAtMs, nowMs, rel)
                            val line = "$ago — ${r.outcome}" +
                                (r.errorMessage?.let { " — ${it.take(60)}" } ?: "")
                            Text(line, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            // Run now / Edit / Delete moved to the Scaffold's bottomBar so they stay
            // pinned and visible regardless of how long the history grows.
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

private data class ParsedAction(val tool: String, val argsBlock: String)

/**
 * Parse the actions JSON the LLM stored. We don't ship the LLM's WorkflowAction model
 * here — the cron-jobs side stores raw JSON — so we render it best-effort: tool name on
 * the header line, args dump in a "show args" expand.
 */
private fun parseActions(actionsJson: String?): List<ParsedAction> {
    if (actionsJson.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = Json.parseToJsonElement(actionsJson) as? JsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val tool = obj["tool"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val args = obj["args"]?.toString().orEmpty()
            ParsedAction(tool, args)
        }
    }.getOrDefault(emptyList())
}

@Composable
private fun ActionRow(index: Int, action: ParsedAction) {
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
        }
        if (action.argsBlock.isNotBlank() && action.argsBlock != "{}") {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text(
                    if (expanded) stringResource(R.string.setting_page_scheduled_jobs_hide_args)
                    else stringResource(R.string.setting_page_scheduled_jobs_show_args),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (expanded) {
                Text(
                    action.argsBlock,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StatsBlock(job: ScheduledJobEntity) {
    val rel = relativeStrings()
    val nowMs by rememberTickingNowMs()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val lastRun = job.lastRunAtMs?.let {
            stringResource(
                R.string.setting_page_scheduled_jobs_stat_last_run,
                formatRelativeAgo(it, nowMs, rel),
            )
        } ?: stringResource(R.string.setting_page_scheduled_jobs_stat_last_run_never)
        Text(lastRun, style = MaterialTheme.typography.bodySmall)

        val nextRun = job.nextRunAtMs?.let {
            stringResource(
                R.string.setting_page_scheduled_jobs_stat_next_run,
                formatAbsoluteForDetail(it),
            )
        } ?: stringResource(R.string.setting_page_scheduled_jobs_stat_next_run_unscheduled)
        Text(nextRun, style = MaterialTheme.typography.bodySmall)

        val runs = job.maxRuns?.let {
            stringResource(R.string.setting_page_scheduled_jobs_stat_runs_capped, job.runsSoFar, it)
        } ?: stringResource(R.string.setting_page_scheduled_jobs_stat_runs, job.runsSoFar)
        Text(runs, style = MaterialTheme.typography.bodySmall)

        if (job.scheduleType == "cron") {
            Text(
                stringResource(R.string.setting_page_scheduled_jobs_stat_catchup, job.catchup),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
