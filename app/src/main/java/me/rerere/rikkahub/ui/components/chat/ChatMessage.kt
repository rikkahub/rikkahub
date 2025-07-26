package me.rerere.rikkahub.ui.components.chat

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.composables.icons.lucide.BookDashed
import com.composables.icons.lucide.BookHeart
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Expand
import com.composables.icons.lucide.File
import com.composables.icons.lucide.GitFork
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Share
import com.composables.icons.lucide.TextSelect
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.Wrench
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.highlight.HighlightText
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.copyMessageToClipboard
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.rikkahub.utils.urlDecode
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

private val EmptyJson = JsonObject(emptyMap())

@Composable
fun ChatMessage(
    node: MessageNode,
    conversation: Conversation,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    showIcon: Boolean = true,
    model: Model? = null,
    assistant: Assistant? = null,
    showActions: Boolean,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit
) {
    val message = node.messages[node.selectIndex]
    val settings = LocalSettings.current.displaySetting
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!message.parts.isEmptyUIMessage()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ModelIcon(
                    showIcon = showIcon,
                    message = message,
                    model = model,
                    assistant = assistant,
                    loading = loading,
                    modifier = Modifier.weight(1f)
                )
                UserAvatar(
                    message = message,
                    avatar = settings.userAvatar,
                    nickname = settings.userNickname,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        ProvideTextStyle(textStyle) {
            MessagePartsBlock(
                role = message.role,
                parts = message.parts,
                annotations = message.annotations,
                messages = conversation.currentMessages,
                messageIndex = conversation.currentMessages.indexOf(message),
                loading = loading
            )
        }
        AnimatedVisibility(
            visible = showActions,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            Column(
                modifier = Modifier.animateContentSize()
            ) {
                Actions(
                    message = message,
                    onRegenerate = onRegenerate,
                    node = node,
                    onUpdate = onUpdate,
                    onOpenActionSheet = {
                        showActionsSheet = true
                    }
                )
            }
        }
    }
    if (showActionsSheet) {
        LongPressActionsSheet(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showSelectCopySheet) {
        SelectAndCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}

@Composable
private fun LongPressActionsSheet(
    message: UIMessage,
    model: Model?,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onFork: () -> Unit,
    onSelectAndCopy: () -> Unit,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Select and Copy
            Card(
                onClick = {
                    onDismissRequest()
                    onSelectAndCopy()
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Lucide.TextSelect,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.select_and_copy),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }


            // Edit
            Card(
                onClick = {
                    onDismissRequest()
                    onEdit()
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Lucide.Pencil,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.edit),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Share
            Card(
                onClick = {
                    onDismissRequest()
                    onShare()
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Lucide.Share,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.share),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Create a Fork
            Card(
                onClick = {
                    onDismissRequest()
                    onFork()
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Lucide.GitFork,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.create_fork),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Delete
            Card(
                onClick = {
                    onDismissRequest()
                    onDelete()
                },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Lucide.Trash2,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.delete),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Message Info
            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                Text(message.createdAt.toJavaLocalDateTime().toLocalString())
                if (model != null) {
                    Text(model.displayName)
                }
            }
        }
    }
}

@Composable
private fun SelectAndCopySheet(
    message: UIMessage,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        onDismissRequest()
                    }
                ) {
                    Icon(Lucide.X, null)
                }

                Text(
                    text = stringResource(R.string.select_and_copy),
                    style = MaterialTheme.typography.headlineSmall,
                )

                TextButton(
                    onClick = {
                        context.copyMessageToClipboard(message)
                        onDismissRequest()
                    }
                ) {
                    Icon(
                        imageVector = Lucide.Copy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.copy_all))
                }
            }

            // Content
            val textParts =
                message.parts.filterIsInstance<UIMessagePart.Text>().filter { it.text.isNotBlank() }

            if (textParts.isEmpty()) {
                // No text content available
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_text_content_to_copy),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        textParts.fastForEach { textPart ->
                            Text(
                                text = textPart.text,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserAvatar(
    message: UIMessage,
    avatar: Avatar,
    nickname: String,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    if (message.role == MessageRole.USER && !message.parts.isEmptyUIMessage() && settings.displaySetting.showUserAvatar) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier,
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = nickname.ifEmpty { stringResource(R.string.user_default_name) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    text = message.createdAt.toJavaLocalDateTime().toLocalString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(alpha = 0.8f),
                    maxLines = 1,
                )
            }
            UIAvatar(
                name = nickname,
                modifier = Modifier.size(36.dp),
                value = avatar,
                loading = false,
            )
        }
    }
}

