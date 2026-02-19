package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Eraser
import com.composables.icons.lucide.FileArchive
import com.composables.icons.lucide.FileAudio
import com.composables.icons.lucide.Files
import com.composables.icons.lucide.Fullscreen
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Music
import com.composables.icons.lucide.Package2
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Video
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Zap
import com.dokar.sonner.ToastType
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import kotlinx.coroutines.Job
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.ui.InjectionSelector
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn
import me.rerere.rikkahub.ui.components.ui.RandomGridLoading
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import org.koin.compose.koinInject
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

enum class ExpandState {
    Collapsed,
    Files,
}

@Composable
fun ChatInput(
    state: ChatInputState,
    loading: Boolean,
    conversation: Conversation,
    settings: Settings,
    mcpManager: McpManager,
    enableSearch: Boolean,
    onToggleSearch: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onUpdateChatModel: (Model) -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onClearContext: () -> Unit,
    onCompressContext: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job,
    onCancelClick: () -> Unit,
    onSendClick: () -> Unit,
    onLongSendClick: () -> Unit,
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val toaster = LocalToaster.current
    val assistant = settings.getCurrentAssistant()

    val keyboardController = LocalSoftwareKeyboardController.current

    fun sendMessage() {
        keyboardController?.hide()
        if (loading) onCancelClick() else onSendClick()
    }

    fun sendMessageWithoutAnswer() {
        keyboardController?.hide()
        if (loading) onCancelClick() else onLongSendClick()
    }

    var expand by remember { mutableStateOf(ExpandState.Collapsed) }
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    fun dismissExpand() {
        expand = ExpandState.Collapsed
        showInjectionSheet = false
        showCompressDialog = false
    }

    fun expandToggle(type: ExpandState) {
        if (expand == type) {
            dismissExpand()
        } else {
            expand = type
        }
    }

    // Collapse when ime is visible
    val imeVisile = WindowInsets.isImeVisible
    LaunchedEffect(imeVisile, showInjectionSheet, showCompressDialog) {
        if (imeVisile && !showInjectionSheet && !showCompressDialog) {
            dismissExpand()
        }
    }

    Surface(
        color = Color.Transparent,
    ) {
        Column(
            modifier = modifier
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Medias
            MediaFileInputRow(state = state, context = context)

            // Text Input Row
            TextInputRow(
                state = state,
                context = context,
                onSendMessage = { sendMessage() }
            )

            // Actions Row
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Model Picker
                    ModelSelector(
                        modelId = assistant.chatModelId ?: settings.chatModelId,
                        providers = settings.providers,
                        onSelect = {
                            onUpdateChatModel(it)
                            dismissExpand()
                        },
                        type = ModelType.CHAT,
                        onlyIcon = true,
                        modifier = Modifier,
                    )

                    // Search
                    val enableSearchMsg = stringResource(R.string.web_search_enabled)
                    val disableSearchMsg = stringResource(R.string.web_search_disabled)
                    val chatModel = settings.getCurrentChatModel()
                    SearchPickerButton(
                        enableSearch = enableSearch,
                        settings = settings,
                        onToggleSearch = { enabled ->
                            onToggleSearch(enabled)
                            toaster.show(
                                message = if (enabled) enableSearchMsg else disableSearchMsg,
                                duration = 1.seconds,
                                type = if (enabled) {
                                    ToastType.Success
                                } else {
                                    ToastType.Normal
                                }
                            )
                        },
                        onUpdateSearchService = onUpdateSearchService,
                        model = chatModel,
                    )

                    // Reasoning
                    val model = settings.getCurrentChatModel()
                    if (model?.abilities?.contains(ModelAbility.REASONING) == true) {
                        ReasoningButton(
                            reasoningTokens = assistant.thinkingBudget ?: 0,
                            onUpdateReasoningTokens = {
                                onUpdateAssistant(assistant.copy(thinkingBudget = it))
                            },
                            onlyIcon = true,
                        )
                    }

                    // MCP
                    if (settings.mcpServers.isNotEmpty()) {
                        McpPickerButton(
                            assistant = assistant,
                            servers = settings.mcpServers,
                            mcpManager = mcpManager,
                            onUpdateAssistant = {
                                onUpdateAssistant(it)
                            },
                        )
                    }
                }

                // Insert files
                IconButton(
                    onClick = {
                        expandToggle(ExpandState.Files)
                    }
                ) {
                    Icon(
                        if (expand == ExpandState.Files) Lucide.X else Lucide.Plus,
                        stringResource(R.string.more_options)
                    )
                }

                // Send Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            enabled = loading || !state.isEmpty(),
                            onClick = {
                                dismissExpand()
                                sendMessage()
                            },
                            onLongClick = {
                                dismissExpand()
                                sendMessageWithoutAnswer()
                            }
                        )
                ) {
                    val containerColor = when {
                        loading -> MaterialTheme.colorScheme.errorContainer // 加载时，红色
                        state.isEmpty() -> MaterialTheme.colorScheme.surfaceContainerHigh // 禁用时(输入为空)，灰色
                        else -> MaterialTheme.colorScheme.primary // 启用时(输入非空)，绿色/主题色
                    }
                    val contentColor = when {
                        loading -> MaterialTheme.colorScheme.onErrorContainer
                        state.isEmpty() -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) // 禁用时，内容用带透明度的灰色
                        else -> MaterialTheme.colorScheme.onPrimary
                    }
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = CircleShape,
                        color = containerColor,
                        content = {}
                    )
                    if (loading) {
                        KeepScreenOn()
                        Icon(Lucide.X, stringResource(R.string.stop), tint = contentColor)
                    } else {
                        Icon(Lucide.ArrowUp, stringResource(R.string.send), tint = contentColor)
                    }
                }
            }

            // Expanded content
            Box(
                modifier = Modifier
                    .animateContentSize()
                    .fillMaxWidth()
            ) {
                BackHandler(
                    enabled = expand != ExpandState.Collapsed,
                ) {
                    dismissExpand()
                }
                if (expand == ExpandState.Files) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        FilesPicker(
                            conversation = conversation,
                            state = state,
                            assistant = assistant,
                            onClearContext = onClearContext,
                            onCompressContext = onCompressContext,
                            onUpdateAssistant = onUpdateAssistant,
                            showInjectionSheet = showInjectionSheet,
                            onShowInjectionSheetChange = { showInjectionSheet = it },
                            showCompressDialog = showCompressDialog,
                            onShowCompressDialogChange = { showCompressDialog = it },
                            onDismiss = { dismissExpand() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextInputRow(
    state: ChatInputState,
    context: Context,
    onSendMessage: () -> Unit,
) {
    val settings = LocalSettings.current
    val filesManager: FilesManager = koinInject()
    val assistant = settings.getCurrentAssistant()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        // TextField
        Surface(
            shape = RoundedCornerShape(32.dp),
            tonalElevation = 4.dp,
            modifier = Modifier.weight(1f)
        ) {
            Column {
                if (state.isEditing()) {
                    Surface(
                        tonalElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.editing),
                            )
                            Spacer(Modifier.weight(1f))
                            Icon(
                                Lucide.X, stringResource(R.string.cancel_edit),
                                modifier = Modifier
                                    .clickable {
                                        state.clearInput()
                                    }
                            )
                        }
                    }
                }
                var isFocused by remember { mutableStateOf(false) }
                var isFullScreen by remember { mutableStateOf(false) }
                val receiveContentListener = remember(settings.displaySetting.pasteLongTextAsFile, settings.displaySetting.pasteLongTextThreshold) {
                    ReceiveContentListener { transferableContent ->
                        when {
                            transferableContent.hasMediaType(MediaType.Image) -> {
                                transferableContent.consume { item ->
                                    val uri = item.uri
                                    if (uri != null) {
                                        state.addImages(
                                            filesManager.createChatFilesByContents(
                                                listOf(
                                                    uri
                                                )
                                            )
                                        )
                                    }
                                    uri != null
                                }
                            }

                            settings.displaySetting.pasteLongTextAsFile &&
                                transferableContent.hasMediaType(MediaType.Text) -> {
                                transferableContent.consume { item ->
                                    val text = item.text?.toString()
                                    if (text != null && text.length > settings.displaySetting.pasteLongTextThreshold) {
                                        val document = filesManager.createChatTextFile(text)
                                        state.addFiles(listOf(document))
                                        true
                                    } else {
                                        false
                                    }
                                }
                            }

                            else -> transferableContent
                        }
                    }
                }
                TextField(
                    state = state.textContent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .contentReceiver(receiveContentListener)
                        .onFocusChanged {
                            isFocused = it.isFocused
                        },
                    shape = RoundedCornerShape(32.dp),
                    placeholder = {
                        Text(stringResource(R.string.chat_input_placeholder))
                    },
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (settings.displaySetting.sendOnEnter) ImeAction.Send else ImeAction.Default
                    ),
                    onKeyboardAction = {
                        if (settings.displaySetting.sendOnEnter && !state.isEmpty()) {
                            onSendMessage()
                        }
                    },
                    colors = TextFieldDefaults.colors().copy(
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    trailingIcon = {
                        if (isFocused) {
                            IconButton(
                                onClick = {
                                    isFullScreen = !isFullScreen
                                }
                            ) {
                                Icon(Lucide.Fullscreen, null)
                            }
                        }
                    },
                    leadingIcon = if (assistant.quickMessages.isNotEmpty()) {
                        {
                            QuickMessageButton(assistant = assistant, state = state)
                        }
                    } else null,
                )
                if (isFullScreen) {
                    FullScreenEditor(state = state) {
                        isFullScreen = false
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickMessageButton(
    assistant: Assistant,
    state: ChatInputState,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            expanded = !expanded
        }
    ) {
        Icon(Lucide.Zap, null)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 200.dp)
                .width(IntrinsicSize.Min)
        ) {
            assistant.quickMessages.forEach { quickMessage ->
                Surface(
                    onClick = {
                        state.appendText(quickMessage.content)
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = quickMessage.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaFileInputRow(
    state: ChatInputState,
    context: Context
) {
    val filesManager: FilesManager = koinInject()
    fun removePart(part: UIMessagePart, url: String) {
        state.messageContent = state.messageContent.filterNot { it == part }
        if (state.shouldDeleteFileOnRemove(part)) {
            filesManager.deleteChatFiles(listOf(url.toUri()))
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        state.messageContent.filterIsInstance<UIMessagePart.Image>().fastForEach { image ->
            Box {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 4.dp
                ) {
                    AsyncImage(
                        model = image.url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = null,
                    modifier = Modifier
                        .clip(CircleShape)
                        .size(20.dp)
                        .clickable {
                            removePart(image, image.url)
                        }
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.secondary),
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }
        }
        state.messageContent.filterIsInstance<UIMessagePart.Video>().fastForEach { video ->
            Box {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 4.dp
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.Video, null)
                    }
                }
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = null,
                    modifier = Modifier
                        .clip(CircleShape)
                        .size(20.dp)
                        .clickable {
                            removePart(video, video.url)
                        }
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.secondary),
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }
        }
        state.messageContent.filterIsInstance<UIMessagePart.Audio>().fastForEach { audio ->
            Box {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 4.dp
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.FileAudio, null)
                    }
                }
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = null,
                    modifier = Modifier
                        .clip(CircleShape)
                        .size(20.dp)
                        .clickable {
                            removePart(audio, audio.url)
                        }
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.secondary),
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }
        }
        state.messageContent.filterIsInstance<UIMessagePart.Document>()
            .fastForEach { document ->
                Box {
                    Surface(
                        modifier = Modifier
                            .height(48.dp)
                            .widthIn(max = 128.dp),
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 4.dp
                    ) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.onSurface.copy(
                                0.8f
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(4.dp)
                            ) {
                                Text(
                                    text = document.fileName,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = null,
                        modifier = Modifier
                            .clip(CircleShape)
                            .size(20.dp)
                            .clickable {
                                removePart(document, document.url)
                            }
                            .align(Alignment.TopEnd)
                            .background(MaterialTheme.colorScheme.secondary),
                        tint = MaterialTheme.colorScheme.onSecondary
                    )
                }
            }
    }
}

@Composable
private fun FilesPicker(
    conversation: Conversation,
    assistant: Assistant,
    state: ChatInputState,
    onClearContext: () -> Unit,
    onCompressContext: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job,
    onUpdateAssistant: (Assistant) -> Unit,
    showInjectionSheet: Boolean,
    onShowInjectionSheetChange: (Boolean) -> Unit,
    showCompressDialog: Boolean,
    onShowCompressDialogChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val settings = LocalSettings.current
    val provider = settings.getCurrentChatModel()?.findProvider(providers = settings.providers)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TakePicButton {
                state.addImages(it)
                onDismiss()
            }

            ImagePickButton {
                state.addImages(it)
                onDismiss()
            }

            if (provider != null && provider is ProviderSetting.Google) {
                VideoPickButton {
                    state.addVideos(it)
                    onDismiss()
                }

                AudioPickButton {
                    state.addAudios(it)
                    onDismiss()
                }
            }

            FilePickButton {
                state.addFiles(it)
                onDismiss()
            }
        }

        HorizontalDivider(
            modifier = Modifier.fillMaxWidth()
        )

        // Prompt Injections
        if (settings.modeInjections.isNotEmpty() || settings.lorebooks.isNotEmpty()) {
            val activeCount = assistant.modeInjectionIds.size + assistant.lorebookIds.size
            ListItem(
                leadingContent = {
                    Icon(
                        imageVector = Lucide.BookOpen,
                        contentDescription = stringResource(R.string.chat_page_prompt_injections),
                    )
                },
                headlineContent = {
                    Text(stringResource(R.string.chat_page_prompt_injections))
                },
                trailingContent = {
                    if (activeCount > 0) {
                        Text(
                            text = activeCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                modifier = Modifier
                    .clip(MaterialTheme.shapes.large)
                    .clickable {
                        onShowInjectionSheetChange(true)
                    },
            )
        }

        // Compress History Button
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = Lucide.Package2,
                    contentDescription = stringResource(R.string.chat_page_compress_context),
                )
            },
            headlineContent = {
                Text(stringResource(R.string.chat_page_compress_context))
            },
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable {
                    onShowCompressDialogChange(true)
                },
        )

        ListItem(
            leadingContent = {
                Icon(
                    imageVector = Lucide.Eraser,
                    contentDescription = stringResource(R.string.chat_page_clear_context),
                )
            },
            headlineContent = {
                Text(stringResource(R.string.chat_page_clear_context))
            },
            trailingContent = {
                // Context Size
                val settings = LocalSettings.current
                if (settings.displaySetting.showTokenUsage && conversation.messageNodes.isNotEmpty()) {
                    val configuredContextSize = assistant.contextMessageSize
                    val effectiveMessagesAfterTruncation =
                        conversation.messageNodes.size - conversation.truncateIndex.coerceAtLeast(0)
                    val actualContextMessageCount =
                        minOf(effectiveMessagesAfterTruncation, configuredContextSize)
                    Text(
                        text = "$actualContextMessageCount/$configuredContextSize",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            },
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable(
                    onClick = {
                        onClearContext()
                    }
                ),
        )
    }

    // Injection Bottom Sheet
    if (showInjectionSheet) {
        InjectionQuickConfigSheet(
            assistant = assistant,
            settings = settings,
            onUpdateAssistant = onUpdateAssistant,
            onDismiss = { onShowInjectionSheetChange(false) }
        )
    }

    // Compress Context Dialog
    if (showCompressDialog) {
        CompressContextDialog(
            onDismiss = {
                onShowCompressDialogChange(false)
                onDismiss()
            },
            onConfirm = { additionalPrompt, targetTokens, keepRecentMessages ->
                onCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
            }
        )
    }
}

@Composable
private fun FullScreenEditor(
    state: ChatInputState,
    onDone: () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = {
            onDone()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(
                            onClick = {
                                onDone()
                            }
                        ) {
                            Text(stringResource(R.string.chat_page_save))
                        }
                    }
                    TextField(
                        state = state.textContent,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .fillMaxSize(),
                        shape = RoundedCornerShape(32.dp),
                        placeholder = {
                            Text(stringResource(R.string.chat_input_placeholder))
                        },
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun useCropLauncher(
    onCroppedImageReady: (Uri) -> Unit,
    onCleanup: (() -> Unit)? = null
): Pair<ActivityResultLauncher<Intent>, (Uri) -> Unit> {
    val context = LocalContext.current
    var cropOutputUri by remember { mutableStateOf<Uri?>(null) }

    val cropActivityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            cropOutputUri?.let { croppedUri ->
                onCroppedImageReady(croppedUri)
            }
        }
        // Clean up crop output file
        cropOutputUri?.toFile()?.delete()
        cropOutputUri = null
        onCleanup?.invoke()
    }

    val launchCrop: (Uri) -> Unit = { sourceUri ->
        val outputFile = File(context.appTempFolder, "crop_output_${System.currentTimeMillis()}.jpg")
        cropOutputUri = Uri.fromFile(outputFile)

        val cropIntent = UCrop.of(sourceUri, cropOutputUri!!)
            .withOptions(UCrop.Options().apply {
                setFreeStyleCropEnabled(true)
                setAllowedGestures(
                    UCropActivity.SCALE,
                    UCropActivity.ROTATE,
                    UCropActivity.NONE
                )
                setCompressionFormat(Bitmap.CompressFormat.PNG)
            })
            .withMaxResultSize(4096, 4096)
            .getIntent(context)

        cropActivityLauncher.launch(cropIntent)
    }

    return Pair(cropActivityLauncher, launchCrop)
}

@Composable
private fun ImagePickButton(onAddImages: (List<Uri>) -> Unit = {}) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val filesManager: FilesManager = koinInject()

    val (_, launchCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            onAddImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
        }
    )

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { selectedUris ->
        if (selectedUris.isNotEmpty()) {
            Log.d("ImagePickButton", "Selected URIs: $selectedUris")
            // Check if we should skip crop based on settings
            if (settings.displaySetting.skipCropImage) {
                // Skip crop, directly add images
                onAddImages(filesManager.createChatFilesByContents(selectedUris))
            } else {
                // Show crop interface
                if (selectedUris.size == 1) {
                    // Single image - offer crop
                    launchCrop(selectedUris.first())
                } else {
                    // Multiple images - no crop
                    onAddImages(filesManager.createChatFilesByContents(selectedUris))
                }
            }
        } else {
            Log.d("ImagePickButton", "No images selected")
        }
    }

    BigIconTextButton(
        icon = {
            Icon(Lucide.Image, null)
        },
        text = {
            Text(stringResource(R.string.photo))
        }
    ) {
        imagePickerLauncher.launch("image/*")
    }
}

