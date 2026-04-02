package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.FileDownload
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.export.ModeInjectionSerializer
import me.rerere.rikkahub.data.export.rememberExporter
import me.rerere.rikkahub.data.export.rememberImporter
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookGlobalSettings
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.WorldInfoCharacterStrategy
import me.rerere.rikkahub.data.model.normalizeForModeInjection
import me.rerere.rikkahub.data.model.normalizedForSystemPromptSupplement
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.EditorGuideAction
import me.rerere.rikkahub.ui.components.ui.ExportDialog
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val modeInjectionPositions = listOf(
    InjectionPosition.BEFORE_SYSTEM_PROMPT,
    InjectionPosition.AFTER_SYSTEM_PROMPT,
)

@Composable
fun PromptPage(vm: PromptVM = koinViewModel()) {
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(stringResource(R.string.prompt_page_title)) },
                actions = {
                    EditorGuideAction(
                        title = stringResource(R.string.prompt_page_help_title),
                        bodyResId = R.raw.prompt_page_help_body_markdown,
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.prompt_page_overview_title)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.StPresets) },
                        leadingContent = { Icon(HugeIcons.MagicWand01, null) },
                        headlineContent = { Text(stringResource(R.string.prompt_page_overview_st_preset_title)) },
                        supportingContent = { Text(stringResource(R.string.prompt_page_overview_st_preset_desc)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.ModeInjections) },
                        leadingContent = { Icon(HugeIcons.Tools, null) },
                        headlineContent = { Text(stringResource(R.string.prompt_page_overview_mode_injection_title)) },
                        supportingContent = { Text(stringResource(R.string.prompt_page_overview_mode_injection_desc)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Lorebooks) },
                        leadingContent = { Icon(HugeIcons.Book01, null) },
                        headlineContent = { Text(stringResource(R.string.prompt_page_overview_lorebook_title)) },
                        supportingContent = { Text(stringResource(R.string.prompt_page_overview_lorebook_desc)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                }
            }
        }
    }
}

@Composable
fun ModeInjectionsPage(vm: PromptVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(stringResource(R.string.prompt_page_mode_injection_title)) },
                actions = {
                    EditorGuideAction(
                        title = stringResource(R.string.prompt_page_mode_injection_help_title),
                        bodyResId = R.raw.prompt_page_mode_injection_help_body_markdown,
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { innerPadding ->
        ModeInjectionTab(
            modifier = Modifier.padding(innerPadding),
            modeInjections = settings.modeInjections,
            onUpdate = { vm.updateSettings(settings.copy(modeInjections = it)) },
        )
    }
}

@Composable
fun LorebooksPage(vm: PromptVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(stringResource(R.string.prompt_page_lorebook_title)) },
                actions = {
                    EditorGuideAction(
                        title = stringResource(R.string.prompt_page_lorebook_help_title),
                        bodyResId = R.raw.prompt_page_lorebook_help_body_markdown,
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { innerPadding ->
        LorebookTab(
            modifier = Modifier.padding(innerPadding),
            lorebooks = settings.lorebooks,
            globalLorebookIds = settings.globalLorebookIds,
            globalSettings = settings.lorebookGlobalSettings,
            assistants = settings.assistants,
            onUpdateLorebooks = { vm.updateSettings(settings.copy(lorebooks = it)) },
            onUpdateGlobalLorebookIds = { vm.updateSettings(settings.copy(globalLorebookIds = it)) },
            onUpdateGlobalSettings = { vm.updateSettings(settings.copy(lorebookGlobalSettings = it)) },
            onUpdateAssistants = { vm.updateSettings(settings.copy(assistants = it)) },
        )
    }
}

@Composable
fun ModeInjectionTab(
    modifier: Modifier = Modifier,
    modeInjections: List<PromptInjection.ModeInjection>,
    onUpdate: (List<PromptInjection.ModeInjection>) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val toaster = LocalToaster.current
    val currentModeInjections by rememberUpdatedState(modeInjections)
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newList = modeInjections.toMutableList()
        val item = newList.removeAt(from.index)
        newList.add(to.index, item)
        onUpdate(newList)
    }
    val editState = useEditState<PromptInjection.ModeInjection> { edited ->
        val index = modeInjections.indexOfFirst { it.id == edited.id }
        if (index >= 0) {
            onUpdate(modeInjections.toMutableList().apply { set(index, edited) })
        } else {
            onUpdate(modeInjections + edited)
        }
    }
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val importer = rememberImporter(ModeInjectionSerializer) { result ->
        result.onSuccess { imported ->
            onUpdate(currentModeInjections + imported.normalizedForSystemPromptSupplement())
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedMsg.format(error.message))
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .floatingToolbarVerticalNestedScroll(
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false }
                ),
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState
        ) {
            if (modeInjections.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxHeight(0.8f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_mode_injection_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.prompt_page_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                items(modeInjections, key = { it.id }) { injection ->
                    ReorderableItem(
                        state = reorderableState,
                        key = injection.id
                    ) { isDragging ->
                        ModeInjectionCard(
                            injection = injection,
                            modifier = Modifier
                                .longPressDraggableHandle()
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    }
                                },
                            onEdit = { editState.open(injection.normalizedForSystemPromptSupplement()) },
                            onDelete = { onUpdate(modeInjections - injection) }
                        )
                    }
                }
            }
        }

        HorizontalFloatingToolbar(
            expanded = expanded,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            leadingContent = {
                IconButton(onClick = { importer.importFromFile() }) {
                    Icon(HugeIcons.FileImport, null)
                }
            },
        ) {
            Button(onClick = { editState.open(PromptInjection.ModeInjection().normalizedForSystemPromptSupplement()) }) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(HugeIcons.Add01, null)
                    AnimatedVisibility(expanded) {
                        Row {
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(stringResource(R.string.prompt_page_add_mode_injection))
                        }
                    }
                }
            }
        }
    }

    if (editState.isEditing) {
        editState.currentState?.let { state ->
            ModeInjectionEditSheet(
                injection = state,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it }
            )
        }
    }
}