@Composable
private fun ModelIcon(
    showIcon: Boolean,
    message: UIMessage,
    loading: Boolean,
    model: Model?,
    assistant: Assistant?,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    if (showIcon && message.role == MessageRole.ASSISTANT && !message.parts.isEmptyUIMessage() && model != null) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (assistant?.useAssistantAvatar == true) {
                UIAvatar(
                    name = assistant.name,
                    modifier = Modifier.size(36.dp),
                    value = assistant.avatar,
                    loading = loading,
                )
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = assistant.name.ifEmpty { stringResource(R.string.assistant_page_default_assistant) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = message.createdAt.toJavaLocalDateTime().toLocalString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current.copy(alpha = 0.8f),
                            maxLines = 1,
                        )
                        if (settings.displaySetting.showTokenUsage) {
                            message.usage?.let { usage ->
                                Text(
                                    text = "${usage.totalTokens.formatNumber()} tokens",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalContentColor.current.copy(alpha = 0.8f),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            } else {
                AutoAIIcon(
                    name = model.modelId,
                    modifier = Modifier.size(36.dp),
                    loading = loading
                )
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = message.createdAt.toJavaLocalDateTime().toLocalString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current.copy(alpha = 0.8f)
                        )
                        if (settings.displaySetting.showTokenUsage) {
                            message.usage?.let { usage ->
                                Text(
                                    text = "${usage.totalTokens} tokens",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalContentColor.current.copy(alpha = 0.8f),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Actions(
    message: UIMessage,
    node: MessageNode,
    onUpdate: (MessageNode) -> Unit,
    onRegenerate: () -> Unit,
    onOpenActionSheet: () -> Unit,
) {
    val context = LocalContext.current
    var showInformation by remember { mutableStateOf(false) }
    var isPendingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(isPendingDelete) {
        if (isPendingDelete) {
            delay(3000) // 3秒后自动取消
            isPendingDelete = false
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Lucide.Copy, stringResource(R.string.copy), modifier = Modifier
                .clip(CircleShape)
                .clickable { context.copyMessageToClipboard(message) }
                .padding(8.dp)
                .size(16.dp)
        )

        Icon(
            Lucide.RefreshCw, stringResource(R.string.regenerate), modifier = Modifier
                .clip(CircleShape)
                .clickable { onRegenerate() }
                .padding(8.dp)
                .size(16.dp)
        )

        if (message.role == MessageRole.ASSISTANT) {
            val tts = LocalTTSState.current
            val isSpeaking by tts.isSpeaking.collectAsState()
            val isAvailable by tts.isAvailable.collectAsState()
            Icon(
                imageVector = if (isSpeaking) Lucide.CircleStop else Lucide.Volume2,
                contentDescription = stringResource(R.string.tts),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        enabled = isAvailable,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (!isSpeaking) {
                                tts.speak(message.toText())
                            } else {
                                tts.stop()
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp),
                tint = if (isAvailable) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f)
            )
        }

        Icon(
            imageVector = Lucide.Ellipsis,
            contentDescription = "More Options",
            modifier = Modifier
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    onClick = {
                        onOpenActionSheet()
                    }
                )
                .padding(8.dp)
                .size(16.dp)
        )

        MessageNodePagerButtons(
            node = node,
            onUpdate = onUpdate,
        )
    }
}

@Composable
private fun MessageNodePagerButtons(
    node: MessageNode,
    modifier: Modifier = Modifier,
    onUpdate: (MessageNode) -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (node.messages.size > 1) {
            Icon(
                imageVector = Lucide.ChevronLeft,
                contentDescription = "Prev",
                modifier = Modifier
                    .clip(CircleShape)
                    .alpha(if (node.selectIndex == 0) 0.5f else 1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (node.selectIndex > 0) {
                                onUpdate(
                                    node.copy(
                                        selectIndex = node.selectIndex - 1
                                    )
                                )
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp)
            )

            Text(
                text = "${node.selectIndex + 1}/${node.messages.size}",
                style = MaterialTheme.typography.bodySmall
            )

            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = "Next",
                modifier = Modifier
                    .clip(CircleShape)
                    .alpha(if (node.selectIndex == node.messages.lastIndex) 0.5f else 1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (node.selectIndex < node.messages.lastIndex) {
                                onUpdate(
                                    node.copy(
                                        selectIndex = node.selectIndex + 1
                                    )
                                )
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp),
            )
        }
    }
}

@Composable
private fun MessagePartsBlock(
    role: MessageRole,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    messages: List<UIMessage>,
    messageIndex: Int,
    loading: Boolean
) {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

    fun handleClickCitation(citationId: String) {
        messages.forEach { message ->
            message.parts.forEach { part ->
                if (part is UIMessagePart.ToolResult && part.toolName == "search_web") {
                    val items = part.content.jsonObject["items"]?.jsonArray ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return
                        }
                    }
                }
            }
        }
    }

    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val settings = LocalSettings.current
    LaunchedEffect(parts) {
        if (parts.isNotEmpty() && loading && settings.displaySetting.enableMessageGenerationHapticEffect) {
            launch(Dispatchers.Default) {
                repeat(3) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    delay(80)
                }
            }
        }
    }

    // Reasoning
    parts.filterIsInstance<UIMessagePart.Reasoning>().fastForEach { reasoning ->
        ReasoningCard(
            reasoning = reasoning,
        )
    }

    // Text
    parts.filterIsInstance<UIMessagePart.Text>().fastForEach { part ->
        SelectionContainer {
            if (role == MessageRole.USER) {
                Card(
                    modifier = Modifier
                        .animateContentSize(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        MarkdownBlock(
                            content = part.text,
                            onClickCitation = { id ->
                                handleClickCitation(id)
                            }
                        )
                    }
                }
            } else {
                MarkdownBlock(
                    content = part.text,
                    onClickCitation = { id ->
                        handleClickCitation(id)
                    },
                    modifier = Modifier.animateContentSize()
                )
            }
        }
    }

    // Tool Calls
    if (messageIndex == messages.lastIndex) {
        parts.filterIsInstance<UIMessagePart.ToolCall>().fastForEachIndexed { index, toolCall ->
            key(index) {
                ToolCallItem(
                    toolName = toolCall.toolName,
                    arguments = runCatching { JsonInstant.parseToJsonElement(toolCall.arguments) }
                        .getOrElse { EmptyJson },
                    content = null,
                    loading = loading,
                )
            }
        }
    }
    parts.filterIsInstance<UIMessagePart.ToolResult>().fastForEachIndexed { index, toolCall ->
        key(index) {
            ToolCallItem(
                toolName = toolCall.toolName,
                arguments = toolCall.arguments,
                content = toolCall.content,
            )
        }
    }

    // Annotations
    if (annotations.isNotEmpty()) {
        Column(
            modifier = Modifier.animateContentSize(),
        ) {
            var expand by remember { mutableStateOf(false) }
            if (expand) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .drawWithContent {
                                drawContent()
                                drawRoundRect(
                                    color = contentColor.copy(alpha = 0.2f),
                                    size = Size(width = 10f, height = size.height),
                                )
                            }
                            .padding(start = 16.dp)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        annotations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                withLink(LinkAnnotation.Url(annotation.url)) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    expand = !expand
                }
            ) {
                Text(stringResource(R.string.citations_count, annotations.size))
            }
        }
    }

    // Images
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val images = parts.filterIsInstance<UIMessagePart.Image>()
        images.fastForEach {
            ZoomableAsyncImage(
                model = it.url,
                contentDescription = null,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .height(72.dp)
            )
        }
    }

    // Documents
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val documents = parts.filterIsInstance<UIMessagePart.Document>()
        documents.fastForEach {
            Surface(
                tonalElevation = 2.dp,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.data = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        it.url.toUri().toFile()
                    )
                    val chooserIndent = Intent.createChooser(intent, null)
                    context.startActivity(chooserIndent)
                },
                modifier = Modifier,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (it.mime) {
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                Icon(
                                    painter = painterResource(R.drawable.docx),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            "application/pdf" -> {
                                Icon(
                                    painter = painterResource(R.drawable.pdf),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            else -> {
                                Icon(
                                    imageVector = Lucide.File,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Text(
                            text = it.fileName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 200.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCallItem(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement?,
    loading: Boolean = false,
) {
    var showResult by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.animateContentSize(),
        onClick = {
            showResult = true
        },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 16.dp)
                .height(IntrinsicSize.Min)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 4.dp,
                )
            } else {
                Icon(
                    imageVector = when (toolName) {
                        "create_memory", "edit_memory" -> Lucide.BookHeart
                        "delete_memory" -> Lucide.BookDashed
                        "search_web" -> Lucide.Earth
                        else -> Lucide.Wrench
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = when (toolName) {
                        "create_memory" -> stringResource(R.string.chat_message_tool_create_memory)
                        "edit_memory" -> stringResource(R.string.chat_message_tool_edit_memory)
                        "delete_memory" -> stringResource(R.string.chat_message_tool_delete_memory)
                        "search_web" -> stringResource(
                            R.string.chat_message_tool_search_web,
                            arguments.jsonObject["query"]?.jsonPrimitiveOrNull?.contentOrNull
                                ?: ""
                        )

                        else -> stringResource(
                            R.string.chat_message_tool_call_generic,
                            toolName
                        )
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
                if (toolName == "create_memory" || toolName == "edit_memory") {
                    val content = content?.jsonObject["content"]?.jsonPrimitiveOrNull?.contentOrNull
                    if (content != null) {
                        Text(
                            text = content,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.shimmer(isLoading = loading),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (toolName == "search_web") {
                    val answer = content?.jsonObject["answer"]?.jsonPrimitiveOrNull?.contentOrNull
                    if (answer != null) {
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.shimmer(isLoading = loading),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val items = content?.jsonObject["items"]?.jsonArray ?: emptyList()
                    if (items.isNotEmpty()) {
                        FaviconRow(
                            urls = items.mapNotNull {
                                it.jsonObject["url"]?.jsonPrimitiveOrNull?.contentOrNull
                            },
                            size = 14.dp,
                        )
                    }
                }
            }
        }
    }
    if (showResult && content != null) {
        ToolCallPreviewDialog(
            toolName = toolName,
            arguments = arguments,
            content = content,
            onDismissRequest = {
                showResult = false
            }
        )
    }
}

@Composable
private fun ToolCallPreviewDialog(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement,
    onDismissRequest: () -> Unit = {}
) {
    val navController = LocalNavController.current
    val memoryRepo: MemoryRepository = koinInject()
    val scope = rememberCoroutineScope()

    // Check if this is a memory creation/update operation
    val isMemoryOperation = toolName in listOf("create_memory", "edit_memory")
    val memoryId = (content as? JsonObject)?.get("id")?.jsonPrimitiveOrNull?.intOrNull

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = {
            onDismissRequest()
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.8f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (toolName) {
                    "search_web" -> {
                        Text(
                            stringResource(
                                R.string.chat_message_tool_search_prefix,
                                arguments.jsonObject["query"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
                            )
                        )
                        val items = content.jsonObject["items"]?.jsonArray ?: emptyList()
                        val answer = content.jsonObject["answer"]?.jsonPrimitive?.contentOrNull
                        if (items.isNotEmpty()) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (answer != null) {
                                    item {
                                        Card(
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer
                                            )
                                        ) {
                                            MarkdownBlock(
                                                content = answer,
                                                modifier = Modifier
                                                    .padding(16.dp)
                                                    .fillMaxWidth(),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }

                                items(items) {
                                    val url =
                                        it.jsonObject["url"]?.jsonPrimitive?.content ?: return@items
                                    val title =
                                        it.jsonObject["title"]?.jsonPrimitive?.content
                                            ?: return@items
                                    val text =
                                        it.jsonObject["text"]?.jsonPrimitive?.content
                                            ?: return@items
                                    Card(
                                        onClick = {
                                            navController.navigate(Screen.WebView(url = url))
                                        },
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 8.dp, horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Favicon(
                                                url = url,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Column {
                                                Text(
                                                    text = title,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    text = text,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                Text(
                                                    text = url,
                                                    maxLines = 1,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(
                                                        alpha = 0.6f
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            HighlightText(
                                code = JsonInstantPretty.encodeToString(content),
                                language = "json",
                                fontSize = 12.sp
                            )
                        }
                    }

                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.chat_message_tool_call_title),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center
                            )

                            // 如果是memory操作，允许用户快速删除
                            if (isMemoryOperation && memoryId != null) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                memoryRepo.deleteMemory(memoryId)
                                                onDismissRequest()
                                            } catch (e: Exception) {
                                                // Handle error if needed
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        Lucide.Trash2,
                                        contentDescription = "Delete memory"
                                    )
                                }
                            }
                        }
                        FormItem(
                            label = {
                                Text(
                                    stringResource(
                                        R.string.chat_message_tool_call_label,
                                        toolName
                                    )
                                )
                            }
                        ) {
                            HighlightCodeBlock(
                                code = JsonInstantPretty.encodeToString(arguments),
                                language = "json",
                                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
                            )
                        }
                        FormItem(
                            label = {
                                Text(stringResource(R.string.chat_message_tool_call_result))
                            }
                        ) {
                            HighlightCodeBlock(
                                code = JsonInstantPretty.encodeToString(content),
                                language = "json",
                                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
                            )
                        }
                    }
                }
            }
        },
    )
}

enum class ReasoningCardState(val expanded: Boolean) {
    Collapsed(false),
    Preview(true),
    Expanded(true),
}

@Composable
fun ReasoningCard(
    reasoning: UIMessagePart.Reasoning,
    modifier: Modifier = Modifier,
    fadeHeight: Float = 64f,
) {
    var expandState by remember { mutableStateOf(ReasoningCardState.Collapsed) }
    val scrollState = rememberScrollState()
    val settings = LocalSettings.current
    val loading = reasoning.finishedAt == null

    LaunchedEffect(reasoning.reasoning, loading) {
        if (loading) {
            if (!expandState.expanded) expandState = ReasoningCardState.Preview
            scrollState.animateScrollTo(scrollState.maxValue)
        } else {
            if (expandState.expanded) {
                expandState = if (settings.displaySetting.autoCloseThinking) {
                    ReasoningCardState.Collapsed
                } else {
                    ReasoningCardState.Expanded
                }
            }
        }
    }

    var duration by remember(reasoning.finishedAt, reasoning.createdAt) {
        mutableStateOf(
            value = reasoning.finishedAt?.let { endTime ->
                endTime - reasoning.createdAt
            } ?: (Clock.System.now() - reasoning.createdAt)
        )
    }

    LaunchedEffect(loading) {
        if (loading) {
            while (isActive) {
                duration = (reasoning.finishedAt ?: Clock.System.now()) - reasoning.createdAt
                delay(50)
            }
        }
    }

    fun toggle() {
        expandState = if (loading) {
            if (expandState == ReasoningCardState.Expanded) ReasoningCardState.Preview else ReasoningCardState.Expanded
        } else {
            if (expandState == ReasoningCardState.Expanded) ReasoningCardState.Collapsed else ReasoningCardState.Expanded
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .let { if (expandState.expanded) it.fillMaxWidth() else it.wrapContentWidth() }
                    .clickable(
                        onClick = {
                            toggle()
                        },
                        indication = LocalIndication.current,
                        interactionSource = remember { MutableInteractionSource() }
                    )
                    .padding(horizontal = 8.dp)
                    .semantics {
                        role = Role.Button
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.deepthink),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = stringResource(R.string.deep_thinking),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(
                        isLoading = loading
                    )
                )
                if (duration > 0.seconds) {
                    Text(
                        text = "(${duration.toString(DurationUnit.SECONDS, 1)})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.shimmer(
                            isLoading = loading
                        )
                    )
                }
                Spacer(
                    modifier = if (expandState.expanded) Modifier.weight(1f) else Modifier.width(4.dp)
                )
                Icon(
                    imageVector = when (expandState) {
                        ReasoningCardState.Collapsed -> Lucide.ChevronDown
                        ReasoningCardState.Expanded -> Lucide.ChevronUp
                        ReasoningCardState.Preview -> Lucide.Expand
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
            if (expandState.expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let {
                            if (expandState == ReasoningCardState.Preview) {
                                it
                                    .graphicsLayer { alpha = 0.99f } // 触发离屏渲染，保证蒙版生效
                                    .drawWithCache {
                                        // 创建顶部和底部的渐变蒙版
                                        val brush = Brush.verticalGradient(
                                            startY = 0f,
                                            endY = size.height,
                                            colorStops = arrayOf(
                                                0.0f to Color.Transparent,
                                                (fadeHeight / size.height) to Color.Black,
                                                (1 - fadeHeight / size.height) to Color.Black,
                                                1.0f to Color.Transparent
                                            )
                                        )
                                        onDrawWithContent {
                                            drawContent()
                                            drawRect(
                                                brush = brush,
                                                size = Size(size.width, size.height),
                                                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn // 用蒙版做透明渐变
                                            )
                                        }
                                    }
                                    .heightIn(max = 120.dp)
                                    .verticalScroll(scrollState)
                            } else {
                                it
                            }
                        }

                ) {
                    SelectionContainer {
                        MarkdownBlock(
                            content = reasoning.reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReasoningCardPreview() {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        ReasoningCard(
            reasoning = UIMessagePart.Reasoning(
                """
            Ok, I'll use the following information to answer your question:

            - The current weather in New York City is sunny with a temperature of 75 degrees Fahrenheit.
            - The current weather in Los Angeles is partly cloudy with a temperature of 68 degrees Fahrenheit.
            - The current weather in Tokyo is rainy with a temperature of 60 degrees Fahrenheit.
            - The current weather in Sydney is sunny with a temperature of 82 degrees Fahrenheit.
            - The current weather in Mumbai is partly cloudy with a temperature of 70 degrees Fahrenheit.
        """.trimIndent()
            ),
            modifier = Modifier.padding(8.dp),
        )

        ReasoningCard(
            reasoning = UIMessagePart.Reasoning(
                """
            Ok, I'll use the following information to answer your question:

            - The current weather in New York City is sunny with a temperature of 75 degrees Fahrenheit.
            - The current weather in Los Angeles is partly cloudy with a temperature of 68 degrees Fahrenheit.
            - The current weather in Tokyo is rainy with a temperature of 60 degrees Fahrenheit.
            - The current weather in Sydney is sunny with a temperature of 82 degrees Fahrenheit.
            - The current weather in Mumbai is partly cloudy with a temperature of 70 degrees Fahrenheit.
        """.trimIndent()
            ),
            modifier = Modifier.padding(8.dp),
        )
    }
}
