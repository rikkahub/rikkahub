package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.chat.ChatMessage
import me.rerere.rikkahub.ui.components.ui.ListSelectableItem
import kotlin.uuid.Uuid

private const val LoadingIndicatorKey = "LoadingIndicator"
private const val ScrollBottomKey = "ScrollBottomKey"
private const val TokenUsageItemKey = "TokenUsageItemKey"
private const val ContextUsageItemKey = "ContextUsageItemKey"

@Composable
fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    loading: Boolean,
    settings: Settings,
    onRegenerate: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var expandedMessageId by remember { mutableStateOf<Uuid?>(null) }

    val viewPortSize by remember { derivedStateOf { state.layoutInfo.viewportSize } }
    var isRecentScroll by remember { mutableStateOf(false) }

    var isAtBottom by remember { mutableStateOf(false) }
    val scrollToBottom = { state.requestScrollToItem(conversation.messageNodes.lastIndex + 5) }
    fun List<LazyListItemInfo>.isAtBottom(): Boolean {
        val lastItem = lastOrNull() ?: return false
        if (lastItem.key == LoadingIndicatorKey || lastItem.key == ScrollBottomKey || lastItem.key == TokenUsageItemKey) {
            return true
        }
        return lastItem.key == conversation.messageNodes.lastOrNull()?.id && (lastItem.offset + lastItem.size <= state.layoutInfo.viewportEndOffset + lastItem.size * 0.15 + 32)
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
        LaunchedEffect(loading, conversation.messageNodes, viewPortSize, loading) {
            if (!state.isScrollInProgress && state.canScrollForward && loading) {
                if (state.layoutInfo.visibleItemsInfo.isAtBottom()) {
                    state.requestScrollToItem(conversation.messageNodes.lastIndex + 10)
                }
            }
        }

        // 用户发送消息后滚动到底部
        LaunchedEffect(conversation.messageNodes.lastOrNull()?.id) {
            val lastNode = conversation.messageNodes.lastOrNull()
            // 检查最后一条消息是否是用户发送的
            if (lastNode?.currentMessage?.role == MessageRole.USER) {
                if (conversation.messageNodes.isNotEmpty()) {
                    scope.launch {
                        state.animateScrollToItem(conversation.messageNodes.lastIndex)
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = conversation.messageNodes,
                key = { index, item -> item.id }) { index, node ->
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
                        enabled = selecting && node.currentMessage.isValidToShowActions(),
                    ) {
                        val isLastMessage = index == conversation.messageNodes.lastIndex
                        val isAssistantMessage = node.role == MessageRole.ASSISTANT

                        // 计算是否显示操作菜单的最终标志
                        // 修改：计算操作菜单可见性的逻辑
                        val showActions = (expandedMessageId == node.id) ||
                                (isLastMessage && isAssistantMessage && !loading)

                        ChatMessage(
                            node = node,
                            showIcon = settings.displaySetting.showModelIcon,
                            model = node.currentMessage.modelId?.let { settings.findModelById(it) },
                            showActions = showActions, 

                            onClick = {
                                if (!selecting) {
                                    expandedMessageId = if (expandedMessageId == node.id) {
                                        null 
                                    } else {
                                        node.id 
                                    }
                                }
                            },
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
                                selecting = true
                                selectedItems.clear()
                                selectedItems.addAll(conversation.messageNodes.map { it.id }
                                    .subList(0, conversation.messageNodes.indexOf(node) + 1))
                            },
                            onUpdate = {
                                onUpdateMessage(it)
                            }
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

            // NEW: Combined Token and Context Usage Item
            // Combined Token and Context Usage Item
            item(ContextUsageItemKey) {
                // 当设置允许显示统计信息，并且聊天记录不为空时才显示
                if (settings.displaySetting.showTokenUsage && conversation.messageNodes.isNotEmpty()) {
                    val configuredContextSize = settings.getCurrentAssistant().contextMessageSize
                    val effectiveMessagesAfterTruncation =
                        conversation.messageNodes.size - conversation.truncateIndex.coerceAtLeast(0)
                    val actualContextMessageCount =
                        minOf(effectiveMessagesAfterTruncation, configuredContextSize)

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
                        // Token使用量统计 (仅当有数据时)
                        if (conversation.tokenUsage != null) {
                            Text(
                                text = "Tokens: ${conversation.tokenUsage.totalTokens} (${conversation.tokenUsage.promptTokens} -> ${conversation.tokenUsage.completionTokens})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        // 上下文消息数量统计
                        Text(
                            text = "Context: $actualContextMessageCount/$configuredContextSize",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
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
                        conversation.messageNodes.filter { it.id in selectedItems && it.currentMessage.isValidToShowActions() }
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
            selectedMessages = conversation.messageNodes.filter { it.id in selectedItems }
                .map { it.currentMessage }
        )

        // 滚动到底部按钮
        MessageQuickBottom(state = state, isAtBottom = isAtBottom, scrollToBottom = scrollToBottom)

        // 消息快速跳转
        MessageJumper(
            isRecentScroll = isRecentScroll && settings.displaySetting.showMessageJumper,
            scope = scope,
            state = state
        )
    }
}

@Composable
private fun BoxScope.MessageQuickBottom(
    state: LazyListState,
    isAtBottom: Boolean,
    scrollToBottom: () -> Unit
) {
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
}

@Composable
private fun BoxScope.MessageJumper(
    isRecentScroll: Boolean,
    scope: CoroutineScope,
    state: LazyListState
) {
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