@Composable
private fun ModeInjectionCard(
    injection: PromptInjection.ModeInjection,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val normalizedInjection = injection.normalizedForSystemPromptSupplement()
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(normalizedInjection, ModeInjectionSerializer)

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { swipeState.reset() } }) {
                    Icon(HugeIcons.Cancel01, null)
                }
                FilledIconButton(onClick = {
                    scope.launch {
                        onDelete()
                        swipeState.reset()
                    }
                }) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete))
                }
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = normalizedInjection.name.ifEmpty { stringResource(R.string.prompt_page_unnamed) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Tag(type = TagType.INFO) {
                            Text(getPositionLabel(normalizedInjection.position))
                        }
                        Tag(type = TagType.DEFAULT) {
                            Text(stringResource(R.string.prompt_page_priority_format, normalizedInjection.priority))
                        }
                        if (!normalizedInjection.enabled) {
                            Tag(type = TagType.WARNING) {
                                Text(stringResource(R.string.prompt_page_disabled))
                            }
                        }
                    }
                }
                IconButton(onClick = { showExportDialog = true }) {
                    Icon(HugeIcons.Share03, stringResource(R.string.export_title))
                }
                IconButton(onClick = onEdit) {
                    Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit))
                }
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false }
        )
    }
}

@Composable
private fun ModeInjectionEditSheet(
    injection: PromptInjection.ModeInjection,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (PromptInjection.ModeInjection) -> Unit
) {
    val normalizedInjection = injection.normalizedForSystemPromptSupplement()
    val updateInjection: (PromptInjection.ModeInjection) -> Unit = { edited ->
        onEdit(edited.normalizedForSystemPromptSupplement())
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.prompt_page_edit_mode_injection),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = normalizedInjection.name,
                    onValueChange = { updateInjection(normalizedInjection.copy(name = it)) },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    modifier = Modifier.fillMaxWidth()
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = normalizedInjection.enabled,
                            onCheckedChange = { updateInjection(normalizedInjection.copy(enabled = it)) }
                        )
                    }
                )

                OutlinedTextField(
                    value = normalizedInjection.priority.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { p -> updateInjection(normalizedInjection.copy(priority = p)) }
                    },
                    label = { Text(stringResource(R.string.prompt_page_priority_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Text(
                    stringResource(R.string.prompt_page_injection_position),
                    style = MaterialTheme.typography.titleSmall
                )
                InjectionPositionSelector(
                    position = normalizedInjection.position.normalizeForModeInjection(),
                    options = modeInjectionPositions,
                    onSelect = { updateInjection(normalizedInjection.copy(position = it)) }
                )

                OutlinedTextField(
                    value = normalizedInjection.content,
                    onValueChange = { updateInjection(normalizedInjection.copy(content = it)) },
                    label = { Text(stringResource(R.string.prompt_page_injection_content)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    minLines = 5
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.prompt_page_confirm))
                }
            }
        }
    }
}

