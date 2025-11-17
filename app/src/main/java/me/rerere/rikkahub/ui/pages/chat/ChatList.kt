package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.ChevronsDown
import com.composables.icons.lucide.ChevronsUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MousePointer2
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.ui.ListSelectableItem
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.hooks.ImeLazyListAutoScroller
import me.rerere.rikkahub.utils.plus
import kotlin.uuid.Uuid

private const val TAG = "ChatList"
private const val LoadingIndicatorKey = "LoadingIndicator"
private const val ScrollBottomKey = "ScrollBottomKey"

@Composable
fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    previewMode: Boolean,
    settings: Settings,
    onRegenerate: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    onClickSuggestion: (String) -> Unit = {},
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onJumpToMessage: (Int) -> Unit = {},
) {
    SharedTransitionLayout {
        AnimatedContent(
            targetState = previewMode,
            label = "ChatListMode",
            transitionSpec = {
                (fadeIn() + scaleIn(initialScale = 0.8f) togetherWith fadeOut() + scaleOut(targetScale = 0.8f))
            }
        ) { target ->
            if (target) {
                ChatListPreview(
                    innerPadding = innerPadding,
                    conversation = conversation,
                    settings = settings,
                    onJumpToMessage = onJumpToMessage,
                    animatedVisibilityScope = this@AnimatedContent,
                )
            } else {
                ChatListNormal(
                    innerPadding = innerPadding,
                    conversation = conversation,
                    state = state,
                    loading = loading,
                    settings = settings,
                    onRegenerate = onRegenerate,
                    onEdit = onEdit,
                    onForkMessage = onForkMessage,
                    onDelete = onDelete,
                    onUpdateMessage = onUpdateMessage,
                    onClickSuggestion = onClickSuggestion,
                    onTranslate = onTranslate,
                    onClearTranslation = onClearTranslation,
                    animatedVisibilityScope = this@AnimatedContent,
                )
            }
        }
    }
}

