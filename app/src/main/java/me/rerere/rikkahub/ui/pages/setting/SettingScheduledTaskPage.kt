package me.rerere.rikkahub.ui.pages.setting

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolCatalog
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.ScheduleType
import me.rerere.rikkahub.data.model.ScheduledPromptTask
import me.rerere.rikkahub.data.model.TaskRunStatus
import me.rerere.rikkahub.service.ScheduledTaskKeepAliveService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.utils.toLocalDateTime
import me.rerere.search.SearchServiceOptions
import java.time.DayOfWeek
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale
import kotlin.uuid.Uuid

private enum class NullableBooleanOption {
    FOLLOW,
    ENABLED,
    DISABLED,
}

private data class ModelOverrideOption(
    val id: Uuid?,
    val title: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScheduledTaskPage(vm: SettingVM = org.koin.androidx.compose.koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var editingTask by remember { mutableStateOf<ScheduledPromptTask?>(null) }
    var exactAlarmGranted by remember { mutableStateOf(context.canScheduleExactAlarmsCompat()) }

    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setOf(PermissionNotification)
        } else {
            emptySet()
        }
    )
    PermissionManager(permissionState = permissionState)
    var pendingEnableKeepAlive by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                exactAlarmGranted = context.canScheduleExactAlarmsCompat()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun setKeepAlive(enabled: Boolean) {
        scope.launch {
            vm.updateSettings(settings.copy(scheduledTaskKeepAliveEnabled = enabled))
        }
        val intent = Intent(context, ScheduledTaskKeepAliveService::class.java).apply {
            action = if (enabled) {
                ScheduledTaskKeepAliveService.ACTION_START
            } else {
                ScheduledTaskKeepAliveService.ACTION_STOP
            }
        }
        if (enabled) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    LaunchedEffect(permissionState.allPermissionsGranted, pendingEnableKeepAlive) {
        if (pendingEnableKeepAlive && permissionState.allPermissionsGranted) {
            pendingEnableKeepAlive = false
            setKeepAlive(true)
        }
    }

    fun saveTask(task: ScheduledPromptTask) {
        val assistantIds = settings.assistants.map { it.id }.toSet()
        val defaultAssistantId = settings.getCurrentAssistant().id
        val maxSearchIndex = settings.searchServices.lastIndex
        val normalized = task.copy(
            title = task.title.trim().ifBlank {
                task.prompt.lineSequence().firstOrNull().orEmpty().take(24)
            },
            prompt = task.prompt.trim(),
            dayOfWeek = if (task.scheduleType == ScheduleType.WEEKLY) {
                task.dayOfWeek ?: DayOfWeek.MONDAY.value
            } else {
                null
            },
            assistantId = if (task.assistantId in assistantIds) task.assistantId else defaultAssistantId,
            overrideMcpServers = task.overrideMcpServers
                ?.filter { id -> settings.mcpServers.any { it.id == id } }
                ?.toSet(),
            overrideSearchServiceIndex = task.overrideSearchServiceIndex?.let { index ->
                if (settings.searchServices.isEmpty()) {
                    null
                } else {
                    index.coerceIn(0, maxSearchIndex)
                }
            }
        )
        val hasSameId = settings.scheduledTasks.any { it.id == normalized.id }
        val nextTasks = if (hasSameId) {
            settings.scheduledTasks.map { if (it.id == normalized.id) normalized else it }
        } else {
            settings.scheduledTasks + normalized
        }
        vm.updateSettings(settings.copy(scheduledTasks = nextTasks))
        editingTask = null
    }

    fun deleteTask(taskId: Uuid) {
        vm.updateSettings(settings.copy(scheduledTasks = settings.scheduledTasks.filter { it.id != taskId }))
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.setting_scheduled_tasks_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingTask = ScheduledPromptTask(
                        assistantId = settings.getCurrentAssistant().id,
                        dayOfWeek = DayOfWeek.MONDAY.value
                    )
                }
            ) {
                Icon(Lucide.Plus, contentDescription = stringResource(R.string.assistant_schedule_add_task))
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.setting_scheduled_tasks_keep_alive_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_scheduled_tasks_keep_alive_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.scheduledTaskKeepAliveEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled &&
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        !permissionState.allPermissionsGranted
                                    ) {
                                        pendingEnableKeepAlive = true
                                        permissionState.requestPermissions()
                                    } else {
                                        pendingEnableKeepAlive = false
                                        setKeepAlive(enabled)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.setting_scheduled_tasks_exact_alarm_title)) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.setting_scheduled_tasks_exact_alarm_desc))
                                    Text(
                                        if (exactAlarmGranted) {
                                            stringResource(R.string.setting_scheduled_tasks_exact_alarm_granted)
                                        } else {
                                            stringResource(R.string.setting_scheduled_tasks_exact_alarm_not_granted)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (exactAlarmGranted) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    )
                                }
                            },
                            trailingContent = {
                                if (!exactAlarmGranted) {
                                    TextButton(
                                        onClick = { openExactAlarmSettings(context) }
                                    ) {
                                        Text(stringResource(R.string.setting_scheduled_tasks_exact_alarm_action))
                                    }
                                }
                            }
                        )
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.assistant_schedule_summary_title)) },
                        supportingContent = {
                            val enabledCount = settings.scheduledTasks.count { it.enabled }
                            Text(stringResource(R.string.assistant_schedule_summary_desc, enabledCount))
                        }
                    )
                }
            }

            if (settings.scheduledTasks.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.assistant_schedule_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
                    )
                }
            } else {
                items(settings.scheduledTasks, key = { it.id.toString() }) { task ->
                    ScheduledTaskCard(
                        task = task,
                        settings = settings,
                        onToggleEnabled = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAlarmGranted) {
                                openExactAlarmSettings(context)
                            }
                            saveTask(task.copy(enabled = enabled))
                        },
                        onEdit = { editingTask = task },
                        onDelete = { deleteTask(task.id) }
                    )
                }
            }
        }
    }

    editingTask?.let { task ->
        TaskEditorSheet(
            task = task,
            settings = settings,
            onDismiss = { editingTask = null },
            onSave = { saveTask(it) }
        )
    }
}