@Composable
private fun InjectionPositionSelector(
    position: InjectionPosition,
    onSelect: (InjectionPosition) -> Unit,
    options: List<InjectionPosition> = InjectionPosition.entries.toList(),
) {
    Select(
        options = options,
        selectedOption = position,
        onOptionSelected = onSelect,
        optionToString = { getPositionLabel(it) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun getPositionLabel(position: InjectionPosition): String = when (position) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_before_system)
    InjectionPosition.AFTER_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_after_system)
    InjectionPosition.AUTHOR_NOTE_TOP -> stringResource(R.string.prompt_page_position_author_note_top)
    InjectionPosition.AUTHOR_NOTE_BOTTOM -> stringResource(R.string.prompt_page_position_author_note_bottom)
    InjectionPosition.TOP_OF_CHAT -> stringResource(R.string.prompt_page_position_top_of_chat)
    InjectionPosition.BOTTOM_OF_CHAT -> stringResource(R.string.prompt_page_position_bottom_of_chat)
    InjectionPosition.AT_DEPTH -> stringResource(R.string.prompt_page_position_at_depth)
    InjectionPosition.EXAMPLE_MESSAGES_TOP -> stringResource(R.string.prompt_page_position_example_messages_top)
    InjectionPosition.EXAMPLE_MESSAGES_BOTTOM -> stringResource(R.string.prompt_page_position_example_messages_bottom)
    InjectionPosition.OUTLET -> stringResource(R.string.prompt_page_position_outlet)
}

// ==================== Lorebook Tab ====================

@Composable
fun LorebookTab(
    modifier: Modifier = Modifier,
    lorebooks: List<Lorebook>,
    globalLorebookIds: Set<kotlin.uuid.Uuid> = emptySet(),
    globalSettings: LorebookGlobalSettings,
    assistants: List<Assistant> = emptyList(),
    onUpdateLorebooks: (List<Lorebook>) -> Unit,
    onUpdateGlobalLorebookIds: (Set<kotlin.uuid.Uuid>) -> Unit,
    onUpdateGlobalSettings: (LorebookGlobalSettings) -> Unit,
    onUpdateAssistants: (List<Assistant>) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val toaster = LocalToaster.current
    val currentLorebooks by rememberUpdatedState(lorebooks)
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newList = lorebooks.toMutableList()
        val item = newList.removeAt(from.index)
        newList.add(to.index, item)
        onUpdateLorebooks(newList)
    }
    val editState = useEditState<Lorebook> { edited ->
        val index = lorebooks.indexOfFirst { it.id == edited.id }
        if (index >= 0) {
            onUpdateLorebooks(lorebooks.toMutableList().apply { set(index, edited) })
        } else {
            onUpdateLorebooks(lorebooks + edited)
        }
    }
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val importer = rememberImporter(LorebookSerializer) { result ->
        result.onSuccess { imported ->
            onUpdateLorebooks(currentLorebooks + imported)
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedMsg.format(error.message))
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .floatingToolbarVerticalNestedScroll(
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false }
                ),
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState
        ) {
            item {
                LorebookOverviewCard(
                    lorebooks = lorebooks,
                    globalLorebookIds = globalLorebookIds,
                    assistants = assistants,
                )
            }
            item {
                LorebookGlobalSettingsCard(
                    settings = globalSettings,
                    onEdit = onUpdateGlobalSettings,
                )
            }
            if (lorebooks.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxHeight(0.8f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_lorebook_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.prompt_page_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                items(lorebooks, key = { it.id }) { book ->
                    ReorderableItem(
                        state = reorderableState,
                        key = book.id
                    ) { isDragging ->
                        LorebookCard(
                            book = book,
                            assistants = assistants,
                            globalLorebookIds = globalLorebookIds,
                            modifier = Modifier
                                .longPressDraggableHandle()
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    }
                                },
                            onEdit = { editState.open(book) },
                            onToggleEnabled = { enabled ->
                                onUpdateLorebooks(
                                    lorebooks.map { candidate ->
                                        if (candidate.id == book.id) {
                                            candidate.copy(enabled = enabled)
                                        } else {
                                            candidate
                                        }
                                    }
                                )
                            },
                            onUpdateAssistantBindings = { assistantIds ->
                                onUpdateAssistants(
                                    assistants.map { assistant ->
                                        val enabledForAssistant = assistant.id in assistantIds
                                        assistant.copy(
                                            lorebookIds = if (enabledForAssistant) {
                                                assistant.lorebookIds + book.id
                                            } else {
                                                assistant.lorebookIds - book.id
                                            }
                                        )
                                    }
                                )
                            },
                            onUpdateGlobalBinding = { enabled ->
                                onUpdateGlobalLorebookIds(
                                    if (enabled) {
                                        globalLorebookIds + book.id
                                    } else {
                                        globalLorebookIds - book.id
                                    }
                                )
                            },
                            onDelete = { onUpdateLorebooks(lorebooks - book) }
                        )
                    }
                }
            }
        }

        HorizontalFloatingToolbar(
            expanded = expanded,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            leadingContent = {
                IconButton(onClick = { importer.importFromFile() }) {
                    Icon(HugeIcons.FileImport, null)
                }
            },
        ) {
            Button(onClick = { editState.open(Lorebook()) }) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(HugeIcons.Add01, null)
                    AnimatedVisibility(expanded) {
                        Row {
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(stringResource(R.string.prompt_page_add_lorebook))
                        }
                    }
                }
            }
        }
    }

    if (editState.isEditing) {
        editState.currentState?.let { state ->
            LorebookEditSheet(
                book = state,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it }
            )
        }
    }
}

