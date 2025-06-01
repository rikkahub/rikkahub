package me.rerere.rikkahub.ui.components.chat

import android.speech.tts.TextToSpeech
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProvideTextStyle
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import com.composables.icons.lucide.BookDashed
import com.composables.icons.lucide.BookHeart
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.GitFork
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Share
import com.composables.icons.lucide.Trash
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.Wrench
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
    message: UIMessage,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
    model: Model? = null,
    isLoadingConversation: Boolean, // 用于判断AI消息是否正在加载
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    // 用于控制用户消息的操作按钮和详情的显示状态
    var showUserMessageActionsAndDetails by remember(message.id) { mutableStateOf(false) }

    val isUserMessage = message.role == MessageRole.USER
    val isAssistantMessage = message.role == MessageRole.ASSISTANT

    // AI消息完成的判断 (沿用之前的逻辑)
    val isAssistantMessageEffectivelyComplete = !isLoadingConversation && isAssistantMessage

    // 最终决定是否显示操作按钮和详情的条件
    val shouldShowActionsAndDetails =
        (isUserMessage && showUserMessageActionsAndDetails) || isAssistantMessageEffectivelyComplete

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUserMessage) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModelIcon(showIcon, message, model)

        // 包裹 MessagePartsBlock 的 Box，用于控制其宽度和对齐
        Box(
            // 对于用户消息，让 Box 包裹其内容宽度；对于AI消息，让 Box 占据全部宽度
            modifier = if (isUserMessage) {
                Modifier
                    .wrapContentWidth(align = Alignment.End, unbounded = true) // 用户消息内容自适应宽度并靠右
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            showUserMessageActionsAndDetails = !showUserMessageActionsAndDetails
                        }
                    )
            } else {
                Modifier.fillMaxWidth() // AI 消息内容块默认占据全部宽度
            }
        ) {
            MessagePartsBlock( // MessagePartsBlock 内部的 Column 会根据父 Box 的约束来布局
                role = message.role,
                parts = message.parts,
                annotations = message.annotations,
            )
        }

        // 根据条件显示 Actions
        // isValidToShowActions 确保消息有实际内容才显示操作
        if (message.isValidToShowActions() && shouldShowActionsAndDetails) {
            Actions(
                message = message,
                model = model,
                // isMessageComplete 传递给 Actions，用于其内部的 AnimatedVisibility
                // 对于用户消息，当 showUserMessageActionsAndDetails 为 true 时，我们认为它是“完成”并可交互的
                // 对于AI消息，它依赖 isAssistantMessageEffectivelyComplete
                isMessageComplete = if(isUserMessage) showUserMessageActionsAndDetails else isAssistantMessageEffectivelyComplete,
                onRegenerate = onRegenerate,
                onEdit = onEdit,
                onFork = onFork,
                onShare = onShare,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun ModelIcon(
    showIcon: Boolean,
    message: UIMessage,
    model: Model?
) {
    if (showIcon && message.role == MessageRole.ASSISTANT && !message.parts.isEmptyUIMessage() && model != null) {
        Row(
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
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
    isMessageComplete: Boolean, // 此参数现在控制 Actions 内部 AnimatedVisibility 的显隐
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    // showInformation 状态现在也应该只在 isMessageComplete 为 true 时才有意义切换
    var showInformation by remember(message.id, isMessageComplete) { mutableStateOf(false) }

    // 如果 isMessageComplete 为 false，则不显示任何操作按钮或信息详情
    AnimatedVisibility(visible = isMessageComplete) {
        Column( // 使用 Column 包裹按钮行和信息详情，确保它们垂直排列
            modifier = Modifier.fillMaxWidth(), // 让 Column 占据可用宽度
            // 根据消息角色决定 Column 内容的水平对齐方式
            horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start
        ) {
            // 按钮行
            Row(
                // Modifier.wrapContentWidth() 确保 Row 只占据其内容所需的宽度
                // Alignment.Start 或 Alignment.End 取决于消息角色
                modifier = Modifier.wrapContentWidth(
                    align = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
                    unbounded = true // 允许内容超出父级约束，虽然这里可能不需要
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp), // 按钮间的固定间距
                verticalAlignment = Alignment.CenterVertically // 垂直居中对齐按钮
            ) {
                // 复制按钮
                Icon(
                    Lucide.Copy, stringResource(R.string.copy), modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            onClick = {
                                context.copyMessageToClipboard(message)
                            }
                        )
                        .padding(8.dp)
                        .size(16.dp)
                )

                // 重新生成按钮
                Icon(
                    Lucide.RefreshCw, stringResource(R.string.regenerate), modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            onClick = {
                                onRegenerate()
                            }
                        )
                        .padding(8.dp)
                        .size(16.dp)
                )

                // 编辑和删除按钮
                if (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT) {
                    Icon(
                        Lucide.Pencil, stringResource(R.string.edit), modifier = Modifier
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = LocalIndication.current,
                                onClick = {
                                    onEdit()
                                }
                            )
                            .padding(8.dp)
                            .size(16.dp)
                    )
                    Icon(
                        Lucide.Trash, "Delete",
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = LocalIndication.current,
                                onClick = {
                                    onDelete()
                                }
                            )
                            .padding(8.dp)
                            .size(16.dp)
                    )
                }

                // TTS 按钮
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

                // 信息详情展开/收起按钮
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
            } // End of buttons Row
            // 信息详情部分
            AnimatedVisibility(visible = showInformation) {
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth() // 信息详情行总是占据全部宽度
                            .padding(top = 4.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
                        // 信息详情内部元素可以根据需要调整 Arrangement
                        horizontalArrangement = if (message.role == MessageRole.USER) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 如果是用户消息，并且希望信息详情内部元素也靠右，可以调整这里的排列
                        // 例如，将 Column 放在最右边
                        if (message.role == MessageRole.USER) Spacer(Modifier.weight(1f))

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

                        Column(
                            horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start
                        ) {
                            Text(message.createdAt.toJavaLocalDateTime().toLocalString())
                            if (model != null) {
                                Text(model.displayName)
                            }
                        }
                        if (message.role == MessageRole.ASSISTANT) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } // End of outer Column for Actions
    } // End of outer AnimatedVisibility
}

@Composable
fun MessagePartsBlock(
    role: MessageRole,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
) {
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


    // MessagePartsBlock 内部的 Column 现在会根据其父 Box 的宽度约束来表现
    // 如果父 Box 是 wrapContentWidth (用户消息)，则此 Column 也会自适应内容宽度
    // 如果父 Box 是 fillMaxWidth (AI消息)，则此 Column 也会占据全部宽度
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        // 确保 Column 内部元素也根据角色对齐（如果父 Box 是 fillMaxWidth）
        horizontalAlignment = if (role == MessageRole.USER) Alignment.End else Alignment.Start
    ) {
        // Reasoning
        parts.filterIsInstance<UIMessagePart.Reasoning>().fastForEach { reasoning ->
            ReasoningCard(
                reasoning = reasoning,
                // ReasoningCard 总是 fillMaxWidth 看起来比较统一
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Text
        parts.filterIsInstance<UIMessagePart.Text>().fastForEach { part ->
            SelectionContainer {
                // 对于用户消息，Card 的宽度将由其内容（MarkdownBlock）决定，
                // 因为 MessagePartsBlock 的父 Box 是 wrapContentWidth。
                // 对于AI消息，MarkdownBlock 会 fillMaxWidth。
                if (role == MessageRole.USER) {
                    Card(
                        modifier = Modifier.animateContentSize(), // Card 自适应内容宽度
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) { // 内边距
                            MarkdownBlock(
                                content = part.text,
                                onClickCitation = { id ->
                                    handleClickCitation(id)
                                }
                            )
                        }
                    }
                } else { // AI 助手的文本消息
                    MarkdownBlock(
                        content = part.text,
                        onClickCitation = { id ->
                            handleClickCitation(id)
                        },
                        modifier = Modifier
                            .animateContentSize()
                            .fillMaxWidth() // AI 消息的 MarkdownBlock 占据全部宽度
                    )
                }
            }
        }

        // Tool Calls / Tool Results
        parts.filterIsInstance<UIMessagePart.ToolResult>().fastForEachIndexed { index, toolResult ->
            key(index) {
                var showResult by remember { mutableStateOf(false) }
                // ToolResult 的外层 Box 保持 fillMaxWidth，内部 UI 通过 wrapContentWidth 对齐
                Box(
                    modifier = Modifier
                        .fillMaxWidth() // 确保 ToolResult 区域有机会根据角色对齐
                        .wrapContentWidth(
                            align = if (role == MessageRole.USER) Alignment.End else Alignment.Start,
                            unbounded = true
                        )
                ) {
                    Box( // 实际的 ToolResult UI
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable { showResult = true }
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
                                imageVector = when (toolResult.toolName) {
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
                                    text = when (toolResult.toolName) {
                                        "create_memory" -> "创建了记忆"
                                        "edit_memory" -> "更新了记忆"
                                        "delete_memory" -> "删除了记忆"
                                        "search_web" -> "搜索网页: ${toolResult.arguments.jsonObject["query"]?.jsonPrimitive?.content}"
                                        else -> "调用工具 ${toolResult.toolName}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                if (showResult) {
                    ToolCallPreviewDialog(
                        toolCall = toolResult,
                        onDismissRequest = { showResult = false }
                    )
                }
            }
        }

        // Annotations
        if (annotations.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .animateContentSize()
                    .fillMaxWidth(), // 注解部分也占据全部宽度
                horizontalAlignment = if (role == MessageRole.USER) Alignment.End else Alignment.Start // 根据角色对齐
            ) {
                var expand by remember { mutableStateOf(false) }
                // ... (Annotations 内容不变) ...
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
        // FlowRow 默认会根据内容换行，其对齐方式会继承父 Column
        FlowRow(
            horizontalArrangement = if (role == MessageRole.USER) Arrangement.End else Arrangement.Start, // 让图片也根据角色对齐
            modifier = Modifier.fillMaxWidth() // FlowRow 占据全部宽度
        ) {
            val images = parts.filterIsInstance<UIMessagePart.Image>()
            images.fastForEach {
                ZoomableAsyncImage(
                    model = it.url,
                    contentDescription = null,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .width(72.dp)
                )
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
                        Text("搜索: ${toolCall.arguments.jsonObject["query"]?.jsonPrimitive?.content}")
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
                        Text("工具调用")
                        FormItem(
                            label = {
                                Text("调用工具 ${toolCall.toolName}")
                            }
                        ) {
                            HighlightText(
                                code = JsonInstantPretty.encodeToString(toolCall.arguments),
                                language = "json",
                                fontSize = 12.sp
                            )
                        }
                        FormItem(
                            label = {
                                Text("调用结果")
                            }
                        ) {
                            HighlightText(
                                code = JsonInstantPretty.encodeToString(toolCall.content),
                                language = "json",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun ReasoningCard(
    reasoning: UIMessagePart.Reasoning,
    modifier: Modifier = Modifier,
    fadeHeight: Float = 64f,
) {
    var expanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val settings = LocalSettings.current
    val loading = reasoning.finishedAt == null

    LaunchedEffect(reasoning, loading) {
        if (loading) {
            if (!expanded) expanded = true
            // 只有在限制高度且内容可滚动时，滚动到底部才有意义
            if (settings.displaySetting.limitReasoningHeightDuringLoading) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        } else {
            if (expanded && settings.displaySetting.autoCloseThinking) expanded = false
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

    OutlinedCard(
        modifier = modifier,
        onClick = {
            expanded = !expanded
        }
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.let { if (expanded) it.fillMaxWidth() else it.wrapContentWidth() },
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
                if(duration > 0.seconds) {
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
                    modifier = if(expanded) Modifier.weight(1f) else Modifier.width(4.dp)
                )
                Icon(
                    imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier
                        .clickable {
                            expanded = !expanded
                        }
                        .size(14.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { currentModifier -> // 使用 let 链式调用修饰符
                            if (loading) {
                                var chainedModifier = currentModifier
                                    .graphicsLayer { alpha = 0.99f } // 触发离屏渲染，保证蒙版生效
                                    .drawWithCache {
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
                                                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
                                            )
                                        }
                                    }

                                // 根据设置决定是否限制高度和是否可滚动
                                if (settings.displaySetting.limitReasoningHeightDuringLoading) {
                                    chainedModifier = chainedModifier
                                        .heightIn(max = 100.dp)
                                        .verticalScroll(scrollState) // 只有在限制高度时才使其内部可滚动
                                }
                                // 如果不限制高度，则不添加 verticalScroll，让外部 LazyColumn 处理滚动
                                chainedModifier
                            } else {
                                // 非加载状态，不应用特殊高度限制或渐变，也不需要内部滚动
                                currentModifier
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