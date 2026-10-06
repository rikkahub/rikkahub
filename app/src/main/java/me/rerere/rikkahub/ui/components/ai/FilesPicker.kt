package me.rerere.rikkahub.ui.components.ai

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.Voice
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.ui.ExtensionSelector
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.compose.koinInject

private enum class FilesPickerPage {
    MAIN,
    MCP,
    EXTENSIONS,
}

@Composable
internal fun FilesPicker(
    conversation: Conversation,
    // 会话视角下的助手和模型：会话开始后以会话上固定的配置为准
    assistant: Assistant,
    chatModel: Model?,
    state: ChatInputState,
    mcpManager: McpManager,
    onCompressContext: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    showCompressDialog: Boolean,
    onShowCompressDialogChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
    onSketch: () -> Unit,
    onStartVoiceMode: (() -> Unit)? = null,
) {
    val settings = LocalSettings.current
    val provider = chatModel?.findProvider(providers = settings.providers)
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())

    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }
    val showCwd = boundWorkspace != null && boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name
    var showCwdSheet by remember { mutableStateOf(false) }

    var page by remember { mutableStateOf(FilesPickerPage.MAIN) }
    // 在子页面时，返回键回到主页面而不是关闭 sheet
    BackHandler(enabled = page != FilesPickerPage.MAIN) {
        page = FilesPickerPage.MAIN
    }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState != FilesPickerPage.MAIN) {
                slideInHorizontally { it } + fadeIn() togetherWith
                    slideOutHorizontally { -it } + fadeOut()
            } else {
                slideInHorizontally { -it } + fadeIn() togetherWith
                    slideOutHorizontally { it } + fadeOut()
            }
        },
        label = "FilesPickerPage"
    ) { currentPage ->
        when (currentPage) {
            FilesPickerPage.MCP -> McpPickerPage(
                assistant = assistant,
                servers = settings.mcpServers,
                mcpManager = mcpManager,
                onUpdateAssistant = onUpdateAssistant,
                onBack = { page = FilesPickerPage.MAIN },
            )

            FilesPickerPage.EXTENSIONS -> ExtensionPickerPage(
                assistant = assistant,
                settings = settings,
                onUpdateAssistant = onUpdateAssistant,
                onBack = { page = FilesPickerPage.MAIN },
                onDismissAll = onDismiss,
            )

            FilesPickerPage.MAIN -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AttachmentGrid(
                    actions = buildList {
                        add(AttachmentAction(HugeIcons.Camera01, R.string.take_picture, onTakePic))
                        add(AttachmentAction(HugeIcons.Image02, R.string.photo, onPickImage))
                        add(AttachmentAction(HugeIcons.PaintBoard, R.string.sketch, onSketch))
                        if (provider != null && provider is ProviderSetting.Google) {
                            add(AttachmentAction(HugeIcons.Video01, R.string.video, onPickVideo))
                            add(AttachmentAction(HugeIcons.MusicNote03, R.string.audio, onPickAudio))
                        }
                        add(AttachmentAction(HugeIcons.Files02, R.string.upload_file, onPickFile))
                        onStartVoiceMode?.let { start ->
                            add(AttachmentAction(HugeIcons.Voice, R.string.chat_page_voice_title, start))
                        }
                    }
                )

                // 分段列表：各项按显示条件依次占位，首尾项才有大圆角
                val showWorkspace = workspaces.isNotEmpty()
                val showMcp = settings.mcpServers.isNotEmpty()
                val itemCount = 2 + listOf(showWorkspace, showWorkspace && showCwd, showMcp).count { it }
                var itemIndex = 0
                Column(
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    if (showWorkspace) {
                        WorkspacePickerListItem(
                            assistant = assistant,
                            workspaces = workspaces,
                            shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                            onUpdateAssistant = onUpdateAssistant,
                            onNavigateToDetail = { id ->
                                onDismiss()
                                navController.navigate(Screen.WorkspaceDetail(id))
                            },
                            onNavigateToTerminal = { id ->
                                onDismiss()
                                navController.navigate(Screen.WorkspaceTerminal(id))
                            },
                            onNavigateToManage = {
                                onDismiss()
                                navController.navigate(Screen.Workspaces)
                            },
                        )

                        // Workspace CWD
                        if (showCwd) {
                            SegmentedListItem(
                                onClick = { showCwdSheet = true },
                                shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                                leadingContent = {
                                    Icon(
                                        imageVector = HugeIcons.Folder01,
                                        contentDescription = stringResource(R.string.workspace_cwd_select_directory),
                                    )
                                },
                                trailingContent = {
                                    Icon(HugeIcons.ArrowRight01, contentDescription = null)
                                },
                            ) {
                                Text(
                                    text = conversation.workspaceCwd ?: "/workspace",
                                    // 路径的末尾更有辨识度，放不下时省略开头
                                    maxLines = 1,
                                    overflow = TextOverflow.StartEllipsis,
                                )
                            }
                        }
                    }

                    if (showMcp) {
                        McpPickerListItem(
                            assistant = assistant,
                            servers = settings.mcpServers,
                            mcpManager = mcpManager,
                            shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                            onClick = { page = FilesPickerPage.MCP },
                        )
                    }

                    // Extensions (Quick Messages + Prompt Injections + Skills)
                    val activeCount =
                        assistant.quickMessageIds.size +
                            assistant.modeInjectionIds.size +
                            assistant.lorebookIds.size +
                            assistant.enabledSkills.size
                    SegmentedListItem(
                        onClick = { page = FilesPickerPage.EXTENSIONS },
                        shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                        leadingContent = {
                            Icon(
                                imageVector = HugeIcons.Package,
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            if (activeCount > 0) {
                                CountBadge(count = activeCount)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.assistant_page_tab_extensions))
                    }

                    // Compress History Button
                    SegmentedListItem(
                        onClick = { onShowCompressDialogChange(true) },
                        shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                        leadingContent = {
                            Icon(
                                imageVector = HugeIcons.Package01,
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            if (conversation.messageNodes.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.chat_page_message_count, conversation.messageNodes.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    ) {
                        Text(stringResource(R.string.chat_page_compress_context))
                    }
                }
            }
        }
    }

    if (showCwdSheet && boundWorkspace != null) {
        WorkspaceCwdPickerSheet(
            workspaceId = boundWorkspace.id,
            currentCwd = conversation.workspaceCwd,
            onSelectCwd = { newCwd ->
                onUpdateConversation(conversation.copy(workspaceCwd = newCwd))
            },
            onDismiss = { showCwdSheet = false },
        )
    }

    // Compress Context Dialog
    if (showCompressDialog) {
        CompressContextDialog(onDismiss = {
            onShowCompressDialogChange(false)
            onDismiss()
        }, onConfirm = { additionalPrompt, targetTokens, keepRecentMessages ->
            onCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
        })
    }
}

@Composable
private fun WorkspacePickerListItem(
    assistant: Assistant,
    workspaces: List<WorkspaceEntity>,
    shapes: ListItemShapes,
    onUpdateAssistant: (Assistant) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToTerminal: (String) -> Unit,
    onNavigateToManage: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }

    SegmentedListItem(
        onClick = { showSheet = true },
        shapes = shapes,
        leadingContent = {
            Icon(
                imageVector = HugeIcons.Codesandbox,
                contentDescription = null,
            )
        },
        supportingContent = {
            Text(
                text = boundWorkspace?.name ?: stringResource(R.string.assistant_page_workspace_unbound),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (boundWorkspace != null) {
                    IconButton(onClick = { onNavigateToDetail(boundWorkspace.id) }) {
                        Icon(
                            imageVector = HugeIcons.Settings02,
                            contentDescription = stringResource(R.string.workspace_detail),
                        )
                    }
                    if (boundWorkspace.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { onNavigateToTerminal(boundWorkspace.id) }) {
                            Icon(
                                imageVector = HugeIcons.ComputerTerminal01,
                                contentDescription = stringResource(R.string.workspace_terminal),
                            )
                        }
                    }
                }
            }
        },
    ) {
        Text(stringResource(R.string.assistant_page_workspace))
    }

    if (showSheet) {
        WorkspaceSelectSheet(
            assistant = assistant,
            workspaces = workspaces,
            onSelect = { workspaceId ->
                val newId = workspaceId?.let { Uuid.parse(it) }
                if (newId != assistant.workspaceId) {
                    // 会话的工作目录随工作区一并重置
                    onUpdateAssistant(assistant.copy(workspaceId = newId))
                }
                showSheet = false
            },
            onManage = {
                showSheet = false
                onNavigateToManage()
            },
            onDismiss = { showSheet = false },
        )
    }
}

