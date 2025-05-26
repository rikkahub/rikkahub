package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Eraser
import com.composables.icons.lucide.History
import com.composables.icons.lucide.ListTree
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.MessageCirclePlus
import com.composables.icons.lucide.Settings
import com.dokar.sonner.ToastType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.chat.AssistantPicker
import me.rerere.rikkahub.ui.components.chat.ChatInput
import me.rerere.rikkahub.ui.components.chat.ChatMessage
import me.rerere.rikkahub.ui.components.chat.rememberChatInputState
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ListSelectableItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.hooks.useThrottle
import me.rerere.rikkahub.utils.UpdateDownload
import me.rerere.rikkahub.utils.Version
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.onError
import me.rerere.rikkahub.utils.onSuccess
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, vm: ChatVM = koinViewModel()) {
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    // Handle Error
    LaunchedEffect(Unit) {
        vm.errorFlow.collect { error ->
            toaster.show(error.message ?: "Error", type = ToastType.Error)
        }
    }

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                navController = navController,
                current = conversation,
                conversations = conversations,
                loading = loadingJob != null,
                vm = vm,
                settings = setting
            )
        }
    ) {
        val inputState = rememberChatInputState()
        LaunchedEffect(loadingJob) {
            inputState.loading = loadingJob != null
        }
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        navController.navigate("menu")
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    }
                )
            },
            bottomBar = {
                ChatInput(
                    state = inputState,
                    settings = setting,
                    onCancelClick = {
                        loadingJob?.cancel()
                    },
                    enableSearch = vm.useWebSearch,
                    onToggleSearch = {
                        vm.useWebSearch = it
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                inputState.messageContent,
                                inputState.editingMessage
                            )
                        } else {
                            vm.handleMessageSend(inputState.messageContent)
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(it)
                    },
                    onUpdateProviders = {
                        vm.updateSettings(
                            setting.copy(
                                providers = it
                            )
                        )
                    },
                    onClearContext = {
                        vm.toggleContextTruncation()
                    },
                    isContextTruncated = vm.isContextTruncated
                )
            }
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                loading = loadingJob != null,
                model = currentChatModel ?: Model(),
                settings = setting,
                isContextTruncated = vm.isContextTruncated,
                contextTruncationIndex = vm.getContextTruncationIndex(),
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.messageContent = it.parts
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    vm.deleteMessage(it)
                }
            )
        }
    }
}

private const val LoadingIndicatorKey = "LoadingIndicator"
private const val ScrollBottomKey = "ScrollBottomKey"
private const val TokenUsageItemKey = "TokenUsageItemKey"