@Composable
private fun ScheduledTaskCard(
    task: ScheduledPromptTask,
    settings: Settings,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val assistantName = settings.getAssistantById(task.assistantId)?.name?.ifBlank {
        stringResource(R.string.assistant_page_default_assistant)
    } ?: stringResource(R.string.assistant_page_default_assistant)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = {
                    Text(
                        text = task.title.ifBlank { stringResource(R.string.assistant_schedule_untitled) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = task.prompt.replace("\n", " "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(R.string.setting_scheduled_tasks_assistant_line, assistantName),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = task.scheduleSummary(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = task.statusSummary(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                trailingContent = {
                    Switch(
                        checked = task.enabled,
                        onCheckedChange = onToggleEnabled
                    )
                }
            )
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onEdit) {
                    Icon(Lucide.Pencil, contentDescription = stringResource(R.string.assistant_schedule_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(Lucide.Trash2, contentDescription = stringResource(R.string.assistant_schedule_delete))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskEditorSheet(
    task: ScheduledPromptTask,
    settings: Settings,
    onDismiss: () -> Unit,
    onSave: (ScheduledPromptTask) -> Unit,
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var prompt by remember(task.id) { mutableStateOf(task.prompt) }
    var assistantId by remember(task.id) {
        mutableStateOf(task.assistantId.takeIf { id -> settings.assistants.any { it.id == id } } ?: settings.getCurrentAssistant().id)
    }
    var scheduleType by remember(task.id) { mutableStateOf(task.scheduleType) }
    var timeMinutesOfDay by remember(task.id) { mutableStateOf(task.timeMinutesOfDay.coerceIn(0, 1439)) }
    var dayOfWeek by remember(task.id) { mutableStateOf(task.dayOfWeek ?: DayOfWeek.MONDAY.value) }
    var showTimePicker by remember(task.id) { mutableStateOf(false) }

    var modelOverrideId by remember(task.id) { mutableStateOf(task.overrideModelId) }
    var localToolsOverrideEnabled by remember(task.id) { mutableStateOf(task.overrideLocalTools != null) }
    var localToolsOverride by remember(task.id) {
        mutableStateOf(task.overrideLocalTools ?: settings.getAssistantById(assistantId)?.localTools.orEmpty())
    }
    var mcpOverrideEnabled by remember(task.id) { mutableStateOf(task.overrideMcpServers != null) }
    var mcpOverride by remember(task.id) {
        mutableStateOf(task.overrideMcpServers ?: settings.getAssistantById(assistantId)?.mcpServers.orEmpty())
    }
    var webSearchOverride by remember(task.id) {
        mutableStateOf(
            when (task.overrideEnableWebSearch) {
                true -> NullableBooleanOption.ENABLED
                false -> NullableBooleanOption.DISABLED
                null -> NullableBooleanOption.FOLLOW
            }
        )
    }
    var searchServiceOverrideEnabled by remember(task.id) { mutableStateOf(task.overrideSearchServiceIndex != null) }
    var searchServiceOverrideIndex by remember(task.id) {
        mutableStateOf(
            task.overrideSearchServiceIndex?.coerceIn(
                0,
                settings.searchServices.lastIndex.coerceAtLeast(0)
            ) ?: settings.searchServiceSelected.coerceIn(0, settings.searchServices.lastIndex.coerceAtLeast(0))
        )
    }
    var termuxApprovalOverride by remember(task.id) {
        mutableStateOf(
            when (task.overrideTermuxNeedsApproval) {
                true -> NullableBooleanOption.ENABLED
                false -> NullableBooleanOption.DISABLED
                null -> NullableBooleanOption.FOLLOW
            }
        )
    }

    val assistants = remember(settings.assistants) { settings.assistants }
    val selectedAssistant = settings.getAssistantById(assistantId) ?: settings.getCurrentAssistant()
    val models = remember(settings.providers) {
        settings.providers.flatMap { it.models }.distinctBy { it.id }
    }
    val useAssistantModelText = stringResource(R.string.setting_scheduled_tasks_use_assistant_model)
    val modelOptions = remember(models, useAssistantModelText) {
        buildList {
            add(ModelOverrideOption(id = null, title = useAssistantModelText))
            models.forEach { model ->
                add(
                    ModelOverrideOption(
                        id = model.id,
                        title = model.displayName.ifBlank { model.modelId.ifBlank { model.id.toString() } }
                    )
                )
            }
        }
    }
    val selectedModelOption = modelOptions.find { it.id == modelOverrideId } ?: modelOptions.first()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.assistant_schedule_editor_title),
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.assistant_schedule_task_title)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(stringResource(R.string.assistant_schedule_task_prompt)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )

            if (selectedAssistant.quickMessages.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.assistant_schedule_insert_quick_message),
                    style = MaterialTheme.typography.labelMedium
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedAssistant.quickMessages
                        .map { it.content }
                        .filter { it.isNotBlank() }
                        .take(8)
                        .forEach { quickMessage ->
                            AssistChip(
                                onClick = {
                                    prompt = if (prompt.isBlank()) {
                                        quickMessage
                                    } else {
                                        "$prompt\n$quickMessage"
                                    }
                                },
                                label = {
                                    Text(
                                        text = quickMessage.replace("\n", " ").take(18),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                }
            }

            Text(stringResource(R.string.setting_scheduled_tasks_assistant), style = MaterialTheme.typography.labelMedium)
            Select(
                options = assistants,
                selectedOption = selectedAssistant,
                onOptionSelected = { assistantId = it.id },
                optionToString = {
                    it.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
                },
                modifier = Modifier.fillMaxWidth()
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(ScheduleType.DAILY, ScheduleType.WEEKLY)
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        onClick = { scheduleType = option },
                        selected = scheduleType == option
                    ) {
                        Text(
                            text = if (option == ScheduleType.DAILY) {
                                stringResource(R.string.assistant_schedule_daily)
                            } else {
                                stringResource(R.string.assistant_schedule_weekly)
                            }
                        )
                    }
                }
            }

            TextButton(onClick = { showTimePicker = true }) {
                Icon(Lucide.Clock, contentDescription = null)
                Text(
                    text = stringResource(R.string.assistant_schedule_time_at, formatTime(timeMinutesOfDay)),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            if (scheduleType == ScheduleType.WEEKLY) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DayOfWeek.values().forEach { day ->
                        FilterChip(
                            selected = dayOfWeek == day.value,
                            onClick = { dayOfWeek = day.value },
                            label = { Text(day.displayName()) }
                        )
                    }
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.setting_scheduled_tasks_override), style = MaterialTheme.typography.titleSmall)

            Text(stringResource(R.string.setting_scheduled_tasks_model), style = MaterialTheme.typography.labelMedium)
            Select(
                options = modelOptions,
                selectedOption = selectedModelOption,
                onOptionSelected = { modelOverrideId = it.id },
                optionToString = { it.title },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.setting_scheduled_tasks_override_local_tools), style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = localToolsOverrideEnabled,
                    onCheckedChange = { localToolsOverrideEnabled = it }
                )
            }
            if (localToolsOverrideEnabled) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LocalToolCatalog.options.forEach { option ->
                        FilterChip(
                            selected = localToolsOverride.contains(option),
                            onClick = {
                                localToolsOverride = if (localToolsOverride.contains(option)) {
                                    localToolsOverride - option
                                } else {
                                    (localToolsOverride + option).distinct()
                                }
                            },
                            label = { Text(stringResource(LocalToolCatalog.get(option).titleRes)) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.setting_scheduled_tasks_override_mcp_servers), style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = mcpOverrideEnabled,
                    onCheckedChange = { mcpOverrideEnabled = it }
                )
            }
            if (mcpOverrideEnabled) {
                val enabledServers = settings.mcpServers.filter { it.commonOptions.enable }
                if (enabledServers.isEmpty()) {
                    Text(stringResource(R.string.setting_scheduled_tasks_no_mcp_server_enabled), style = MaterialTheme.typography.bodySmall)
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (server in enabledServers) {
                            FilterChip(
                                selected = mcpOverride.contains(server.id),
                                onClick = {
                                    mcpOverride = if (mcpOverride.contains(server.id)) {
                                        mcpOverride - server.id
                                    } else {
                                        mcpOverride + server.id
                                    }
                                },
                                label = { Text(server.commonOptions.name.ifBlank { server.id.toString() }) }
                            )
                        }
                    }
                }
            }

            Text(stringResource(R.string.setting_scheduled_tasks_web_search), style = MaterialTheme.typography.labelMedium)
            Select(
                options = NullableBooleanOption.entries,
                selectedOption = webSearchOverride,
                onOptionSelected = { webSearchOverride = it },
                optionToString = { boolOverrideTitle(it) },
                modifier = Modifier.fillMaxWidth()
            )

            if (webSearchOverride == NullableBooleanOption.ENABLED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.setting_scheduled_tasks_override_search_service), style = MaterialTheme.typography.labelMedium)
                    Switch(
                        checked = searchServiceOverrideEnabled,
                        onCheckedChange = { searchServiceOverrideEnabled = it }
                    )
                }
                if (searchServiceOverrideEnabled && settings.searchServices.isNotEmpty()) {
                    val indexes = settings.searchServices.indices.toList()
                    Select(
                        options = indexes,
                        selectedOption = searchServiceOverrideIndex.coerceIn(indexes.first(), indexes.last()),
                        onOptionSelected = { searchServiceOverrideIndex = it },
                        optionToString = { index ->
                            searchServiceName(settings.searchServices[index])
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Text(stringResource(R.string.setting_scheduled_tasks_termux_approval), style = MaterialTheme.typography.labelMedium)
            Select(
                options = NullableBooleanOption.entries,
                selectedOption = termuxApprovalOverride,
                onOptionSelected = { termuxApprovalOverride = it },
                optionToString = { boolOverrideTitle(it) },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.assistant_schedule_cancel))
                }
                TextButton(
                    onClick = {
                        if (prompt.isBlank()) return@TextButton
                        onSave(
                            task.copy(
                                title = title.trim(),
                                prompt = prompt.trim(),
                                assistantId = assistantId,
                                scheduleType = scheduleType,
                                timeMinutesOfDay = timeMinutesOfDay.coerceIn(0, 1439),
                                dayOfWeek = if (scheduleType == ScheduleType.WEEKLY) dayOfWeek else null,
                                overrideModelId = modelOverrideId,
                                overrideLocalTools = if (localToolsOverrideEnabled) {
                                    localToolsOverride
                                } else {
                                    null
                                },
                                overrideMcpServers = if (mcpOverrideEnabled) {
                                    mcpOverride
                                } else {
                                    null
                                },
                                overrideEnableWebSearch = when (webSearchOverride) {
                                    NullableBooleanOption.FOLLOW -> null
                                    NullableBooleanOption.ENABLED -> true
                                    NullableBooleanOption.DISABLED -> false
                                },
                                overrideSearchServiceIndex = if (searchServiceOverrideEnabled) {
                                    searchServiceOverrideIndex
                                } else {
                                    null
                                },
                                overrideTermuxNeedsApproval = when (termuxApprovalOverride) {
                                    NullableBooleanOption.FOLLOW -> null
                                    NullableBooleanOption.ENABLED -> true
                                    NullableBooleanOption.DISABLED -> false
                                }
            )
        )
    }
                ) {
                    Text(stringResource(R.string.assistant_schedule_save))
                }
            }
        }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = timeMinutesOfDay / 60,
            initialMinute = timeMinutesOfDay % 60,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        timeMinutesOfDay = timeState.hour * 60 + timeState.minute
                        showTimePicker = false
                    }
                ) {
                    Text(stringResource(R.string.assistant_schedule_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.assistant_schedule_cancel))
                }
            }
        )
    }
}