// 扩展选择页，作为子页面嵌在加号 sheet 里
@Composable
private fun ExtensionPickerPage(
    assistant: Assistant,
    settings: Settings,
    onUpdateAssistant: (Assistant) -> Unit,
    onBack: () -> Unit,
    onDismissAll: () -> Unit,
) {
    val navController = LocalNavController.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.75f)
            .padding(horizontal = 16.dp),
    ) {
        SheetHeader(
            title = stringResource(R.string.assistant_page_tab_extensions),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                }
            },
        )
        ExtensionSelector(
            assistant = assistant,
            settings = settings,
            onUpdate = onUpdateAssistant,
            modifier = Modifier.weight(1f),
            onNavigateToQuickMessages = {
                onDismissAll()
                navController.navigate(Screen.QuickMessages)
            },
            onNavigateToPrompts = {
                onDismissAll()
                navController.navigate(Screen.Prompts)
            },
            onNavigateToSkills = {
                onDismissAll()
                navController.navigate(Screen.Skills)
            })

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private class AttachmentAction(
    val icon: ImageVector,
    @param:StringRes val label: Int,
    val onClick: () -> Unit,
)

// 附件入口：一行最多四个，再多就均分成两行
@Composable
private fun AttachmentGrid(
    actions: List<AttachmentAction>,
    modifier: Modifier = Modifier,
) {
    val perRow = if (actions.size <= 4) actions.size else (actions.size + 1) / 2
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.chunked(perRow).fastForEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.fastForEach { action ->
                    AttachmentTile(
                        action = action,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentTile(
    action: AttachmentAction,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = action.onClick,
        // 按下时圆角收紧
        shapes = ButtonDefaults.shapes(
            shape = MaterialTheme.shapes.extraLarge,
            pressedShape = MaterialTheme.shapes.medium,
        ),
        modifier = modifier.heightIn(min = 88.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = stringResource(action.label),
                // 四个并排时较长的译文放不下，自动缩小字号
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 14.sp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// 列表项尾部的已启用数量
@Composable
internal fun CountBadge(count: Int) {
    Badge(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(count.toString())
    }
}

@Preview(showBackground = true)
@Composable
private fun AttachmentGridPreview() {
    AttachmentGrid(
        actions = listOf(
            AttachmentAction(HugeIcons.Camera01, R.string.take_picture) {},
            AttachmentAction(HugeIcons.Image02, R.string.photo) {},
            AttachmentAction(HugeIcons.Video01, R.string.video) {},
            AttachmentAction(HugeIcons.MusicNote03, R.string.audio) {},
            AttachmentAction(HugeIcons.Files02, R.string.upload_file) {},
        ),
        modifier = Modifier.padding(16.dp),
    )
}