@Composable
private fun SharedTransitionScope.ChatListNormal(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    settings: Settings,
    onRegenerate: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onForkMessage: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit,
    onClickSuggestion: (String) -> Unit,
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)?,
    onClearTranslation: (UIMessage) -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val scope = rememberCoroutineScope()
    val loadingState by rememberUpdatedState(loading)
    var isRecentScroll by remember { mutableStateOf(false) }
    val conversationUpdated by rememberUpdatedState(conversation)

    fun List<LazyListItemInfo>.isAtBottom(): Boolean {
        val lastItem = lastOrNull() ?: return false
        if (lastItem.key == LoadingIndicatorKey || lastItem.key == ScrollBottomKey) {
            return true
        }
        return lastItem.key == conversation.messageNodes.lastOrNull()?.id && (lastItem.offset + lastItem.size <= state.layoutInfo.viewportEndOffset + lastItem.size * 0.15 + 32)
    }

    // 聊天选择
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    // 自动跟随键盘滚动
    ImeLazyListAutoScroller(lazyListState = state)

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // 自动滚动到底部
        LaunchedEffect(state) {
            snapshotFlow { state.layoutInfo.visibleItemsInfo }.collect { visibleItemsInfo ->
                // println("is bottom = ${visibleItemsInfo.isAtBottom()}, scroll = ${state.isScrollInProgress}, can_scroll = ${state.canScrollForward}, loading = $loading")
                if (!state.isScrollInProgress && loadingState) {
                    if (visibleItemsInfo.isAtBottom()) {
                        state.requestScrollToItem(conversationUpdated.messageNodes.lastIndex + 10)
                        // Log.i(TAG, "ChatList: scroll to ${conversationUpdated.messageNodes.lastIndex}")
                    }
                }
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
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "conversation_list"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            itemsIndexed(
                items = conversation.messageNodes,
                key = { index, item -> item.id },
            ) { index, node ->
                Column {
                    ListSelectableItem(
                        key = node.id,
                        onSelectChange = {
                            if (!selectedItems.contains(node.id)) {
                                selectedItems.add(node.id)
                            } else {
                                selectedItems.remove(node.id)
                            }
                        },
                        selectedKeys = selectedItems,
                        enabled = selecting,
                    ) {
                        ChatMessage(
                            node = node,
                            conversation = conversation,
                            model = node.currentMessage.modelId?.let { settings.findModelById(it) },
                            assistant = settings.getAssistantById(conversation.assistantId),
                            loading = loading && index == conversation.messageNodes.lastIndex,
                            onRegenerate = {
                                onRegenerate(node.currentMessage)
                            },
                            onEdit = {
                                onEdit(node.currentMessage)
                            },
                            onFork = {
                                onForkMessage(node.currentMessage)
                            },
                            onDelete = {
                                onDelete(node.currentMessage)
                            },
                            onShare = {
                                selecting = true  // 使用 CoroutineScope 延迟状态更新
                                selectedItems.clear()
                                selectedItems.addAll(conversation.messageNodes.map { it.id }
                                    .subList(0, conversation.messageNodes.indexOf(node) + 1))
                            },
                            onUpdate = {
                                onUpdateMessage(it)
                            },
                            onTranslate = onTranslate,
                            onClearTranslation = onClearTranslation
                        )
                    }
                    if (index == conversation.truncateIndex - 1) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .fillMaxWidth()
                        ) {
                            HorizontalDivider(modifier = Modifier.weight(1f))
                            Text(
                                text = stringResource(R.string.chat_page_clear_context),
                                style = MaterialTheme.typography.bodySmall
                            )
                            HorizontalDivider(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            if (loading) {
                item(LoadingIndicatorKey) {
                    LoadingIndicator()
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 完成选择
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(48).dp),
                enter = slideInVertically(
                    initialOffsetY = { it * 2 },
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it * 2 },
                ),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                ) {
                    Tooltip(
                        tooltip = {
                            Text("Clear selection")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                selecting = false
                                selectedItems.clear()
                            }
                        ) {
                            Icon(Lucide.X, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Select all")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                if (selectedItems.isNotEmpty()) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.addAll(conversation.messageNodes.map { it.id })
                                }
                            }
                        ) {
                            Icon(Lucide.MousePointer2, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Confirm")
                        }
                    ) {
                        FilledIconButton(
                            onClick = {
                                selecting = false
                                val messages = conversation.messageNodes.filter { it.id in selectedItems }
                                if (messages.isNotEmpty()) {
                                    showExportSheet = true
                                }
                            }
                        ) {
                            Icon(Lucide.Check, null)
                        }
                    }
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
                selectedMessages = conversation.messageNodes.filter { it.id in selectedItems }
                    .map { it.currentMessage }
            )

            val captureProgress = LocalScrollCaptureInProgress.current

            // 消息快速跳转
            MessageJumper(
                show = isRecentScroll && !state.isScrollInProgress && settings.displaySetting.showMessageJumper && !captureProgress,
                onLeft = settings.displaySetting.messageJumperOnLeft,
                scope = scope,
                state = state
            )

            // Suggestion
            if (conversation.chatSuggestions.isNotEmpty() && !captureProgress) {
                ChatSuggestionsRow(
                    conversation = conversation,
                    onClickSuggestion = onClickSuggestion,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/**
 * 提取包含搜索词的文本片段，确保匹配词在开头可见
 */
private fun extractMatchingSnippet(
    text: String,
    query: String
): String {
    if (query.isBlank()) {
        return text
    }

    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex == -1) {
        return text
    }

    // 直接从匹配词开始显示，确保匹配词在最前面
    val snippet = text.substring(matchIndex)

    // 只在前面有内容时添加省略号
    return if (matchIndex > 0) {
        "...$snippet"
    } else {
        snippet
    }
}

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var startIndex = 0
        var index = text.indexOf(query, startIndex, ignoreCase = true)

        while (index >= 0) {
            // 添加高亮前的文本
            append(text.substring(startIndex, index))

            // 添加高亮文本
            withStyle(
                style = SpanStyle(
                    background = highlightColor,
                    color = Color.Black
                )
            ) {
                append(text.substring(index, index + query.length))
            }

            startIndex = index + query.length
            index = text.indexOf(query, startIndex, ignoreCase = true)
        }

        // 添加剩余文本
        if (startIndex < text.length) {
            append(text.substring(startIndex))
        }
    }
}

@Composable
private fun SharedTransitionScope.ChatListPreview(
    innerPadding: PaddingValues,
    conversation: Conversation,
    settings: Settings,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onJumpToMessage: (Int) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    // 过滤消息
    val filteredMessages = remember(conversation.messageNodes, searchQuery) {
        if (searchQuery.isBlank()) {
            conversation.messageNodes
        } else {
            conversation.messageNodes.filterIndexed { index, node ->
                node.currentMessage.toText().contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .padding(innerPadding)
            .fillMaxSize(),
    ) {
        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.history_page_search)) },
            leadingIcon = {
                Icon(
                    imageVector = Lucide.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Lucide.X,
                            contentDescription = "Clear",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            maxLines = 1,
        )

        // 消息预览
        LazyColumn(
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "conversation_list"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
                .fillMaxWidth()
                .weight(1f),
        ) {
            itemsIndexed(
                items = filteredMessages,
                key = { index, item -> item.id },
            ) { _, node ->
                val message = node.currentMessage
                val isUser = message.role == me.rerere.ai.core.MessageRole.USER
                val originalIndex = conversation.messageNodes.indexOf(node)
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .then(
                            if (!isUser) Modifier.padding(end = 24.dp) else Modifier
                        ),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    onJumpToMessage(originalIndex)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
                            val highlightedText = remember(searchQuery, message) {
                                val fullText = message.toText().trim().ifBlank { "[...]" }
                                val messageText = extractMatchingSnippet(
                                    text = fullText,
                                    query = searchQuery
                                )
                                buildHighlightedText(
                                    text = messageText,
                                    query = searchQuery,
                                    highlightColor = highlightColor
                                )
                            }
                            Text(
                                text = highlightedText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatSuggestionsRow(
    modifier: Modifier = Modifier,
    conversation: Conversation,
    onClickSuggestion: (String) -> Unit
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(conversation.chatSuggestions) { suggestion ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        onClickSuggestion(suggestion)
                    }
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                    .padding(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MessageJumper(
    show: Boolean,
    onLeft: Boolean,
    scope: CoroutineScope,
    state: LazyListState
) {
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(
            initialOffsetX = { if (onLeft) -it * 2 else it * 2 },
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { if (onLeft) -it * 2 else it * 2 },
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(0)
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = Lucide.ChevronsUp,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
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
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(state.layoutInfo.totalItemsCount - 1)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f),
            ) {
                Icon(
                    imageVector = Lucide.ChevronsDown,
                    contentDescription = stringResource(R.string.chat_page_scroll_to_bottom),
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
        }
    }
}