@Composable
private fun ScheduledPromptTask.scheduleSummary(): String {
    val time = formatTime(timeMinutesOfDay)
    return when (scheduleType) {
        ScheduleType.DAILY -> stringResource(R.string.assistant_schedule_summary_daily, time)
        ScheduleType.WEEKLY -> {
            val day = runCatching {
                DayOfWeek.of(dayOfWeek ?: DayOfWeek.MONDAY.value).displayName()
            }.getOrElse { DayOfWeek.MONDAY.displayName() }
            stringResource(R.string.assistant_schedule_summary_weekly, day, time)
        }
    }
}

@Composable
private fun ScheduledPromptTask.statusSummary(): String {
    val status = when (lastStatus) {
        TaskRunStatus.IDLE -> stringResource(R.string.assistant_schedule_status_idle)
        TaskRunStatus.RUNNING -> stringResource(R.string.assistant_schedule_status_running)
        TaskRunStatus.SUCCESS -> stringResource(R.string.assistant_schedule_status_success)
        TaskRunStatus.FAILED -> stringResource(R.string.assistant_schedule_status_failed)
    }
    val timeText = if (lastRunAt > 0) {
        Instant.ofEpochMilli(lastRunAt).toLocalDateTime()
    } else {
        stringResource(R.string.assistant_schedule_status_never_run)
    }
    return stringResource(R.string.assistant_schedule_status_line, status, timeText)
}

