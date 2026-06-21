package me.rerere.rikkahub.ui.pages.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Date
import java.util.TimeZone
import kotlin.uuid.Uuid
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleSnapshot
import me.rerere.ai.runtime.schedule.RecurrenceSpec
import me.rerere.ai.runtime.schedule.RecurrenceUnit
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormBottomSheet
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.SegmentedButtonLabel
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.ext.plus
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

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
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val spawnableAssistants = settings.assistants.filter { it.spawnable }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var createError by remember { mutableStateOf<CreateScheduleError?>(null) }
    var deleteTarget by remember { mutableStateOf<ScheduleSnapshot?>(null) }
    var selectedPage by rememberSaveable { mutableIntStateOf(0) }

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
        bottomBar = {
            // Bottom tabs (user request): same shape as the speech config page's bottom NavigationBar —
            // "Runs" monitors executions, "Scheduled" manages the definitions.
            NavigationBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor,
            ) {
                NavigationBarItem(
                    selected = selectedPage == 0,
                    onClick = { selectedPage = 0 },
                    icon = { Icon(Lucide.Activity, contentDescription = null) },
                    label = { Text("Runs") },
                )
                NavigationBarItem(
                    selected = selectedPage == 1,
                    onClick = { selectedPage = 1 },
                    icon = { Icon(Lucide.CalendarClock, contentDescription = null) },
                    label = { Text("Scheduled") },
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        // Bottom-tab content (user request): "Runs" monitors task executions (incl. long-running ones
        // live), "Scheduled" manages the schedule definitions. The bottom NavigationBar selects the page;
        // innerPadding already accounts for the top bar and the bottom bar.
        when (selectedPage) {
            0 -> RunsTabContent(
                runs = runs,
                innerPadding = innerPadding,
                onCreate = { showCreateDialog = true },
            )

            else -> ScheduledTabContent(
                state = uiState,
                innerPadding = innerPadding,
                onCreate = { showCreateDialog = true },
                onRetry = { vm.load() },
                onToggleEnabled = { snapshot, enabled ->
                    // Route the toggle through the VM → repository (the single legality path); a resume
                    // that breaches a freed cap is Rejected. The Switch is bound to snapshot.enabled —
                    // which only changes when the list refreshes on Accept — so a Rejected leaves the
                    // switch visually reverted and we toast the reason.
                    vm.setEnabled(snapshot.id, enabled) { result ->
                        if (result is ScheduleMutationResult.Rejected) toaster.show(result.reason)
                    }
                },
                onDelete = { deleteTarget = it },
            )
        }
    }

    if (showCreateDialog) {
        CreateScheduleDialog(
            error = createError,
            assistants = spawnableAssistants,
            defaultAssistantId = targetAssistantId,
            onDismiss = {
                showCreateDialog = false
                createError = null
            },
            // The dialog is NOT dismissed here: a create can be Rejected (over-length prompt, sub-floor
            // interval, bad zone, cap breach), and closing before the result is known destroys the user's
            // input. The dialog stays open until the result arrives — only an Accepted dismisses it; a
            // Rejected keeps it open and surfaces the reason inline (M2 / task T3). Rejection is an
            // EXPECTED domain outcome, so it is shown in the dialog, never toasted.
            onConfirm = { draft ->
                createError = null
                vm.create(draft) { result ->
                    when (result) {
                        is ScheduleMutationResult.Accepted -> {
                            showCreateDialog = false
                            toaster.show("Schedule created")
                        }

                        is ScheduleMutationResult.Rejected ->
                            createError = createScheduleError(result.reason)
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

/**
 * The "Scheduled" tab: the existing four-state schedule list, unchanged except that its padding now
 * comes from the tab host (top inset is consumed by the tab row). Loading is a spinner, Empty a CTA
 * column, Error a retry line, Content the schedule cards.
 */
@Composable
private fun ScheduledTabContent(
    state: ScheduleUiState,
    innerPadding: PaddingValues,
    onCreate: () -> Unit,
    onRetry: () -> Unit,
    onToggleEnabled: (ScheduleSnapshot, Boolean) -> Unit,
    onDelete: (ScheduleSnapshot) -> Unit,
) {
    when (state) {
        is ScheduleUiState.Loading -> ScheduleLoadingState(innerPadding)

        is ScheduleUiState.Empty -> ScheduleEmptyState(innerPadding = innerPadding, onCreate = onCreate)

        is ScheduleUiState.Error -> ScheduleErrorState(
            innerPadding = innerPadding,
            message = state.message,
            onRetry = onRetry,
        )

        is ScheduleUiState.Content -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.schedules, key = { it.id.toString() }) { snapshot ->
                ScheduleCard(
                    snapshot = snapshot,
                    onToggleEnabled = { enabled -> onToggleEnabled(snapshot, enabled) },
                    onDelete = { onDelete(snapshot) },
                )
            }
        }
    }
}

/**
 * The "Runs" tab (user request): a live monitor of the bound conversation's task executions so a
 * long-running scheduled task can be watched. Empty until something has fired; otherwise a newest-first
 * list of run cards whose status updates live (a RUNNING run shows a spinner).
 */
@Composable
private fun RunsTabContent(
    runs: List<TaskRunRow>,
    innerPadding: PaddingValues,
    onCreate: () -> Unit,
) {
    if (runs.isEmpty()) {
        RunsEmptyState(innerPadding = innerPadding, onCreate = onCreate)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = innerPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(runs, key = { it.id }) { run -> RunCard(run = run) }
    }
}

/** The Runs empty state — mirrors [ScheduleEmptyState] but points the CTA at creating a schedule. */
@Composable
private fun RunsEmptyState(
    innerPadding: PaddingValues,
    onCreate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Lucide.Search,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "No runs yet",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(
            onClick = onCreate,
            modifier = Modifier.padding(top = 20.dp),
        ) {
            Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("New schedule", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** One execution row: a live status badge, when it started, the prompt, and the result/error tail. */
@Composable
private fun RunCard(run: TaskRunRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RunStatusBadge(state = run.state)
                Text(
                    text = runTimeLabel(run.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = run.prompt.ifBlank { "(empty prompt)" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = run.finalError ?: run.finalResult
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (run.finalError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Status pill for a run: a spinner for any in-flight (ACTIVE) state so a long-running task is obviously
 * still going, a check for success, an X for a failure, and a neutral mark for cancelled/interrupted.
 */
@Composable
private fun RunStatusBadge(state: TaskRunStateTag) {
    val active = state in TaskRunStateTag.ACTIVE
    val color = when {
        active -> MaterialTheme.colorScheme.primary
        state == TaskRunStateTag.SUCCEEDED -> MaterialTheme.colorScheme.primary
        state == TaskRunStateTag.FAILED || state == TaskRunStateTag.BUDGET_EXHAUSTED ->
            MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (state) {
        TaskRunStateTag.CREATED, TaskRunStateTag.QUEUED -> "Queued"
        TaskRunStateTag.STARTING, TaskRunStateTag.RUNNING, TaskRunStateTag.RESUMING -> "Running"
        TaskRunStateTag.WAITING_APPROVAL -> "Waiting"
        TaskRunStateTag.SUCCEEDED -> "Done"
        TaskRunStateTag.FAILED -> "Failed"
        TaskRunStateTag.CANCELLED -> "Cancelled"
        TaskRunStateTag.BUDGET_EXHAUSTED -> "Budget exhausted"
        TaskRunStateTag.INTERRUPTED -> "Interrupted"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            active -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = color,
            )

            state == TaskRunStateTag.SUCCEEDED -> Icon(
                imageVector = Lucide.CircleCheck,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = color,
            )

            else -> Icon(
                imageVector = Lucide.CircleX,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = color,
            )
        }
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun runTimeLabel(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

/** Centered spinner shown while [ScheduleUiState.Loading] — distinct from the empty state (SC6). */
@Composable
private fun ScheduleLoadingState(innerPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * The empty state (SPEC.md M6 / SC6): an icon, a one-line explanation, and a PRIMARY in-column
 * Create task button so a first-time user has an obvious call to action without hunting for the FAB.
 */
@Composable
private fun ScheduleEmptyState(
    innerPadding: PaddingValues,
    onCreate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
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
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(
            onClick = onCreate,
            modifier = Modifier.padding(top = 20.dp),
        ) {
            Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("New schedule", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * The error state (SPEC.md M6 / SC6): shown only on an UNEXPECTED fault while listing (domain failures
 * are Rejected, never thrown), so the screen surfaces the cause and offers a retry instead of a blank
 * or misleading empty state.
 */
@Composable
private fun ScheduleErrorState(
    innerPadding: PaddingValues,
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Lucide.TriangleAlert,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = "Couldn't load scheduled tasks",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.padding(top = 20.dp),
        ) {
            Text("Retry")
        }
    }
}

@Composable
private fun ScheduleCard(
    snapshot: ScheduleSnapshot,
    onToggleEnabled: (Boolean) -> Unit,
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
                Text(
                    text = scheduleRunState(snapshot),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The Switch is bound to snapshot.enabled — the repository-backed source of truth — so a
            // Rejected resume (which never refreshes the list) leaves it visually reverted; only an
            // Accepted toggle moves it (M5 / task T11). It is the ONLY pause/resume affordance: no
            // direct flag flip, every toggle re-checks caps and arms/cancels the fire in the repository.
            Switch(
                checked = snapshot.enabled,
                onCheckedChange = onToggleEnabled,
            )
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

/**
 * The card's run-state line (SPEC.md M5 / task T11), read straight from the snapshot the repository
 * publishes: a live fire (`runningTaskRunId != null`) reads "Running now"; otherwise the last fire
 * (`lastFiredAt`) or, if it has never fired, "Not run yet". This is display-only — the firing truth
 * lives in the repository's claim path, never recomputed here.
 */
private fun scheduleRunState(snapshot: ScheduleSnapshot): String {
    if (snapshot.runningTaskRunId != null) return "Running now"
    val lastFiredAt = snapshot.lastFiredAt ?: return "Not run yet"
    val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).apply {
        timeZone = runCatching { TimeZone.getTimeZone(snapshot.timeZoneId) }.getOrDefault(TimeZone.getDefault())
    }
    return "Last run ${df.format(Date(lastFiredAt))}"
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

/**
 * Production create form (SPEC.md M3 / task T8). The dialog OWNS its field state via
 * [rememberSaveable] primitives and projects them into a pure [ScheduleFormState] for the live
 * [scheduleSummary] preview and the M4 submit gate — the VM stays the legality conduit, the dialog
 * decides display (see the [onConfirm] comment at the call site).
 *
 * Controls, each grounded in the data model:
 *  - segmented kind (One-shot / Recurring) and unit (minutes / hours / days);
 *  - a -/+ stepper whose floor is [minEveryFor] for the chosen unit, so it can never produce a
 *    sub-floor draft the repository rejects (and re-clamps `every` up when the unit switches to a
 *    coarser floor, e.g. HOURS→MINUTES forces `every` to at least 15);
 *  - an M3 date + time picker for `firstFireAt` (replaces the old blind `now + 1h`);
 *  - an M3 time picker for the daily `timeOfDay`, shown only for DAYS (Recurrence ignores it
 *    otherwise — Recurrence.kt:34);
 *  - an editable, searchable IANA timezone picker defaulting to the device zone;
 *  - a LIVE summary line computed by [scheduleSummary] — the same `Recurrence.nextOccurrenceAfter`
 *    the worker fires, so the preview equals reality (SC4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateScheduleDialog(
    error: CreateScheduleError?,
    assistants: List<Assistant>,
    defaultAssistantId: Uuid,
    onDismiss: () -> Unit,
    onConfirm: (ScheduleDraft) -> Unit,
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(ScheduleKind.ONE_SHOT) }
    var unit by rememberSaveable { mutableStateOf(RecurrenceUnit.HOURS) }
    var every by rememberSaveable { mutableIntStateOf(1) }
    // Seed first fire one hour out so the user edits a future, valid instant, not a past one.
    var firstFireAt by rememberSaveable { mutableLongStateOf(System.currentTimeMillis() + 60 * 60 * 1000) }
    var timeOfDay by rememberSaveable { mutableStateOf<String?>(null) }
    var timeZoneId by rememberSaveable { mutableStateOf(TimeZone.getDefault().id) }
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    var targetAssistantId by rememberSaveable {
        mutableStateOf(resolveScheduleTarget(assistants, defaultAssistantId))
    }
    // The spawnable list arrives asynchronously (settingsFlow seeds with dummy/empty first), and an
    // assistant can lose `spawnable` while the dialog is open. Re-seed whenever the current target is
    // unset or no longer in the spawnable list, so an untouched picker resolves to a valid default
    // instead of sticking at Uuid.NIL (which would keep Create permanently disabled). A target the
    // user explicitly picked that is still spawnable is preserved (it stays in `assistants`).
    LaunchedEffect(assistants) {
        if (targetAssistantId == Uuid.NIL || assistants.none { it.id == targetAssistantId }) {
            targetAssistantId = resolveScheduleTarget(assistants, defaultAssistantId)
        }
    }
    val selectedTargetName = assistants
        .firstOrNull { it.id == targetAssistantId }
        ?.name
        ?.ifEmpty { defaultAssistantName }
        ?: defaultAssistantName

    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showTimeOfDayPicker by rememberSaveable { mutableStateOf(false) }
    var showTimeZonePicker by rememberSaveable { mutableStateOf(false) }
    var showAssistantPicker by remember { mutableStateOf(false) }

    val now = System.currentTimeMillis()
    val formState = ScheduleFormState(
        prompt = prompt,
        kind = kind,
        every = every,
        unit = unit,
        firstFireAt = firstFireAt,
        targetAssistantId = targetAssistantId,
        timeOfDay = timeOfDay.takeIf { kind == ScheduleKind.RECURRING && unit == RecurrenceUnit.DAYS },
        timeZoneId = timeZoneId,
    )
    val validationErrors = formState.validate(now)
    val previewSpec = if (kind == ScheduleKind.RECURRING) {
        RecurrenceSpec(every = every, unit = unit, timeOfDay = formState.timeOfDay)
    } else null
    val summary = runCatching {
        scheduleSummary(
            kind = kind,
            spec = previewSpec,
            firstFireAt = firstFireAt,
            timeZoneId = timeZoneId,
            now = now,
        )
    }.getOrNull()

    val displayZone = runCatching { ZoneId.of(timeZoneId) }.getOrDefault(ZoneId.systemDefault())
    val fireDateTimeLabel = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).apply {
        timeZone = TimeZone.getTimeZone(displayZone)
    }.format(Date(firstFireAt))

    FormBottomSheet(
        title = "New scheduled task",
        onDismiss = onDismiss,
        footer = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(
                // Create is enabled iff the form mirrors every repository gate cleanly (SC3): an empty
                // validate() == submittable, so the button cannot offer a draft the repository rejects.
                enabled = validationErrors.isEmpty(),
                // The dialog projects its live formState through the SAME toDraft() the SC3 invariant
                // test exercises — one form→draft mapping, no hand-rolled duplicate that could drift.
                onClick = { onConfirm(formState.toDraft()) },
            ) {
                Text("Create")
            }
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Prompt") },
                    minLines = 3,
                    maxLines = 6,
                    isError = error?.field == CreateScheduleField.PROMPT ||
                        validationErrors.containsKey(ScheduleField.PROMPT),
                    supportingText = fieldSupportingText(
                        repositoryError = error?.takeIf { it.field == CreateScheduleField.PROMPT }?.message,
                        validationError = validationErrors[ScheduleField.PROMPT],
                    ),
                )

                FormItem(
                    label = { Text("Assistant") },
                    description = {
                        if (assistants.isEmpty()) {
                            Text("No spawnable assistant — enable 'spawnable' on an assistant")
                        }
                    },
                ) {
                    OutlinedButton(onClick = { showAssistantPicker = true }, enabled = assistants.isNotEmpty()) {
                        Text(selectedTargetName)
                    }
                    validationErrors[ScheduleField.ASSISTANT]?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                FormItem(label = { Text("Repeat") }) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val kinds = ScheduleKind.entries
                        kinds.forEachIndexed { index, candidate ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                                selected = kind == candidate,
                                onClick = { kind = candidate },
                            ) {
                                SegmentedButtonLabel(if (candidate == ScheduleKind.ONE_SHOT) "One-shot" else "Recurring")
                            }
                        }
                    }
                }

                if (kind == ScheduleKind.RECURRING) {
                    FormItem(label = { Text("Unit") }) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val units = RecurrenceUnit.entries
                            units.forEachIndexed { index, candidate ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index, units.size),
                                    selected = unit == candidate,
                                    onClick = {
                                        unit = candidate
                                        // Re-clamp up so switching to a coarser floor (e.g. → MINUTES,
                                        // floor 15) can never leave `every` sitting below the new floor.
                                        every = maxOf(every, minEveryFor(candidate))
                                    },
                                ) {
                                    SegmentedButtonLabel(candidate.name.lowercase())
                                }
                            }
                        }
                    }

                    FormItem(
                        label = { Text("Every") },
                        description = {
                            val floor = minEveryFor(unit)
                            if (floor > 1) Text("Minimum $floor for ${unit.name.lowercase()}")
                        },
                    ) {
                        Stepper(
                            value = every,
                            min = minEveryFor(unit),
                            onValueChange = { every = it },
                            isError = error?.field == CreateScheduleField.EVERY ||
                                validationErrors.containsKey(ScheduleField.EVERY),
                        )
                        validationErrors[ScheduleField.EVERY]?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }

                    if (unit == RecurrenceUnit.DAYS) {
                        FormItem(
                            label = { Text("Time of day") },
                            description = { Text("Optional — fires at this local time") },
                            tail = {
                                OutlinedButton(onClick = { showTimeOfDayPicker = true }) {
                                    Text(timeOfDay ?: "Anchor time")
                                }
                            },
                        )
                    }
                }

                FormItem(
                    label = { Text(if (kind == ScheduleKind.ONE_SHOT) "Fire at" else "First fire") },
                    tail = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showDatePicker = true }) { Text("Date") }
                            OutlinedButton(onClick = { showTimePicker = true }) { Text("Time") }
                        }
                    },
                ) {
                    Text(fireDateTimeLabel, style = MaterialTheme.typography.bodyMedium)
                }

                FormItem(
                    label = { Text("Timezone") },
                    tail = {
                        OutlinedButton(onClick = { showTimeZonePicker = true }) { Text("Change") }
                    },
                ) {
                    Text(
                        text = timeZoneId,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (validationErrors.containsKey(ScheduleField.TIMEZONE)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                summary?.let {
                    HorizontalDivider()
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // Dialog-level reasons (cap breach, unrecognized) and any repository timezone reason
                // surface here so no rejection is silently dropped (field-mapped reasons render inline above).
                error
                    ?.takeIf { it.field == CreateScheduleField.NONE || it.field == CreateScheduleField.TIMEZONE }
                    ?.let {
                        Text(
                            text = it.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
            }
        }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(firstFireAt).atZone(displayZone)
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { utcMidnight ->
                        // DatePicker reports UTC-midnight of the picked day; recombine that calendar date
                        // with the existing local clock time in the schedule's zone.
                        val pickedDate = Instant.ofEpochMilli(utcMidnight).atZone(ZoneOffset.UTC).toLocalDate()
                        firstFireAt = combineDateTime(pickedDate, currentLocalTime(firstFireAt, displayZone), displayZone)
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val localTime = currentLocalTime(firstFireAt, displayZone)
        val timePickerState = rememberTimePickerState(
            initialHour = localTime.hour, initialMinute = localTime.minute, is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val pickedDate = Instant.ofEpochMilli(firstFireAt).atZone(displayZone).toLocalDate()
                    firstFireAt = combineDateTime(
                        pickedDate, LocalTime.of(timePickerState.hour, timePickerState.minute), displayZone,
                    )
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
            text = { TimePicker(state = timePickerState) },
        )
    }

    if (showTimeOfDayPicker) {
        val seed = timeOfDay?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(9, 0)
        val timePickerState = rememberTimePickerState(
            initialHour = seed.hour, initialMinute = seed.minute, is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimeOfDayPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    timeOfDay = "%02d:%02d".format(timePickerState.hour, timePickerState.minute)
                    showTimeOfDayPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimeOfDayPicker = false }) { Text("Cancel") } },
            text = { TimePicker(state = timePickerState) },
        )
    }

    if (showTimeZonePicker) {
        TimeZonePickerDialog(
            current = timeZoneId,
            onPick = { timeZoneId = it; showTimeZonePicker = false },
            onDismiss = { showTimeZonePicker = false },
        )
    }

    if (showAssistantPicker) {
        ScheduleAssistantPickerSheet(
            assistants = assistants,
            selectedId = targetAssistantId,
            onSelected = {
                targetAssistantId = it.id
                showAssistantPicker = false
            },
            onDismiss = {
                showAssistantPicker = false
            },
        )
    }
}

/**
 * The delegation target an untouched picker should resolve to: the screen's assistant when it is
 * spawnable, else the first spawnable assistant, else [Uuid.NIL] (no spawnable assistant exists,
 * which the form surfaces as a blocking validation error).
 */
private fun resolveScheduleTarget(assistants: List<Assistant>, defaultAssistantId: Uuid): Uuid = when {
    assistants.isEmpty() -> Uuid.NIL
    assistants.any { it.id == defaultAssistantId } -> defaultAssistantId
    else -> assistants.firstOrNull()?.id ?: defaultAssistantId
}

/** Assistant picker used only for schedule delegation; intentionally side-effect free. */
@Composable
private fun ScheduleAssistantPickerSheet(
    assistants: List<Assistant>,
    selectedId: Uuid,
    onSelected: (Assistant) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Select assistant", style = MaterialTheme.typography.titleLarge)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(assistants, key = { it.id }) { assistant ->
                    val checked = assistant.id == selectedId
                    Card(
                        onClick = { onSelected(assistant) },
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (checked) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            contentColor = if (checked) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        ),
                    ) {
                        ListItem(
                            leadingContent = {
                                UIAvatar(
                                    name = assistant.name.ifEmpty { defaultAssistantName },
                                    value = assistant.avatar,
                                    modifier = Modifier.size(32.dp),
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = assistant.name.ifEmpty { defaultAssistantName },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = { if (checked) Text("Selected") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** -/+ stepper bound at [min] (the unit's legal floor) so it can never emit a sub-floor value. */
@Composable
private fun Stepper(
    value: Int,
    min: Int,
    onValueChange: (Int) -> Unit,
    isError: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onValueChange(maxOf(min, value - 1)) },
            enabled = value > min,
        ) {
            Icon(Lucide.Minus, contentDescription = "Decrease")
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = { onValueChange(value + 1) }) {
            Icon(Lucide.Plus, contentDescription = "Increase")
        }
    }
}

/**
 * Searchable IANA timezone picker (SPEC.md M3 / task T8, Assumption 8). The full
 * [ZoneId.getAvailableZoneIds] list is offered with a free-text filter so no zone the user needs is
 * hidden behind curation; the current selection is preselected.
 */
@Composable
private fun TimeZonePickerDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val allZones = remember { ZoneId.getAvailableZoneIds().sorted() }
    val filtered = remember(query) {
        if (query.isBlank()) allZones else allZones.filter { it.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Timezone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search") },
                    singleLine = true,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it }) { zone ->
                        TextButton(
                            onClick = { onPick(zone) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = zone,
                                modifier = Modifier.fillMaxWidth(),
                                color = if (zone == current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A red `supportingText` slot preferring the repository's verbatim reason, else the mirrored guard. */
private fun fieldSupportingText(
    repositoryError: String?,
    validationError: String?,
): (@Composable () -> Unit)? {
    val message = repositoryError ?: validationError ?: return null
    return { Text(message, color = MaterialTheme.colorScheme.error) }
}

/** The local wall-clock time of [millis] in [zone] — the time part the date picker must preserve. */
private fun currentLocalTime(millis: Long, zone: ZoneId): LocalTime =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()

/** Recombine a calendar [date] and wall [time] into an epoch-millis instant in [zone]. */
private fun combineDateTime(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
    ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
