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
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModelIcon(showIcon, message, model)
        MessagePartsBlock(
            message.role,
            message.parts,
            message.annotations,
        )
        if (message.isValidToShowActions()) {
            Actions(
                message = message,
                model = model,
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
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var showInformation by remember { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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

    // Information
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
                                "create_memory" -> "创建了记忆"
                                "edit_memory" -> "更新了记忆"
                                "delete_memory" -> "删除了记忆"
                                "search_web" -> "搜索网页: ${toolCall.arguments.jsonObject["query"]?.jsonPrimitive?.content}"
                                else -> "调用工具 ${toolCall.toolName}"
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
                    .width(72.dp)
            )
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