private fun DayOfWeek.displayName(): String {
    return getDisplayName(TextStyle.SHORT, Locale.getDefault())
}

private fun formatTime(timeMinutesOfDay: Int): String {
    val hour = timeMinutesOfDay.coerceIn(0, 1439) / 60
    val minute = timeMinutesOfDay.coerceIn(0, 1439) % 60
    return String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
}

@Composable
private fun boolOverrideTitle(option: NullableBooleanOption): String {
    return when (option) {
        NullableBooleanOption.FOLLOW -> stringResource(R.string.setting_scheduled_tasks_follow_global)
        NullableBooleanOption.ENABLED -> stringResource(R.string.setting_scheduled_tasks_force_enabled)
        NullableBooleanOption.DISABLED -> stringResource(R.string.setting_scheduled_tasks_force_disabled)
    }
}

@Composable
private fun searchServiceName(service: SearchServiceOptions): String {
    return SearchServiceOptions.TYPES[service::class]
        ?: service::class.simpleName
        ?: stringResource(R.string.setting_scheduled_tasks_search_fallback)
}

private fun Context.canScheduleExactAlarmsCompat(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = getSystemService(AlarmManager::class.java) ?: return false
    return alarmManager.canScheduleExactAlarms()
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        val fallbackIntent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallbackIntent)
    }
}
