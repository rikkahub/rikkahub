package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDownDouble
import me.rerere.hugeicons.stroke.ArrowUpDouble
import me.rerere.hugeicons.stroke.BubbleChatTemporary
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.InspectCode
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.ai.tools.termux.TermuxDirectCommandParser
import me.rerere.rikkahub.data.event.ChatComposerBridge
import me.rerere.rikkahub.data.event.ChatHistoryBridge
import me.rerere.rikkahub.data.event.replaceHistoryText
import me.rerere.rikkahub.data.event.toChatHistorySnapshot
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.effectiveUserName
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.components.ui.LuneTopBarSurface
import me.rerere.rikkahub.ui.components.ui.luneGlassBorderColor
import me.rerere.rikkahub.ui.components.ui.luneGlassContainerColor
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.getActivity
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

internal fun draftSubmissionRequiresModel(
    isEditing: Boolean,
    answer: Boolean,
    isTermuxDirect: Boolean,
): Boolean {
    return !isEditing && answer && !isTermuxDirect
}

@Composable
private fun ChatStatusBarImmersiveEffect() {
    val context = LocalContext.current
    val view = LocalView.current

    DisposableEffect(context, view) {
        if (view.isInEditMode) return@DisposableEffect onDispose {}

        val activity = context.getActivity() ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(activity.window, view)
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())

        onDispose {
            controller.systemBarsBehavior = previousBehavior
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Hide keyboard when drawer is open
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(vm) {
        if (nodeId == null && !vm.chatListInitialized && chatListState.layoutInfo.totalItemsCount > 0) {
            chatListState.scrollToItem(chatListState.layoutInfo.totalItemsCount)
            vm.chatListInitialized = true
        }
    }

    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (nodeId != null && conversation.messageNodes.isNotEmpty() && !vm.chatListInitialized) {
            val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
            if (index >= 0) {
                chatListState.scrollToItem(index)
            }
            vm.chatListInitialized = true
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

private fun Conversation.findCurrentMessageByNodeId(nodeId: String): UIMessage? {
    return messageNodes.firstOrNull { it.id.toString() == nodeId }?.currentMessage
}

private fun Settings.resolveHistoryAssistantName(conversation: Conversation): String {
    return getAssistantById(conversation.assistantId)
        ?.stCharacterData
        ?.name
        ?.takeIf { it.isNotBlank() }
        ?: getAssistantById(conversation.assistantId)
            ?.name
            ?.takeIf { it.isNotBlank() }
        ?: "Assistant"
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val chatComposerBridge: ChatComposerBridge = koinInject()
    val chatHistoryBridge: ChatHistoryBridge = koinInject()
    val runtimeInspection by vm.runtimeInspection.collectAsStateWithLifecycle()
    val selectModelFirstText = stringResource(R.string.chat_page_select_model_first)
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var topBarVisible by rememberSaveable { mutableStateOf(true) }
    var inspectorVisible by rememberSaveable { mutableStateOf(false) }
    var inspectorTab by rememberSaveable { mutableStateOf(ChatRuntimeInspectorTab.PROMPTS) }
    val enableGlassBlur = setting.displaySetting.enableBlurEffect
    val hazeState = rememberHazeState()
    val activeHazeState = if (enableGlassBlur) hazeState else null
    val currentDraftText = inputState.textContent.text.toString()
    val historyUserName = setting.effectiveUserName().ifBlank { "User" }
    val historyAssistantName = setting.resolveHistoryAssistantName(conversation)
    val chatHistorySnapshot = remember(
        conversation.id,
        conversation.messageNodes,
        historyUserName,
        historyAssistantName,
    ) {
        conversation.toChatHistorySnapshot(
            userName = historyUserName,
            assistantName = historyAssistantName,
        )
    }

    fun submitDraft(
        answer: Boolean,
        overrideText: String? = null,
    ): Boolean {
        if (loadingJob != null) {
            loadingJob.cancel()
            return false
        }

        if (overrideText != null) {
            inputState.setMessageText(overrideText)
        }

        val contents = inputState.getContents()
        val termuxDirect = if (inputState.isEditing()) {
            null
        } else {
            TermuxDirectCommandParser.parse(
                parts = contents,
                commandModeEnabled = setting.termuxCommandModeEnabled
            )
        }

        if (
            currentChatModel == null && draftSubmissionRequiresModel(
                isEditing = inputState.isEditing(),
                answer = answer,
                isTermuxDirect = termuxDirect?.isDirect == true,
            )
        ) {
            toaster.show(
                selectModelFirstText,
                type = ToastType.Error,
            )
            return false
        }

        if (inputState.isEditing()) {
            vm.handleMessageEdit(
                parts = contents,
                messageId = inputState.editingMessage!!,
            )
        } else {
            vm.handleMessageSend(
                content = contents,
                answer = answer,
                forceTermuxCommandMode = setting.termuxCommandModeEnabled
            )
            scope.launch {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
        }
        inputState.clearInput()
        return true
    }

    val latestCurrentDraftText by rememberUpdatedState(currentDraftText)
    val latestSubmitDraft by rememberUpdatedState(::submitDraft)
    val latestInputState by rememberUpdatedState(inputState)
    val latestConversation by rememberUpdatedState(conversation)
    val latestLoadingJob by rememberUpdatedState(loadingJob)

    SideEffect {
        chatComposerBridge.updateDraftTextSnapshot(currentDraftText)
    }

    LaunchedEffect(chatHistoryBridge, chatHistorySnapshot) {
        chatHistoryBridge.updateSnapshot(chatHistorySnapshot)
    }

    DisposableEffect(chatComposerBridge, chatHistoryBridge) {
        val composerDelegate = object : ChatComposerBridge.Delegate {
            override fun replaceDraftText(text: String) {
                latestInputState.setMessageText(text)
            }

            override fun appendDraftText(text: String) {
                latestInputState.appendText(text)
            }

            override fun submitDraft(answer: Boolean, overrideText: String?) {
                latestSubmitDraft(answer, overrideText)
            }
        }
        val historyDelegate = object : ChatHistoryBridge.Delegate {
            override fun editMessage(nodeId: String, text: String) {
                if (latestLoadingJob != null) {
                    vm.showEditBlockedWhileGeneratingError()
                    return
                }

                val targetMessage = latestConversation.findCurrentMessageByNodeId(nodeId) ?: return
                vm.handleMessageEdit(
                    parts = targetMessage.parts.replaceHistoryText(text),
                    messageId = targetMessage.id,
                )
            }

            override fun deleteMessage(nodeId: String) {
                if (latestLoadingJob != null) {
                    vm.showDeleteBlockedWhileGeneratingError()
                    return
                }

                val targetMessage = latestConversation.findCurrentMessageByNodeId(nodeId) ?: return
                vm.deleteMessage(targetMessage)
            }

            override fun selectMessageNode(nodeId: String, selectIndex: Int) {
                val targetNode = latestConversation.messageNodes.firstOrNull { it.id.toString() == nodeId } ?: return
                vm.selectMessageNode(targetNode.id, selectIndex)
            }

            override fun regenerateMessage(nodeId: String, regenerateAssistantMessage: Boolean) {
                val targetMessage = latestConversation.findCurrentMessageByNodeId(nodeId) ?: return
                vm.regenerateAtMessage(targetMessage, regenerateAssistantMessage)
            }

            override fun continueMessage(nodeId: String) {
                val targetMessage = latestConversation.findCurrentMessageByNodeId(nodeId) ?: return
                vm.continueAssistantMessage(targetMessage)
            }
        }
        chatComposerBridge.register(composerDelegate)
        chatHistoryBridge.register(historyDelegate)
        onDispose {
            chatComposerBridge.updateDraftTextSnapshot(latestCurrentDraftText)
            chatComposerBridge.unregister(composerDelegate)
            chatHistoryBridge.unregister(historyDelegate)
        }
    }

    ChatStatusBarImmersiveEffect()
    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(setting = setting)
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    AnimatedVisibility(
                        visible = topBarVisible,
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.displayCutout)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top) + scaleIn(initialScale = 0.96f),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top) + scaleOut(targetScale = 0.96f),
                    ) {
                        LuneTopBarSurface(
                            hazeState = activeHazeState,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TopBar(
                                settings = setting,
                                conversation = conversation,
                                bigScreen = bigScreen,
                                drawerState = drawerState,
                                previewMode = previewMode,
                                onNewChat = {
                                    navigateToChatPage(navController)
                                },
                                onToggleTemporaryConversation = {
                                    vm.toggleTemporaryConversation()
                                },
                                onClickMenu = {
                                    previewMode = !previewMode
                                },
                                onUpdateTitle = {
                                    vm.updateTitle(it)
                                },
                                onHideTopBar = {
                                    topBarVisible = false
                                },
                                onOpenRuntimeInspector = {
                                    inspectorTab = ChatRuntimeInspectorTab.PROMPTS
                                    inspectorVisible = true
                                    vm.refreshRuntimeInspection()
                                }
                            )
                        }
                    }
                },
                bottomBar = {
                    ChatInput(
                        state = inputState,
                        loading = loadingJob != null,
                        settings = setting,
                        conversation = conversation,
                        mcpManager = vm.mcpManager,
                        hazeState = activeHazeState,
                        onCancelClick = {
                            loadingJob?.cancel()
                        },
                        enableSearch = enableWebSearch,
                        termuxCommandModeEnabled = setting.termuxCommandModeEnabled,
                        codeBlockRichRenderEnabled = setting.displaySetting.enableCodeBlockRichRender,
                        onToggleSearch = {
                            vm.updateSettings(setting.copy(enableWebSearch = !enableWebSearch))
                        },
                        onToggleTermuxCommandMode = {
                            vm.updateSettings(setting.copy(termuxCommandModeEnabled = it))
                        },
                        onToggleCodeBlockRichRender = {
                            vm.updateSettings(
                                setting.copy(
                                    displaySetting = setting.displaySetting.copy(
                                        enableCodeBlockRichRender = it
                                    )
                                )
                            )
                        },
                        onSendClick = {
                            submitDraft(answer = true)
                        },
                        onLongSendClick = {
                            submitDraft(answer = false)
                        },
                        onUpdateChatModel = {
                            vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                        },
                        onUpdateAssistant = {
                            vm.updateSettings(
                                setting.copy(
                                    assistants = setting.assistants.map { assistant ->
                                        if (assistant.id == it.id) {
                                            it
                                        } else {
                                            assistant
                                        }
                                    }
                                )
                            )
                        },
                        onUpdateSearchService = { index ->
                            vm.updateSettings(
                                setting.copy(
                                    searchServiceSelected = index
                                )
                            )
                        },
                        onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                            vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
                        },
                    )
                },
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { innerPadding ->
                ChatList(
                    innerPadding = innerPadding,
                    conversation = conversation,
                    state = chatListState,
                    loading = loadingJob != null,
                    previewMode = previewMode,
                    topBarVisible = topBarVisible,
                    settings = setting,
                    hazeState = hazeState,
                    errors = errors,
                    onDismissError = onDismissError,
                    onClearAllErrors = onClearAllErrors,
                    onRegenerate = {
                        vm.regenerateAtMessage(it)
                    },
                    onContinue = {
                        vm.continueAssistantMessage(it)
                    },
                    onEdit = {
                        inputState.editingMessage = it.id
                        inputState.setContents(it.parts)
                    },
                    onForkMessage = {
                        scope.launch {
                            val fork = vm.forkMessage(message = it)
                            navigateToChatPage(navController, chatId = fork.id)
                        }
                    },
                    onDelete = {
                        if (loadingJob != null) {
                            vm.showDeleteBlockedWhileGeneratingError()
                        } else {
                            vm.deleteMessage(it)
                        }
                    },
                    onUpdateMessage = { newNode ->
                        vm.updateConversation(
                            conversation.copy(
                                messageNodes = conversation.messageNodes.map { node ->
                                    if (node.id == newNode.id) {
                                        newNode
                                    } else {
                                        node
                                    }
                                }
                            ))
                        vm.saveConversationAsync()
                    },
                    onClickSuggestion = { suggestion ->
                        inputState.editingMessage = null
                        inputState.setMessageText(suggestion)
                    },
                    onTranslate = { message, locale ->
                        vm.translateMessage(message, locale)
                    },
                    onClearTranslation = { message ->
                        vm.clearTranslationField(message.id)
                    },
                    onJumpToMessage = { index ->
                        previewMode = false
                        scope.launch {
                            chatListState.animateScrollToItem(index)
                        }
                    },
                    onToolApproval = { toolCallId, approved, reason ->
                        vm.handleToolApproval(toolCallId, approved, reason)
                    },
                    onToolAnswer = { toolCallId, answer ->
                        vm.handleToolAnswer(toolCallId, answer)
                    },
                    onToggleFavorite = { node ->
                        vm.toggleMessageFavorite(node)
                    },
                    showSuggestions = !inputState.isEditing() &&
                        inputState.textContent.text.isEmpty() &&
                        inputState.messageContent.isEmpty(),
                )
            }

            AnimatedVisibility(
                visible = !topBarVisible,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(top = 8.dp, end = 12.dp),
                enter = fadeIn() + scaleIn(initialScale = 0.92f),
                exit = fadeOut() + scaleOut(targetScale = 0.92f),
            ) {
                Surface(
                    onClick = {
                        topBarVisible = true
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = luneGlassContainerColor(),
                    border = androidx.compose.foundation.BorderStroke(1.dp, luneGlassBorderColor()),
                ) {
                    Icon(
                        imageVector = HugeIcons.ArrowDownDouble,
                        contentDescription = stringResource(R.string.more_options),
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .size(18.dp)
                    )
                }
            }

            if (inspectorVisible) {
                ChatRuntimeInspectorSheet(
                    state = runtimeInspection,
                    initialTab = inspectorTab,
                    onDismissRequest = {
                        inspectorVisible = false
                    },
                    onRefresh = {
                        vm.refreshRuntimeInspection()
                    },
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onToggleTemporaryConversation: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    onHideTopBar: () -> Unit,
    onOpenRuntimeInspector: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }
    val showTemporaryConversationAction = conversation.newConversation && conversation.messageNodes.isEmpty()
    val temporaryConversationEnabled = conversation.isTemporaryConversation
    val newChatText = stringResource(R.string.chat_page_new_chat)
    val temporaryConversationText = stringResource(R.string.chat_page_temporary_chat)
    val temporaryConversationEnabledText = stringResource(R.string.chat_page_temporary_chat_enabled)
    val temporaryConversationDisabledText = stringResource(R.string.chat_page_temporary_chat_disabled)

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        windowInsets = WindowInsets(0, 0, 0, 0),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }
                ) {
                    Icon(HugeIcons.Menu03, stringResource(R.string.chat_page_messages))
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Text(
                    text = conversation.title.ifBlank {
                        if (temporaryConversationEnabled) {
                            temporaryConversationText
                        } else {
                            newChatText
                        }
                    },
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            IconButton(
                onClick = {
                    if (showTemporaryConversationAction) {
                        onToggleTemporaryConversation()
                        toaster.show(
                            if (temporaryConversationEnabled) {
                                temporaryConversationDisabledText
                            } else {
                                temporaryConversationEnabledText
                            },
                            type = if (temporaryConversationEnabled) {
                                ToastType.Info
                            } else {
                                ToastType.Success
                            }
                        )
                    } else {
                        onNewChat()
                    }
                }
            ) {
                Icon(
                    imageVector = if (showTemporaryConversationAction) {
                        HugeIcons.BubbleChatTemporary
                    } else {
                        HugeIcons.MessageAdd01
                    },
                    contentDescription = if (showTemporaryConversationAction) {
                        temporaryConversationText
                    } else {
                        newChatText
                    },
                    tint = if (temporaryConversationEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        LocalContentColor.current
                    }
                )
            }

            var showOverflowMenu by rememberSaveable { mutableStateOf(false) }
            IconButton(onClick = { showOverflowMenu = true }) {
                Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options))
            }
            DropdownMenu(
                expanded = showOverflowMenu,
                onDismissRequest = { showOverflowMenu = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (previewMode) {
                                stringResource(R.string.chat_page_back_to_conversation)
                            } else {
                                stringResource(R.string.chat_page_search_chats)
                            }
                        )
                    },
                    onClick = {
                        showOverflowMenu = false
                        onClickMenu()
                    },
                    leadingIcon = {
                        Icon(
                            if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet,
                            null
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_page_hide_top_bar)) },
                    onClick = {
                        showOverflowMenu = false
                        onHideTopBar()
                    },
                    leadingIcon = {
                        Icon(HugeIcons.ArrowUpDouble, null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("运行时检查") },
                    onClick = {
                        showOverflowMenu = false
                        onOpenRuntimeInspector()
                    },
                    leadingIcon = {
                        Icon(HugeIcons.InspectCode, null)
                    }
                )
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