@Composable
fun TakePicButton(onAddImages: (List<Uri>) -> Unit = {}) {
    val cameraPermission = rememberPermissionState(PermissionCamera)

    val context = LocalContext.current
    val settings = LocalSettings.current
    val filesManager: FilesManager = koinInject()
    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }

    val (_, launchCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            onAddImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
        },
        onCleanup = {
            // Clean up camera temp file after cropping is done
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            // Check if we should skip crop based on settings
            if (settings.displaySetting.skipCropImage) {
                // Skip crop, directly add image
                onAddImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))
                // Clean up camera temp file
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
            } else {
                // Show crop interface
                launchCrop(cameraOutputUri!!)
            }
        } else {
            // Clean up camera temp file if capture failed
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }

    // 使用权限管理器包装
    PermissionManager(
        permissionState = cameraPermission
    ) {
        BigIconTextButton(
            icon = {
                Icon(Lucide.Camera, null)
            },
            text = {
                Text(stringResource(R.string.take_picture))
            }
        ) {
            if (cameraPermission.allRequiredPermissionsGranted) {
                // 权限已授权，直接启动相机
                cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
                cameraOutputUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    cameraOutputFile!!
                )
                cameraLauncher.launch(cameraOutputUri!!)
            } else {
                // 请求权限
                cameraPermission.requestPermissions()
            }
        }
    }
}