@Composable
private fun LorebookOverviewCard(
    lorebooks: List<Lorebook>,
    globalLorebookIds: Set<kotlin.uuid.Uuid>,
    assistants: List<Assistant>,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "使用说明",
                style = MaterialTheme.typography.titleMedium
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Tag(type = TagType.INFO) {
                    Text("${lorebooks.size} 本")
                }
                Tag(type = TagType.SUCCESS) {
                    Text("${assistants.count { it.lorebookIds.isNotEmpty() }} 个助手已绑定")
                }
                if (globalLorebookIds.isNotEmpty()) {
                    Tag(type = TagType.INFO) {
                        Text("${globalLorebookIds.size} 本全局生效")
                    }
                }
                Tag(type = TagType.WARNING) {
                    Text("按关键词自动触发")
                }
            }
            LorebookGuideRow(
                title = "全局设置",
                body = "控制默认扫描深度、预算、递归和匹配策略，决定整个 lorebook 系统在聊天时如何搜索可触发条目。"
            )
            LorebookGuideRow(
                title = "书本管理",
                body = "每本书都可以单独启用、导出、删除，并通过链接按钮绑定到指定助手，或显式设为全局生效；没绑定且未设为全局的书不会参与聊天。"
            )
            LorebookGuideRow(
                title = "条目编辑",
                body = "条目负责定义关键词、注入位置和高级 ST 兼容字段。建议先把基础触发跑通，再逐步加概率、分组、sticky 或 recursion。"
            )
        }
    }
}

