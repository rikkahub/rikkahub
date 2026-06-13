package me.rerere.rikkahub.ui.pages.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleSnapshot
import me.rerere.ai.runtime.schedule.RecurrenceSpec
import me.rerere.ai.runtime.schedule.RecurrenceUnit
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.ext.plus
import me.rerere.rikkahub.ui.theme.CustomColors
import kotlinx.serialization.json.Json
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import kotlin.uuid.Uuid

/**
 * Minimal create / list / delete UI for one conversation's task schedules (SPEC.md M5 / task T10).
 * Every mutation flows through [ScheduleVM], which WRITES through the same [TaskScheduleRepository]
 * the schedule tools use — there is no UI-only legality path. Strings are English-only placeholders
 * (CLAUDE.md i18n rule: no localization unless explicitly requested).
 */
@Composable
fun SchedulePage(
    targetAssistantId: Uuid,
    conversationId: Uuid? = null,
    vm: ScheduleVM = koinViewModel { parametersOf(targetAssistantId, conversationId) },
) {
    val toaster = LocalToaster.current
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ScheduleSnapshot?>(null) }

    LaunchedEffect(vm) { vm.load() }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Scheduled tasks") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Lucide.Plus, contentDescription = "Add schedule")
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (schedules.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Lucide.CalendarClock,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "No scheduled tasks yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(schedules, key = { it.id.toString() }) { snapshot ->
                ScheduleCard(
                    snapshot = snapshot,
                    onDelete = { deleteTarget = snapshot },
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateScheduleDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { draft ->
                showCreateDialog = false
                vm.create(draft) { result ->
                    when (result) {
                        is ScheduleMutationResult.Accepted -> toaster.show("Schedule created")
                        is ScheduleMutationResult.Rejected -> toaster.show(result.reason)
                    }
                }
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = "Delete schedule",
        confirmText = "Delete",
        dismissText = "Cancel",
        onConfirm = {
            deleteTarget?.let { snapshot ->
                vm.delete(snapshot.id) { result ->
                    if (result is ScheduleMutationResult.Rejected) toaster.show(result.reason)
                }
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text("This scheduled task will be removed and will not fire again.")
    }
}

@Composable
private fun ScheduleCard(
    snapshot: ScheduleSnapshot,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Lucide.CalendarClock,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = snapshot.prompt.ifBlank { "(empty prompt)" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = scheduleSubtitle(snapshot),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Lucide.Trash2,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun scheduleSubtitle(snapshot: ScheduleSnapshot): String {
    val kindLabel = when (snapshot.kind) {
        ScheduleKind.ONE_SHOT -> "One-shot"
        ScheduleKind.RECURRING -> "Recurring"
    }
    val state = if (snapshot.enabled) "enabled" else "disabled"
    val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).apply {
        timeZone = runCatching { TimeZone.getTimeZone(snapshot.timeZoneId) }.getOrDefault(TimeZone.getDefault())
    }
    return "$kindLabel · next ${df.format(Date(snapshot.nextFireAt))} · $state"
}

@Composable
private fun CreateScheduleDialog(
    onDismiss: () -> Unit,
    onConfirm: (ScheduleDraft) -> Unit,
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var recurring by rememberSaveable { mutableStateOf(false) }
    var everyText by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableStateOf(RecurrenceUnit.HOURS) }
    // Default first fire one hour out so the user is editing a future, valid time, not a past one.
    val firstFireAt = remember { System.currentTimeMillis() + 60 * 60 * 1000 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New scheduled task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Prompt") },
                    minLines = 3,
                    maxLines = 6,
                )
                FormItem(
                    label = { Text("Repeat") },
                    description = { Text("Run once, or on a fixed cadence") },
                    tail = {
                        FilterChip(
                            selected = recurring,
                            onClick = { recurring = !recurring },
                            label = { Text(if (recurring) "Recurring" else "One-shot") },
                        )
                    },
                )
                if (recurring) {
                    FormItem(label = { Text("Every") }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = everyText,
                                onValueChange = { everyText = it.filter(Char::isDigit) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            RecurrenceUnit.entries.forEach { candidate ->
                                FilterChip(
                                    selected = unit == candidate,
                                    onClick = { unit = candidate },
                                    label = { Text(candidate.name.lowercase()) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = prompt.isNotBlank() && (!recurring || (everyText.toIntOrNull() ?: 0) >= 1),
                onClick = {
                    val kind = if (recurring) ScheduleKind.RECURRING else ScheduleKind.ONE_SHOT
                    val spec = if (recurring) {
                        Json.encodeToString(RecurrenceSpec(every = everyText.toInt(), unit = unit))
                    } else null
                    onConfirm(
                        ScheduleDraft(
                            targetAssistantId = Uuid.NIL, // bound by the VM to the screen's target assistant
                            prompt = prompt.trim(),
                            kind = kind,
                            firstFireAt = firstFireAt,
                            timeZoneId = TimeZone.getDefault().id,
                            recurrenceSpec = spec,
                        )
                    )
                },
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