@Composable
fun VideoPickButton(onAddVideos: (List<Uri>) -> Unit = {}) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { selectedUris ->
        if (selectedUris.isNotEmpty()) {
            onAddVideos(filesManager.createChatFilesByContents(selectedUris))
        }
    }

    BigIconTextButton(
        icon = {
            Icon(Lucide.Video, null)
        },
        text = {
            Text(stringResource(R.string.video))
        }
    ) {
        videoPickerLauncher.launch("video/*")
    }
}

@Composable
fun AudioPickButton(onAddAudios: (List<Uri>) -> Unit = {}) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { selectedUris ->
        if (selectedUris.isNotEmpty()) {
            onAddAudios(filesManager.createChatFilesByContents(selectedUris))
        }
    }

    BigIconTextButton(
        icon = {
            Icon(Lucide.Music, null)
        },
        text = {
            Text(stringResource(R.string.audio))
        }
    ) {
        audioPickerLauncher.launch("audio/*")
    }
}

@Composable
fun FilePickButton(onAddFiles: (List<UIMessagePart.Document>) -> Unit = {}) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    val pickMedia =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val allowedMimeTypes = setOf(
                    "text/plain",
                    "text/html",
                    "text/css",
                    "text/javascript",
                    "text/csv",
                    "text/xml",
                    "application/json",
                    "application/javascript",
                    "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                )

                val documents = uris.mapNotNull { uri ->
                    val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                    val mime = filesManager.getFileMimeType(uri) ?: "text/plain"

                    // Filter by MIME type or file extension
                    val isAllowed = allowedMimeTypes.contains(mime) ||
                        mime.startsWith("text/") ||
                        mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                        mime == "application/pdf" ||
                        fileName.endsWith(".txt", ignoreCase = true) ||
                        fileName.endsWith(".md", ignoreCase = true) ||
                        fileName.endsWith(".csv", ignoreCase = true) ||
                        fileName.endsWith(".json", ignoreCase = true) ||
                        fileName.endsWith(".js", ignoreCase = true) ||
                        fileName.endsWith(".html", ignoreCase = true) ||
                        fileName.endsWith(".css", ignoreCase = true) ||
                        fileName.endsWith(".xml", ignoreCase = true) ||
                        fileName.endsWith(".py", ignoreCase = true) ||
                        fileName.endsWith(".java", ignoreCase = true) ||
                        fileName.endsWith(".kt", ignoreCase = true) ||
                        fileName.endsWith(".ts", ignoreCase = true) ||
                        fileName.endsWith(".tsx", ignoreCase = true) ||
                        fileName.endsWith(".md", ignoreCase = true) ||
                        fileName.endsWith(".markdown", ignoreCase = true) ||
                        fileName.endsWith(".mdx", ignoreCase = true) ||
                        fileName.endsWith(".yml", ignoreCase = true) ||
                        fileName.endsWith(".yaml", ignoreCase = true)

                    if (isAllowed) {
                        val localUri = filesManager.createChatFilesByContents(listOf(uri))[0]
                        UIMessagePart.Document(
                            url = localUri.toString(),
                            fileName = fileName,
                            mime = mime
                        )
                    } else {
                        toaster.show("不支持的文件类型: $fileName", type = ToastType.Error)
                        null
                    }
                }

                if (documents.isNotEmpty()) {
                    onAddFiles(documents)
                }
            }
        }
    BigIconTextButton(
        icon = {
            Icon(Lucide.Files, null)
        },
        text = {
            Text(stringResource(R.string.upload_file))
        }
    ) {
        pickMedia.launch(arrayOf("*/*"))
    }
}


@Composable
private fun BigIconTextButton(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .semantics {
                role = Role.Button
            }
            .wrapContentWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Surface(
            tonalElevation = 2.dp,
            shape = RoundedCornerShape(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 32.dp, vertical = 16.dp)
            ) {
                icon()
            }
        }
        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
            text()
        }
    }
}

@Composable
private fun InjectionQuickConfigSheet(
    assistant: Assistant,
    settings: Settings,
    onUpdateAssistant: (Assistant) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 16.dp),
        ) {
            InjectionSelector(
                assistant = assistant,
                settings = settings,
                onUpdate = onUpdateAssistant,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BigIconTextButtonPreview() {
    Row(
        modifier = Modifier.padding(16.dp)
    ) {
        BigIconTextButton(
            icon = {
                Icon(Lucide.Image, null)
            },
            text = {
                Text(stringResource(R.string.photo))
            }
        ) {}
    }
}