@Composable
private fun LorebookGuideRow(
    title: String,
    body: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LorebookGlobalSettingsCard(
    settings: LorebookGlobalSettings,
    onEdit: (LorebookGlobalSettings) -> Unit,
) {
    fun updateMinActivations(value: Int) {
        onEdit(
            settings.copy(
                minActivations = value,
                maxRecursionSteps = if (value > 0) 0 else settings.maxRecursionSteps,
            )
        )
    }

    fun updateMaxRecursionSteps(value: Int) {
        onEdit(
            settings.copy(
                maxRecursionSteps = value,
                minActivations = if (value > 0) 0 else settings.minActivations,
            )
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.prompt_page_lorebook_global_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.prompt_page_lorebook_global_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LorebookGlobalNumberField(
                label = stringResource(R.string.prompt_page_lorebook_global_scan_depth),
                value = settings.scanDepth,
                onValueChange = { onEdit(settings.copy(scanDepth = it)) }
            )
            LorebookGlobalNumberField(
                label = stringResource(R.string.prompt_page_lorebook_global_min_activations),
                value = settings.minActivations,
                onValueChange = ::updateMinActivations
            )
            LorebookGlobalNumberField(
                label = stringResource(R.string.prompt_page_lorebook_global_min_activations_depth_max),
                value = settings.minActivationsDepthMax,
                onValueChange = { onEdit(settings.copy(minActivationsDepthMax = it)) }
            )
            LorebookGlobalNumberField(
                label = stringResource(R.string.prompt_page_lorebook_global_budget_percent),
                value = settings.budgetPercent,
                onValueChange = { onEdit(settings.copy(budgetPercent = it.coerceIn(0, 100))) }
            )
            LorebookGlobalNumberField(
                label = stringResource(R.string.prompt_page_lorebook_global_budget_cap),
                value = settings.budgetCap,
                onValueChange = { onEdit(settings.copy(budgetCap = it)) }
            )
            LorebookGlobalNumberField(
                label = stringResource(R.string.prompt_page_lorebook_global_max_recursion_steps),
                value = settings.maxRecursionSteps,
                onValueChange = ::updateMaxRecursionSteps
            )

            Text(
                text = stringResource(R.string.prompt_page_lorebook_global_character_strategy),
                style = MaterialTheme.typography.titleSmall
            )
            Select(
                options = WorldInfoCharacterStrategy.entries,
                selectedOption = settings.characterStrategy,
                onOptionSelected = { onEdit(settings.copy(characterStrategy = it)) },
                optionToString = { getWorldInfoCharacterStrategyLabel(it) },
                modifier = Modifier.fillMaxWidth()
            )

            FormItem(
                label = { Text(stringResource(R.string.prompt_page_lorebook_global_include_names)) },
                description = { Text(stringResource(R.string.prompt_page_lorebook_global_include_names_desc)) },
                tail = {
                    Switch(
                        checked = settings.includeNames,
                        onCheckedChange = { onEdit(settings.copy(includeNames = it)) }
                    )
                }
            )
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_lorebook_global_recursive)) },
                description = { Text(stringResource(R.string.prompt_page_lorebook_global_recursive_desc)) },
                tail = {
                    Switch(
                        checked = settings.recursiveScanning,
                        onCheckedChange = { onEdit(settings.copy(recursiveScanning = it)) }
                    )
                }
            )
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_lorebook_global_case_sensitive)) },
                tail = {
                    Switch(
                        checked = settings.caseSensitive,
                        onCheckedChange = { onEdit(settings.copy(caseSensitive = it)) }
                    )
                }
            )
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_lorebook_global_match_whole_words)) },
                tail = {
                    Switch(
                        checked = settings.matchWholeWords,
                        onCheckedChange = { onEdit(settings.copy(matchWholeWords = it)) }
                    )
                }
            )
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_lorebook_global_group_scoring)) },
                tail = {
                    Switch(
                        checked = settings.useGroupScoring,
                        onCheckedChange = { onEdit(settings.copy(useGroupScoring = it)) }
                    )
                }
            )
            FormItem(
                label = { Text(stringResource(R.string.prompt_page_lorebook_global_overflow_alert)) },
                tail = {
                    Switch(
                        checked = settings.overflowAlert,
                        onCheckedChange = { onEdit(settings.copy(overflowAlert = it)) }
                    )
                }
            )
        }
    }
}

