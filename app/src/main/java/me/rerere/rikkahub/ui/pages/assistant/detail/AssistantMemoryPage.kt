package me.rerere.rikkahub.ui.pages.assistant.detail

import android.text.format.DateUtils
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.MemoryConsolidationStatus
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.groupByDirectory
import me.rerere.rikkahub.data.model.memoryTitleOf
import me.rerere.rikkahub.ui.components.message.ChatMessageToolStep
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.memory.resultText
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val consolidation by vm.memoryConsolidation.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_memory))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantMemoryContent(
            innerPadding = innerPadding,
            assistant = assistant,
            memories = memories,
            consolidation = consolidation,
            onOrganizeMemory = { vm.organizeMemory() },
            onUpdateAssistant = { vm.update(it) },
            onDeleteMemory = { vm.deleteMemory(it) },
            onOpenMemory = { path -> navController.navigate(Screen.MemoryFile(vm.memoryId, path)) },
        )
    }
}

@Composable
private fun AssistantMemoryContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    memories: List<MemoryFile>,
    consolidation: MemoryConsolidationStatus,
    onOrganizeMemory: () -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    // path 为 null 时新建
    onOpenMemory: (path: String?) -> Unit,
    onDeleteMemory: (MemoryFile) -> Unit,
) {
    var pendingDeleteMemory by remember { mutableStateOf<MemoryFile?>(null) }
    val memoryGroups = remember(memories) { memories.groupByDirectory() }

    var showTimeReminderIntervalDialog by remember(assistant.id) { mutableStateOf(false) }
    var timeReminderIntervalInput by remember(assistant.id) { mutableStateOf("") }

    if (showTimeReminderIntervalDialog) {
        val interval = timeReminderIntervalInput.toIntOrNull()?.takeIf { it > 0 }
        AlertDialog(
            onDismissRequest = { showTimeReminderIntervalDialog = false },
            title = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
            text = {
                TextField(
                    value = timeReminderIntervalInput,
                    onValueChange = { timeReminderIntervalInput = it },
                    label = { Text(stringResource(R.string.assistant_page_time_reminder_interval_label)) },
                    supportingText = { Text(stringResource(R.string.assistant_page_time_reminder_interval_hint)) },
                    isError = interval == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = interval != null,
                    onClick = {
                        interval?.let {
                            onUpdateAssistant(assistant.copy(timeReminderIntervalMinutes = it))
                        }
                        showTimeReminderIntervalDialog = false
                    },
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimeReminderIntervalDialog = false }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .padding(bottom = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            switchItem(
                checked = assistant.enableMemory,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            enableMemory = it
                        )
                    )
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_memory_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_memory)) },
            )
            switchItem(
                checked = assistant.useGlobalMemory,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            useGlobalMemory = it
                        )
                    )
                },
                enabled = assistant.enableMemory,
                supportingContent = { Text(stringResource(R.string.assistant_page_global_memory_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
            )
            switchItem(
                checked = assistant.enableRecentChatsReference,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            enableRecentChatsReference = it
                        )
                    )
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_recent_chats_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
            )
        }

        CardGroup {
            switchItem(
                checked = assistant.enableTimeReminder,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            enableTimeReminder = it
                        )
                    )
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_time_reminder_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder)) },
            )
            if (assistant.enableTimeReminder) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval_desc)) },
                    trailingContent = {
                        Text(
                            text = stringResource(
                                R.string.assistant_page_time_reminder_interval_value,
                                assistant.timeReminderIntervalMinutes
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = {
                        timeReminderIntervalInput = assistant.timeReminderIntervalMinutes.toString()
                        showTimeReminderIntervalDialog = true
                    },
                )
            }
        }

        if (assistant.enableMemory) {
            MemoryConsolidationSection(
                status = consolidation,
                assistant = assistant,
                onOrganize = onOrganizeMemory,
            )
        }

        SectionHeader(
            title = stringResource(R.string.assistant_page_manage_memory_title),
            action = {
                SectionAddButton(
                    onClick = { onOpenMemory(null) },
                )
            },
        )

        memoryGroups.fastForEach { (directory, files) ->
            CardGroup(
                title = { Text(memorySectionTitle(directory)) },
            ) {
                files.fastForEach { memory ->
                    item(
                        onClick = { onOpenMemory(memory.path) },
                        trailingContent = {
                            ItemActionMenu(
                                actions = listOf(
                                    ItemAction(
                                        text = stringResource(R.string.delete),
                                        icon = HugeIcons.Delete01,
                                        destructive = true,
                                        onClick = { pendingDeleteMemory = memory },
                                    ),
                                )
                            )
                        },
                        headlineContent = {
                            Text(
                                text = memory.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (memory.description.isNotEmpty()) {
                                    Text(
                                        text = memory.description,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = "Updated ${relativeTimeOf(memory.updatedAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let(onDeleteMemory)
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        text = {
            Text(
                text = pendingDeleteMemory?.path.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

/** 后台整理的状态和手动入口，下面跟着上一次整理的工具调用记录 */
@Composable
private fun MemoryConsolidationSection(
    status: MemoryConsolidationStatus,
    assistant: Assistant,
    onOrganize: () -> Unit,
) {
    val lastRun = status.lastRun
    CardGroup {
        item(
            headlineContent = { Text("Dreaming") },
            supportingContent = { Text(memoryConsolidationSummary(status)) },
            trailingContent = {
                if (status.running) {
                    RabbitLoadingIndicator(modifier = Modifier.size(32.dp))
                } else {
                    FilledTonalButton(onClick = onOrganize) {
                        Text(if (lastRun?.error != null) "Retry" else "Dream now")
                    }
                }
            },
        )
    }
    if (lastRun != null && lastRun.steps.isNotEmpty()) {
        ChainOfThought(steps = lastRun.steps) { tool ->
            ChatMessageToolStep(tool = tool, assistant = assistant)
        }
    }
}

private fun memoryConsolidationSummary(status: MemoryConsolidationStatus): String {
    val lastRun = status.lastRun
    return when {
        status.running -> "Dreaming…"
        lastRun?.error != null -> lastRun.resultText()
        else -> listOfNotNull(
            status.pendingTurns.takeIf { it > 0 }?.let { "$it new ${if (it == 1) "turn" else "turns"} waiting" },
            lastRun?.let { "${relativeTimeOf(it.finishedAt)}: ${it.resultText()}" },
        ).joinToString("\n").ifEmpty { "Turns your chats into memories once they pause" }
    }
}

// 根目录下是 profile 和 preferences，都是关于用户本人的
private fun memorySectionTitle(directory: String): String =
    if (directory == "/") "You" else memoryTitleOf(directory.removePrefix("/"))

// 一周内显示"41 分钟前"这样的相对时间，更早的显示日期
private fun relativeTimeOf(time: Long): String = DateUtils.getRelativeTimeSpanString(
    time,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_MONTH,
).toString()
