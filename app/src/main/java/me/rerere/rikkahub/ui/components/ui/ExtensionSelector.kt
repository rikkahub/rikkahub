package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.ai.LorebooksContent
import me.rerere.rikkahub.ui.components.ai.ModeInjectionsContent
import me.rerere.rikkahub.ui.components.ai.QuickMessagesContent
import me.rerere.rikkahub.ui.components.ai.SkillsContent
import me.rerere.workspace.WorkspaceFileEntry
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid


internal const val WORKSPACE_PAGE_INDEX = 0
internal const val QUICK_MESSAGES_PAGE_INDEX = 1
internal const val MODE_INJECTIONS_PAGE_INDEX = 2
internal const val LOREBOOKS_PAGE_INDEX = 3
internal const val SKILLS_PAGE_INDEX = 4
private const val EXTENSION_PAGE_COUNT = 5

/** Suspends until this flow first emits [target], then returns. Cancels the upstream after the
 *  first match, so any work guarded behind it runs at most once per collection. */
internal suspend fun Flow<Int>.awaitPage(target: Int) {
    filter { it == target }.first()
}


@Composable
fun ExtensionSelector(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    settings: Settings,
    onUpdate: (Assistant) -> Unit,
    conversation: Conversation? = null,
    onUpdateConversation: ((Conversation) -> Unit)? = null,
    onNavigateToQuickMessages: () -> Unit = {},
    onNavigateToPrompts: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
    onNavigateToWorkspaces: () -> Unit = {},
    onNavigateToWorkspaceDetail: (String) -> Unit = {},
    onNavigateToWorkspaceTerminal: (String) -> Unit = {},
) {
    val skillManager: SkillManager = koinInject()
    val workspaceVm: WorkspaceSheetVM = koinViewModel(key = assistant.id.toString())
    val workspaceState by workspaceVm.state.collectAsStateWithLifecycle()
    val currentAssistant by rememberUpdatedState(assistant)
    val currentOnUpdate by rememberUpdatedState(onUpdate)
    var skills by remember { mutableStateOf<List<SkillMetadata>>(emptyList()) }

    val useConversationInjections =
        assistant.allowConversationPromptInjection && conversation != null && onUpdateConversation != null
    val selectedModeInjectionIds = if (useConversationInjections) {
        conversation.modeInjectionIds
    } else {
        assistant.modeInjectionIds
    }
    val selectedLorebookIds = if (useConversationInjections) {
        conversation.lorebookIds
    } else {
        assistant.lorebookIds
    }

    val pagerState = rememberPagerState(initialPage = QUICK_MESSAGES_PAGE_INDEX) { EXTENSION_PAGE_COUNT }
    val scope = rememberCoroutineScope()

    LaunchedEffect(assistant.workspaceId) {
        workspaceVm.syncAssistantWorkspaceId(assistant.workspaceId?.toString())
    }

    // Drive the Workspace VM by the SETTLED page so workspace IO runs only while the Workspace tab
    // is actually visible+settled: activate on settle, deactivate on any other tab. The VM is
    // ViewModelStore-scoped and outlives the sheet, so a one-shot activation would keep collecting
    // workspace rows after dismissal (see WorkspaceSheetVM.activate).
    LaunchedEffect(Unit) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page == WORKSPACE_PAGE_INDEX) {
                workspaceVm.activate(currentAssistant.workspaceId?.toString()) { id ->
                    currentOnUpdate(currentAssistant.copy(workspaceId = Uuid.parse(id)))
                }
            } else {
                workspaceVm.deactivate()
            }
        }
    }

    // Sheet dismissal disposes this composition; stop workspace IO so nothing runs while closed.
    DisposableEffect(Unit) {
        onDispose { workspaceVm.deactivate() }
    }

    // Defer the skills disk IO until the user actually settles on the Skills tab. Loading is
    // single-shot per sheet instance: awaitPage cancels the snapshotFlow after the first match.
    LaunchedEffect(Unit) {
        snapshotFlow { pagerState.settledPage }.awaitPage(SKILLS_PAGE_INDEX)
        withContext(Dispatchers.IO) {
            skills = skillManager.listSkills()
        }
    }

    Column(
        modifier = modifier
    ) {
        SecondaryScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 4.dp,
        ) {
            Tab(
                selected = pagerState.currentPage == WORKSPACE_PAGE_INDEX,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(WORKSPACE_PAGE_INDEX) }
                },
                text = { Text("Workspace") }
            )
            Tab(
                selected = pagerState.currentPage == QUICK_MESSAGES_PAGE_INDEX,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(QUICK_MESSAGES_PAGE_INDEX) }
                },
                text = { Text(stringResource(R.string.extension_selector_tab_quick_messages)) }
            )
            Tab(
                selected = pagerState.currentPage == MODE_INJECTIONS_PAGE_INDEX,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(MODE_INJECTIONS_PAGE_INDEX) }
                },
                text = { Text(stringResource(R.string.extension_selector_tab_mode_injections)) }
            )
            Tab(
                selected = pagerState.currentPage == LOREBOOKS_PAGE_INDEX,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(LOREBOOKS_PAGE_INDEX) }
                },
                text = { Text(stringResource(R.string.extension_selector_tab_lorebooks)) }
            )
            Tab(
                selected = pagerState.currentPage == SKILLS_PAGE_INDEX,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(SKILLS_PAGE_INDEX) }
                },
                text = { Text(stringResource(R.string.extension_selector_tab_skills)) }
            )
        }

        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 0,
            key = { page -> page },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (page) {
                WORKSPACE_PAGE_INDEX -> {
                    key(workspaceState.selectedWorkspaceId) {
                        WorkspaceTabContent(
                            state = workspaceState,
                            onSelectWorkspace = { id ->
                                workspaceVm.selectWorkspace(id) { selectedId ->
                                    currentOnUpdate(currentAssistant.copy(workspaceId = Uuid.parse(selectedId)))
                                }
                            },
                            onOpen = workspaceVm::open,
                            onGoUp = workspaceVm::goUp,
                            onSetProjectDir = workspaceVm::setCurrentAsProjectDir,
                            onManageWorkspaces = onNavigateToWorkspaces,
                            onOpenWorkspaceDetail = onNavigateToWorkspaceDetail,
                            onOpenTerminal = onNavigateToWorkspaceTerminal,
                        )
                    }
                }

                QUICK_MESSAGES_PAGE_INDEX -> {
                    if (settings.quickMessages.isNotEmpty()) {
                        QuickMessagesContent(
                            quickMessages = settings.quickMessages,
                            selectedIds = assistant.quickMessageIds,
                            onToggle = { id, checked ->
                                val newIds = if (checked) {
                                    assistant.quickMessageIds + id
                                } else {
                                    assistant.quickMessageIds - id
                                }
                                onUpdate(assistant.copy(quickMessageIds = newIds))
                            },
                            onManage = onNavigateToQuickMessages,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_quick_messages_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToQuickMessages,
                        )
                    }
                }

                MODE_INJECTIONS_PAGE_INDEX -> {
                    if (settings.modeInjections.isNotEmpty()) {
                        ModeInjectionsContent(
                            modeInjections = settings.modeInjections,
                            selectedIds = selectedModeInjectionIds,
                            onToggle = { id, checked ->
                                val newIds = if (checked) {
                                    selectedModeInjectionIds + id
                                } else {
                                    selectedModeInjectionIds - id
                                }
                                if (useConversationInjections) {
                                    onUpdateConversation(conversation.copy(modeInjectionIds = newIds))
                                } else {
                                    onUpdate(assistant.copy(modeInjectionIds = newIds))
                                }
                            },
                            onManage = onNavigateToPrompts,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_mode_injections_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToPrompts,
                        )
                    }
                }

                LOREBOOKS_PAGE_INDEX -> {
                    if (settings.lorebooks.isNotEmpty()) {
                        LorebooksContent(
                            lorebooks = settings.lorebooks,
                            selectedIds = selectedLorebookIds,
                            onToggle = { id, checked ->
                                val newIds = if (checked) {
                                    selectedLorebookIds + id
                                } else {
                                    selectedLorebookIds - id
                                }
                                if (useConversationInjections) {
                                    onUpdateConversation(conversation.copy(lorebookIds = newIds))
                                } else {
                                    onUpdate(assistant.copy(lorebookIds = newIds))
                                }
                            },
                            onManage = onNavigateToPrompts,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_lorebooks_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToPrompts,
                        )
                    }
                }

                SKILLS_PAGE_INDEX -> {
                    if (skills.isNotEmpty()) {
                        SkillsContent(
                            skills = skills,
                            enabledSkills = assistant.enabledSkills,
                            onToggle = { name, checked ->
                                val newSkills = if (checked) {
                                    assistant.enabledSkills + name
                                } else {
                                    assistant.enabledSkills - name
                                }
                                onUpdate(assistant.copy(enabledSkills = newSkills))
                            },
                            onManage = onNavigateToSkills,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_skills_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_skills),
                            onAction = onNavigateToSkills,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceTabContent(
    state: WorkspaceSheetState,
    onSelectWorkspace: (String) -> Unit,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onGoUp: () -> Unit,
    onSetProjectDir: () -> Unit,
    onManageWorkspaces: () -> Unit,
    onOpenWorkspaceDetail: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
) {
    val selectedWorkspace = state.workspaces.firstOrNull { it.id == state.selectedWorkspaceId }
    if (!state.activated) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    if (state.workspaces.isEmpty()) {
        ExtensionEmptyState(
            message = "No workspaces configured",
            buttonText = "Workspaces",
            onAction = onManageWorkspaces,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            WorkspaceSelectorRow(
                workspaces = state.workspaces,
                selectedWorkspace = selectedWorkspace,
                onSelectWorkspace = onSelectWorkspace,
                onManageWorkspaces = onManageWorkspaces,
            )
        }

        item {
            WorkspaceProjectRow(
                workspace = selectedWorkspace,
                projectDir = state.projectDir,
                onOpenWorkspaceDetail = onOpenWorkspaceDetail,
                onOpenTerminal = onOpenTerminal,
            )
        }

        item {
            WorkspaceSheetPathBar(
                path = state.path,
                canGoUp = state.path.isNotBlank(),
                settingProjectDir = state.settingProjectDir,
                onGoUp = onGoUp,
                onSetProjectDir = onSetProjectDir,
            )
        }

        if (state.error != null) {
            item {
                Text(
                    text = state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (state.loading) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        } else if (state.entries.isEmpty()) {
            item {
                Text(
                    text = "Empty directory",
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.entries, key = { it.path }) { entry ->
            WorkspaceSheetFileRow(entry = entry, onOpen = { onOpen(entry) })
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun WorkspaceSelectorRow(
    workspaces: List<WorkspaceSheetWorkspace>,
    selectedWorkspace: WorkspaceSheetWorkspace?,
    onSelectWorkspace: (String) -> Unit,
    onManageWorkspaces: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (workspaces.size == 1) {
            Text(
                text = workspaces.single().name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Select(
                options = workspaces,
                selectedOption = selectedWorkspace ?: workspaces.first(),
                onOptionSelected = { onSelectWorkspace(it.id) },
                modifier = Modifier.weight(1f),
                optionToString = { it.name },
            )
        }
        IconButton(onClick = onManageWorkspaces) {
            Icon(HugeIcons.Settings03, contentDescription = null)
        }
    }
}

@Composable
private fun WorkspaceProjectRow(
    workspace: WorkspaceSheetWorkspace?,
    projectDir: String,
    onOpenWorkspaceDetail: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Project directory", style = MaterialTheme.typography.labelMedium)
            Text(
                text = projectDir.ifBlank { "/workspace" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = workspace != null,
                    onClick = { workspace?.let { onOpenWorkspaceDetail(it.id) } },
                ) {
                    Text("Files")
                }
                SideloadWorkspaceTerminalAction(
                    workspaceId = workspace?.id,
                    onOpenTerminal = onOpenTerminal,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceSheetPathBar(
    path: String,
    canGoUp: Boolean,
    settingProjectDir: Boolean,
    onGoUp: () -> Unit,
    onSetProjectDir: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(enabled = canGoUp, onClick = onGoUp) {
            Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
        }
        Text(
            text = path.ifBlank { "/workspace" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Button(
            enabled = !settingProjectDir,
            onClick = onSetProjectDir,
        ) {
            Icon(
                imageVector = HugeIcons.CheckmarkCircle01,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text("Set")
        }
    }
}

@Composable
private fun WorkspaceSheetFileRow(
    entry: WorkspaceFileEntry,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (entry.isDirectory) Modifier.clickable(onClick = onOpen) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (entry.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