@Composable
private fun LorebookGlobalNumberField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { input ->
            input.toIntOrNull()?.let(onValueChange)
            if (input.isBlank()) onValueChange(0)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

@Composable
private fun getWorldInfoCharacterStrategyLabel(strategy: WorldInfoCharacterStrategy): String = when (strategy) {
    WorldInfoCharacterStrategy.EVENLY -> stringResource(R.string.prompt_page_lorebook_global_character_strategy_evenly)
    WorldInfoCharacterStrategy.CHARACTER_FIRST -> stringResource(R.string.prompt_page_lorebook_global_character_strategy_character_first)
    WorldInfoCharacterStrategy.GLOBAL_FIRST -> stringResource(R.string.prompt_page_lorebook_global_character_strategy_global_first)
}

@Composable
private fun LorebookCard(
    book: Lorebook,
    assistants: List<Assistant>,
    globalLorebookIds: Set<kotlin.uuid.Uuid>,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onUpdateAssistantBindings: (Set<kotlin.uuid.Uuid>) -> Unit,
    onUpdateGlobalBinding: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }
    var showBindingsSheet by remember { mutableStateOf(false) }
    val exporter = rememberExporter(book, LorebookSerializer)
    val boundAssistantIds = remember(book.id, assistants) {
        assistants
            .filter { assistant -> book.id in assistant.lorebookIds }
            .map { assistant -> assistant.id }
            .toSet()
    }
    val isGlobalBinding = book.id in globalLorebookIds

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { swipeState.reset() } }) {
                    Icon(HugeIcons.Cancel01, null)
                }
                FilledIconButton(onClick = {
                    scope.launch {
                        onDelete()
                        swipeState.reset()
                    }
                }) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete))
                }
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = book.name.ifEmpty { stringResource(R.string.prompt_page_unnamed_lorebook) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (book.description.isNotEmpty()) {
                        Text(
                            text = book.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Tag(type = TagType.INFO) {
                            Text(
                                stringResource(
                                    R.string.prompt_page_entries_count_format,
                                    book.entries.size
                                )
                            )
                        }
                        if (!book.enabled) {
                            Tag(type = TagType.WARNING) {
                                Text(stringResource(R.string.prompt_page_disabled))
                            }
                        }
                        Tag(type = TagType.DEFAULT) {
                            Text(
                                stringResource(
                                    R.string.prompt_page_lorebook_assistant_count,
                                    boundAssistantIds.size,
                                )
                            )
                        }
                        if (isGlobalBinding) {
                            Tag(type = TagType.SUCCESS) {
                                Text("全局生效")
                            }
                        }
                        if (book.recursiveScanning) {
                            Tag(type = TagType.SUCCESS) {
                                Text(stringResource(R.string.prompt_page_lorebook_recursive_badge))
                            }
                        }
                        book.tokenBudget?.let { budget ->
                            Tag(type = TagType.INFO) {
                                Text(stringResource(R.string.prompt_page_lorebook_budget_badge, budget))
                            }
                        }
                    }
                }
                Switch(
                    checked = book.enabled,
                    onCheckedChange = onToggleEnabled,
                )
                IconButton(onClick = { showBindingsSheet = true }) {
                    Icon(HugeIcons.Link01, stringResource(R.string.prompt_page_lorebook_manage_bindings))
                }
                IconButton(onClick = { showExportDialog = true }) {
                    Icon(HugeIcons.Share03, stringResource(R.string.export_title))
                }
                IconButton(onClick = onEdit) {
                    Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit))
                }
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false }
        )
    }

    if (showBindingsSheet) {
        LorebookBindingSheet(
            book = book,
            assistants = assistants,
            isGlobalBinding = isGlobalBinding,
            boundAssistantIds = boundAssistantIds,
            onDismiss = { showBindingsSheet = false },
            onUpdateGlobalBinding = onUpdateGlobalBinding,
            onUpdate = onUpdateAssistantBindings,
        )
    }
}

