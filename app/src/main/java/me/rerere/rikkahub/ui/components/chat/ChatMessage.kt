package me.rerere.rikkahub.ui.components.chat

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalContext
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
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.Expand
import com.composables.icons.lucide.File
import com.composables.icons.lucide.GitFork
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Share
import com.composables.icons.lucide.Trash
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.Wrench
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaLocalDateTime
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
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.tts.rememberTtsState
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.copyMessageToClipboard
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.rikkahub.utils.urlDecode
import me.rerere.rikkahub.utils.urlEncode
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
    model: Model? = null,
    showActions: Boolean,
    onClick: () -> Unit, // 新增：接收点击事件的回调
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit
) {
    val message = node.messages[node.selectIndex]
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // 设置为 null 以禁用点击时的视觉效果
                onClick = onClick
            ),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!message.parts.isEmptyUIMessage()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModelIcon(
                    showIcon = showIcon,
                    message = message,
                    model = model,
                    modifier = Modifier.weight(1f)
                )
                MessageNodePagerButtons(
                    node = node,
                    onUpdate = onUpdate
                )
            }
        }
        MessagePartsBlock(
            role = message.role,
            parts = message.parts,
            annotations = message.annotations,
        )
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
                    model = model,
                    onRegenerate = onRegenerate,
                    onEdit = onEdit,
                    onFork = onFork,
                    onShare = onShare,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun ModelIcon(
    showIcon: Boolean,
    message: UIMessage,
    model: Model?,
    modifier: Modifier = Modifier,
) {
    if (showIcon && message.role == MessageRole.ASSISTANT && !message.parts.isEmptyUIMessage() && model != null) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(
                model.modelId,
            )
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

@Composable
private fun ColumnScope.Actions(
    message: UIMessage,
    model: Model?,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
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

        if (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT) {
            Icon(
                Lucide.Pencil, stringResource(R.string.edit), modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onEdit() }
                    .padding(8.dp)
                    .size(16.dp)
            )

            Box(
                modifier = Modifier
                    .animateContentSize()
                    .height(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isPendingDelete) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp))
                            .padding(horizontal = 4.dp)
                    ) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Lucide.Check,
                                contentDescription = stringResource(R.string.confirm_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        VerticalDivider(
                            modifier = Modifier.height(16.dp),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                        IconButton(
                            onClick = { isPendingDelete = false },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Lucide.X,
                                contentDescription = stringResource(R.string.cancel),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else {
                    Icon(
                        imageVector = Lucide.Trash,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { isPendingDelete = true }
                            .padding(8.dp)
                            .size(16.dp)
                    )
                }
            }
        }
        if (message.role == MessageRole.ASSISTANT) {
            val tts = rememberTtsState()
            val isSpeaking by tts.isSpeaking.collectAsState()
            Icon(
                imageVector = if (isSpeaking) Lucide.CircleStop else Lucide.Volume2,
                contentDescription = stringResource(R.string.tts),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (!isSpeaking) {
                                tts.speak(message.toText(), TextToSpeech.QUEUE_FLUSH)
                            } else {
                                tts.stop()
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp)
            )
        }
        if (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT) {
            Icon(
                imageVector = if (showInformation) Lucide.ChevronUp else Lucide.ChevronDown,
                contentDescription = "Info",
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            showInformation = !showInformation
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp)
            )
        }
    }

    AnimatedVisibility(showInformation) {
        ProvideTextStyle(MaterialTheme.typography.labelSmall) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Lucide.Share,
                    contentDescription = "Share",
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            onClick = {
                                onShare()
                            }
                        )
                        .padding(8.dp)
                        .size(16.dp)
                )

                Icon(
                    Lucide.GitFork, "Fork", modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            onClick = {
                                onFork()
                            }
                        )
                        .padding(8.dp)
                        .size(16.dp)
                )

                Column {
                    Text(message.createdAt.toJavaLocalDateTime().toLocalString())
                    if (model != null) {
                        Text(model.displayName)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageNodePagerButtons(
    node: MessageNode,
    onUpdate: (MessageNode) -> Unit
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

@Composable
fun MessagePartsBlock(
    role: MessageRole,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
) {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    val navController = LocalNavController.current

    fun handleClickCitation(id: Int) {
//        val search = parts.filterIsInstance<UIMessagePart.Search>().firstOrNull()
//        if (search != null) {
//            val item = search.search.items.getOrNull(id - 1)
//            if (item != null) {
//                navController.navigate("webview?url=${item.url.urlEncode()}")
//            }
//        }
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
                    shape = RoundedCornerShape(8.dp),
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
    parts.filterIsInstance<UIMessagePart.ToolResult>().fastForEachIndexed { index, toolCall ->
        key(index) {
            var showResult by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable {
                        showResult = true
                    }
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                        .height(IntrinsicSize.Min)
                ) {
                    Icon(
                        imageVector = when (toolCall.toolName) {
                            "create_memory", "edit_memory" -> Lucide.BookHeart
                            "delete_memory" -> Lucide.BookDashed
                            "search_web" -> Lucide.Earth
                            else -> Lucide.Wrench
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = contentColor.copy(alpha = 0.7f)
                    )
                    Column {
                        Text(
                            text = when (toolCall.toolName) {
                                "create_memory" -> stringResource(R.string.chat_message_tool_create_memory)
                                "edit_memory" -> stringResource(R.string.chat_message_tool_edit_memory)
                                "delete_memory" -> stringResource(R.string.chat_message_tool_delete_memory)
                                "search_web" -> stringResource(
                                    R.string.chat_message_tool_search_web,
                                    toolCall.arguments.jsonObject["query"]?.jsonPrimitive?.content
                                        ?: ""
                                )

                                else -> stringResource(
                                    R.string.chat_message_tool_call_generic,
                                    toolCall.toolName
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (showResult) {
                ToolCallPreviewDialog(
                    toolCall = toolCall,
                    onDismissRequest = {
                        showResult = false
                    }
                )
            }
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
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Lucide.File,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
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

@Composable
private fun ToolCallPreviewDialog(
    toolCall: UIMessagePart.ToolResult,
    onDismissRequest: () -> Unit = {}
) {
    val navController = LocalNavController.current
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
                when (toolCall.toolName) {
                    "search_web" -> {
                        Text(
                            stringResource(
                                R.string.chat_message_tool_search_prefix,
                                toolCall.arguments.jsonObject["query"]?.jsonPrimitive?.content ?: ""
                            )
                        )
                        val items = toolCall.content.jsonObject["items"]?.jsonArray ?: emptyList()
                        if (items.isNotEmpty()) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
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
                                            navController.navigate("webview?url=${url.urlEncode()}")
                                        }
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
                                code = JsonInstantPretty.encodeToString(toolCall.content),
                                language = "json",
                                fontSize = 12.sp
                            )
                        }
                    }

                    else -> {
                        Text(
                            text = stringResource(R.string.chat_message_tool_call_title),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        FormItem(
                            label = {
                                Text(
                                    stringResource(
                                        R.string.chat_message_tool_call_label,
                                        toolCall.toolName
                                    )
                                )
                            }
                        ) {
                            HighlightCodeBlock(
                                code = JsonInstantPretty.encodeToString(toolCall.arguments),
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
                                code = JsonInstantPretty.encodeToString(toolCall.content),
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

    var duration by remember {
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

    OutlinedCard(
        modifier = modifier,
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
                    imageVector = Lucide.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
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
                                    .heightIn(max = 100.dp)
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
                            modifier = Modifier
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