@Composable
private fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    loading: Boolean,
    model: Model,
    settings: Settings,
    isContextTruncated: Boolean = false,
    contextTruncationIndex: Int = 0,
    onRegenerate: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val viewPortSize by remember { derivedStateOf { state.layoutInfo.viewportSize } }
    var isRecentScroll by remember { mutableStateOf(false) }

    var isAtBottom by remember { mutableStateOf(false) }
    val scrollToBottom = { state.requestScrollToItem(conversation.messages.lastIndex + 5) }
    fun List<LazyListItemInfo>.isAtBottom(): Boolean {
        val lastItem = lastOrNull() ?: return false
        if (lastItem.key == LoadingIndicatorKey || lastItem.key == ScrollBottomKey || lastItem.key == TokenUsageItemKey) {
            return true
        }
        return lastItem.key == conversation.messages.lastOrNull()?.id && (lastItem.offset + lastItem.size <= state.layoutInfo.viewportEndOffset + lastItem.size * 0.1 + 32)
    }
    LaunchedEffect(state, conversation) {
        isAtBottom = state.layoutInfo.visibleItemsInfo.isAtBottom()
    }

    // 聊天选择
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .padding(innerPadding)
            .fillMaxSize(),
    ) {
        // 自动滚动到底部
        LaunchedEffect(loading, conversation.messages, viewPortSize, loading) {
            if (!state.isScrollInProgress && state.canScrollForward && loading) {
                if (state.layoutInfo.visibleItemsInfo.isAtBottom()) {
                    state.requestScrollToItem(conversation.messages.lastIndex + 10)
                }
            }
        }

        // 上下文截断状态改变时自动滚动到底部
        LaunchedEffect(isContextTruncated) {
            delay(100) // 等待UI更新
            if (state.layoutInfo.totalItemsCount > 0) {
                state.animateScrollToItem(state.layoutInfo.totalItemsCount - 1)
            }
        }

        // 判断最近是否滚动
        LaunchedEffect(state.isScrollInProgress) {
            if (state.isScrollInProgress) {
                isRecentScroll = true
                delay(1500)
                isRecentScroll = false
            } else {
                delay(1500)
                isRecentScroll = false
            }
        }

        LazyColumn(
            state = state,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            // 渲染消息，在截断点后插入指示器
            conversation.messages.forEachIndexed { index, message ->
                item(key = "message_${message.id}") {
                    ListSelectableItem(
                        key = message.id,
                        onSelectChange = {
                            if (!selectedItems.contains(message.id)) {
                                selectedItems.add(message.id)
                            } else {
                                selectedItems.remove(message.id)
                            }
                        },
                        selectedKeys = selectedItems,
                        enabled = selecting && message.isValidToShowActions(),
                    ) {
                        ChatMessage(
                            message = message,
                            showIcon = settings.displaySetting.showModelIcon,
                            model = message.modelId?.let { settings.providers.findModelById(it) },
                            onRegenerate = {
                                onRegenerate(message)
                            },
                            onEdit = {
                                onEdit(message)
                            },
                            onFork = {
                                onForkMessage(message)
                            },
                            onDelete = {
                                onDelete(message)
                            },
                            onShare = {
                                selecting = true
                                selectedItems.clear()
                                selectedItems.addAll(conversation.messages.map { it.id }
                                    .subList(0, conversation.messages.indexOf(message) + 1))
                            }
                        )
                    }
                }

                // 在截断点后插入指示器
                if (isContextTruncated && index == contextTruncationIndex - 1) {
                    item(key = "truncation_indicator") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 左侧分隔线
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            )

                            // 中间的指示器
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Lucide.Eraser,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = stringResource(R.string.chat_page_context_truncated),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)
                                )
                            }

                            // 右侧分隔线
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            )
                        }
                    }
                }
            }

            if (loading) {
                item(LoadingIndicatorKey) {
                    LoadingIndicator()
                }
            }

            if (settings.displaySetting.showTokenUsage) {
                conversation.tokenUsage?.let { usage ->
                    item(TokenUsageItemKey) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(
                                8.dp,
                                Alignment.CenterHorizontally
                            ),
                        ) {
                            Text(
                                text = "Tokens: ${usage.totalTokens}  (${usage.promptTokens} -> ${usage.completionTokens})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }



            // 为了能正确滚动到这
            item(ScrollBottomKey) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                )
            }
        }

        // 完成选择
        AnimatedVisibility(
            visible = selecting,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            enter = slideInVertically(
                initialOffsetY = { it * 2 },
            ),
            exit = slideOutVertically(
                targetOffsetY = { it * 2 },
            ),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    selecting = false
                    val messages =
                        conversation.messages.filter { it.id in selectedItems && it.isValidToShowActions() }
                    if (messages.isNotEmpty()) {
                        showExportSheet = true
                    }
                }
            ) {
                Icon(Lucide.Check, null)
            }
        }

        // 导出对话框
        ChatExportSheet(
            visible = showExportSheet,
            onDismissRequest = {
                showExportSheet = false
                selectedItems.clear()
            },
            conversation = conversation,
            selectedMessages = conversation.messages.filter { it.id in selectedItems }
        )

        // 滚动到底部按钮
        AnimatedVisibility(
            state.canScrollForward && isAtBottom,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(
                initialOffsetY = { it * 2 },
            ),
            exit = slideOutVertically(
                targetOffsetY = { it * 2 },
            ),
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(8.dp),
                onClick = {
                    scrollToBottom()
                },
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Lucide.ChevronDown,
                        contentDescription = "Scroll to bottom",
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        stringResource(R.string.chat_page_scroll_to_bottom),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // 消息快速跳转
        AnimatedVisibility(
            isRecentScroll,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(
                initialOffsetX = { it * 2 },
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { it * 2 },
            )
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = {
                        scope.launch {
                            state.animateScrollToItem(
                                (state.firstVisibleItemIndex - 1).fastCoerceAtLeast(
                                    0
                                )
                            )
                        }
                    },
                    shape = CircleShape,
                    tonalElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                        4.dp
                    ).copy(alpha = 0.65f)
                ) {
                    Icon(
                        imageVector = Lucide.ChevronUp,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(4.dp)
                    )
                }
                Surface(
                    onClick = {
                        scope.launch {
                            state.animateScrollToItem(state.firstVisibleItemIndex + 1)
                        }
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                        4.dp
                    ).copy(alpha = 0.65f)
                ) {
                    Icon(
                        imageVector = Lucide.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    drawerState: DrawerState,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        navigationIcon = {
            IconButton(
                onClick = {
                    scope.launch { drawerState.open() }
                }
            ) {
                Icon(Lucide.ListTree, "Messages")
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messages.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                }
            ) {
                Column {
                    val model = settings.providers.findModelById(settings.chatModelId)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null) {
                        Text(
                            text = model.displayName,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(Lucide.Menu, "Menu")
            }

            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(Lucide.MessageCirclePlus, "New Message")
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun DrawerContent(
    navController: NavController,
    vm: ChatVM,
    settings: Settings,
    current: Conversation,
    conversations: List<Conversation>,
    loading: Boolean,
) {
    ModalDrawerSheet(
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if(settings.displaySetting.showUpdates) {
                UpdateCard(vm)
            }
            ConversationList(
                current = current,
                conversations = conversations,
                loadings = if (loading) listOf(current.id) else emptyList(),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onClick = {
                    navController.navigate("chat/${it.id}") {
                        popUpTo("chat/${current.id}") {
                            inclusive = true
                        }
                        launchSingleTop = true
                    }
                },
                onRegenerateTitle = {
                    vm.generateTitle(it, true)
                },
                onDelete = {
                    vm.deleteConversation(it)
                    if (it.id == current.id) {
                        navigateToChatPage(navController)
                    }
                }
            )
            AssistantPicker(
                settings = settings,
                onUpdateSettings = { vm.updateSettings(it) },
                modifier = Modifier.fillMaxWidth(),
                onClickSetting = {
                    navController.navigate("assistant")
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = {
                        navController.navigate("history")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Lucide.History, "Chat History")
                    Text(
                        stringResource(R.string.chat_page_history),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                TextButton(
                    onClick = {
                        navController.navigate("setting")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Lucide.Settings, stringResource(R.string.settings))
                    Text(
                        stringResource(R.string.settings),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun UpdateCard(vm: ChatVM) {
    val state by vm.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    state.onError {
        Card {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "检查更新失败",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = it.message ?: "未知错误",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    state.onSuccess { info ->
        var showDetail by remember { mutableStateOf(false) }
        val current = remember { Version(BuildConfig.VERSION_NAME) }
        val latest = remember(info) { Version(info.version) }
        if (latest > current) {
            Card(
                onClick = {
                    showDetail = true
                }
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "发现新版本 ${info.version}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    MarkdownBlock(
                        content = info.changelog,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (showDetail) {
            val downloadHandler = useThrottle<UpdateDownload>(500) { item ->
                vm.updateChecker.downloadUpdate(context, item)
                showDetail = false
                toaster.show("已在下载，请在状态栏查看下载进度", type = ToastType.Info)
            }
            ModalBottomSheet(
                onDismissRequest = { showDetail = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = info.version,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = Instant.parse(info.publishedAt).toJavaInstant().toLocalDateTime()
                            .toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    MarkdownBlock(
                        content = info.changelog,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    info.downloads.fastForEach { downloadItem ->
                        OutlinedCard(
                            onClick = {
                                downloadHandler(downloadItem)
                            },
                        ) {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = downloadItem.name,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = downloadItem.size
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Lucide.Download,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