@Composable
private fun LorebookBindingSheet(
    book: Lorebook,
    assistants: List<Assistant>,
    isGlobalBinding: Boolean,
    boundAssistantIds: Set<kotlin.uuid.Uuid>,
    onDismiss: () -> Unit,
    onUpdateGlobalBinding: (Boolean) -> Unit,
    onUpdate: (Set<kotlin.uuid.Uuid>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.prompt_page_lorebook_binding_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                text = book.name.ifBlank { stringResource(R.string.prompt_page_unnamed_lorebook) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item("global_binding") {
                    ListItem(
                        headlineContent = {
                            Text("全局生效")
                        },
                        supportingContent = {
                            Text(
                                "启用后，这本世界书会对所有助手生效；单独绑定仍会保留角色作用域。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = isGlobalBinding,
                                onCheckedChange = onUpdateGlobalBinding,
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                        )
                    )
                }

                if (assistants.isEmpty()) {
                    item("empty_assistants") {
                        Text(
                            text = stringResource(R.string.prompt_page_lorebook_binding_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(assistants, key = { it.id }) { assistant ->
                        ListItem(
                            headlineContent = {
                                Text(assistant.name.ifBlank { stringResource(R.string.prompt_page_lorebook_binding_unnamed_assistant) })
                            },
                            trailingContent = {
                                Switch(
                                    checked = assistant.id in boundAssistantIds,
                                    onCheckedChange = { checked ->
                                        val updatedIds = if (checked) {
                                            boundAssistantIds + assistant.id
                                        } else {
                                            boundAssistantIds - assistant.id
                                        }
                                        onUpdate(updatedIds)
                                    }
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LorebookEditSheet(
    book: Lorebook,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (Lorebook) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val entryEditState = useEditState<PromptInjection.RegexInjection> { edited ->
        val index = book.entries.indexOfFirst { it.id == edited.id }
        if (index >= 0) {
            onEdit(book.copy(entries = book.entries.toMutableList().apply { set(index, edited) }))
        } else {
            onEdit(book.copy(entries = book.entries + edited))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.prompt_page_edit_lorebook),
                    style = MaterialTheme.typography.titleLarge,
                )
                EditorGuideAction(
                    title = stringResource(R.string.prompt_page_lorebook_editor_help_title),
                    bodyResId = R.raw.prompt_page_lorebook_editor_help_body_markdown,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = book.name,
                    onValueChange = { onEdit(book.copy(name = it)) },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = book.description,
                    onValueChange = { onEdit(book.copy(description = it)) },
                    label = { Text(stringResource(R.string.prompt_page_description)) },
                    modifier = Modifier.fillMaxWidth()
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = book.enabled,
                            onCheckedChange = { onEdit(book.copy(enabled = it)) }
                        )
                    }
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_lorebook_book_recursive)) },
                    description = { Text(stringResource(R.string.prompt_page_lorebook_book_recursive_desc)) },
                    tail = {
                        Switch(
                            checked = book.recursiveScanning,
                            onCheckedChange = { onEdit(book.copy(recursiveScanning = it)) }
                        )
                    }
                )

                OutlinedTextField(
                    value = book.tokenBudget?.toString().orEmpty(),
                    onValueChange = { value ->
                        val normalized = value.trim()
                        if (normalized.isEmpty()) {
                            onEdit(book.copy(tokenBudget = null))
                        } else {
                            normalized.toIntOrNull()?.let { budget ->
                                onEdit(book.copy(tokenBudget = budget))
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.prompt_page_lorebook_book_token_budget)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                // 条目列表
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.prompt_page_entries_format, book.entries.size),
                        style = MaterialTheme.typography.titleSmall
                    )
                    IconButton(onClick = {
                        entryEditState.open(PromptInjection.RegexInjection())
                    }) {
                        Icon(HugeIcons.Add01, stringResource(R.string.prompt_page_add_entry))
                    }
                }

                book.entries.forEach { entry ->
                    RegexInjectionEntryCard(
                        entry = entry,
                        onEdit = { entryEditState.open(entry) },
                        onDelete = {
                            onEdit(book.copy(entries = book.entries - entry))
                        }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.prompt_page_confirm))
                }
            }
        }
    }

    if (entryEditState.isEditing) {
        entryEditState.currentState?.let { state ->
            RegexInjectionEditDialog(
                entry = state,
                onDismiss = { entryEditState.dismiss() },
                onConfirm = { entryEditState.confirm() },
                onEdit = { entryEditState.currentState = it }
            )
        }
    }
}

@Composable
private fun RegexInjectionEntryCard(
    entry: PromptInjection.RegexInjection,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = entry.name.ifEmpty { stringResource(R.string.prompt_page_unnamed_entry) },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (entry.keywords.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.prompt_page_keywords_format, entry.keywords.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!entry.enabled) {
                        Tag(type = TagType.WARNING) {
                            Text(stringResource(R.string.prompt_page_disabled))
                        }
                    }
                    if (entry.constantActive) {
                        Tag(type = TagType.INFO) {
                            Text(stringResource(R.string.prompt_page_constant_active))
                        }
                    }
                    if (entry.secondaryKeywords.isNotEmpty()) {
                        Tag(type = TagType.DEFAULT) {
                            Text(
                                stringResource(
                                    R.string.prompt_page_lorebook_secondary_keywords_badge,
                                    entry.secondaryKeywords.size,
                                )
                            )
                        }
                    }
                    entry.probability?.let { probability ->
                        Tag(type = TagType.SUCCESS) {
                            Text(
                                stringResource(
                                    R.string.prompt_page_lorebook_probability_badge,
                                    probability,
                                )
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onEdit) {
                Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete))
            }
        }
    }
}
