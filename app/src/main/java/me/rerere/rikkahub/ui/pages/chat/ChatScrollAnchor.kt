package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.MessageNode

/**
 * 发送消息后把这条用户消息滚到列表顶部，回复在它下面往下长，列表不跟着滚。
 *
 * 用户消息下面的内容不够一屏时，在最后一条消息下方补上空白，用户消息才能停在顶部；
 * 回复变长时空白同步变短，总高度不变，列表也就不会动。
 */
@Stable
class ChatScrollAnchorState {
    /** 置顶的用户消息节点，没有时列表按原来的方式滚到底、跟随输出 */
    var nodeId: Uuid? by mutableStateOf(null)
        private set

    /** 每次要求置顶时加一，列表据此滚过去 */
    internal var scrollRequest by mutableIntStateOf(0)
        private set

    // 发送后用户消息要等服务写进会话才出现（生成中发送还要排队），先记下发送前最后一条用户消息
    private var waitingForNewUserMessage = false
    private var lastUserNodeIdBeforeSend: Uuid? = null

    // 置顶后一直把锚点按在顶部，直到用户自己滚动
    internal var pinned = false
    internal var selfScrolling = false

    fun anchorNextUserMessage(nodes: List<MessageNode>) {
        waitingForNewUserMessage = true
        lastUserNodeIdBeforeSend = nodes.lastOrNull { it.isAnchorable() }?.id
    }

    /** 重新生成时置顶这条消息所在那一轮的用户消息 */
    fun anchorTurnOf(nodes: List<MessageNode>, messageId: Uuid) {
        val index = nodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
        if (index == -1) return
        val userIndex = (index downTo 0).firstOrNull { nodes[it].isAnchorable() } ?: return
        anchorTo(nodes[userIndex].id)
    }

    internal fun onMessagesChanged(nodes: List<MessageNode>) {
        if (!waitingForNewUserMessage) return
        val lastUser = nodes.lastOrNull { it.isAnchorable() } ?: return
        if (lastUser.id != lastUserNodeIdBeforeSend) {
            waitingForNewUserMessage = false
            anchorTo(lastUser.id)
        }
    }

    private fun anchorTo(id: Uuid) {
        nodeId = id
        pinned = false
        scrollRequest++
    }
}

private fun MessageNode.isAnchorable(): Boolean {
    val message = currentMessage
    return role == MessageRole.USER && !message.isNoticeOnly && !message.isContextCheckpoint
}

/**
 * 处理置顶滚动，返回最后一条消息应有的最小高度（px），用 [Modifier.chatAnchorMinHeight] 加到最后一条消息上。
 * 消息节点要是列表最前面的 item（下标一一对应），[bottomKey] 是列表最后的占位 item。
 *
 * 空白放在最后一条消息里而不是单独的占位里：这个高度按上一帧的布局算，比内容慢一帧。
 * 放在单独的占位里时，回复哪怕缩 1px（流式 Markdown 藏起没写完的语法、思考卡片折叠），
 * 这一帧内容就不满一屏，列表被往回推，下一帧再按回顶部，生成时就一直上下抖。
 * 放在消息自己的测量里，回复变短时空白在同一帧变长，总高度不变。
 */
@Composable
internal fun ChatScrollAnchorState.rememberLastNodeMinHeight(
    listState: LazyListState,
    nodes: List<MessageNode>,
    bottomKey: Any,
): IntState {
    val minHeight = remember { mutableIntStateOf(0) }
    val currentNodes by rememberUpdatedState(nodes)
    val anchorIndex by remember {
        derivedStateOf { nodeId?.let { id -> currentNodes.indexOfFirst { it.id == id } } ?: -1 }
    }
    val lastNodeIndex by remember { derivedStateOf { currentNodes.lastIndex } }

    LaunchedEffect(nodes) { onMessagesChanged(nodes) }

    // 用户自己滚动（拖动、跳转按钮、音量键）后就不再把锚点按回顶部
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !selfScrolling) pinned = false
        }
    }

    LaunchedEffect(scrollRequest) {
        if (scrollRequest == 0) return@LaunchedEffect
        // 等新消息排进列表、空白按新锚点算好，不然内容不够长滚不到顶
        withFrameNanos {}
        withFrameNanos {}
        val index = anchorIndex
        if (index < 0) return@LaunchedEffect
        selfScrolling = true
        try {
            listState.animateScrollToItem(index)
        } finally {
            selfScrolling = false
        }
        // 滚动途中空白还在变，落点差一点的话下面会校正
        pinned = true
    }

    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.layoutInfo, anchorIndex, lastNodeIndex) }.collect { (info, index, lastIndex) ->
            if (index < 0 || lastIndex < index) {
                minHeight.intValue = 0
                return@collect
            }
            val height = info.lastNodeMinHeight(index, lastIndex, bottomKey) ?: return@collect
            minHeight.intValue = height
            // 回复以外的内容变短（生成结束去掉加载行等）时列表会往回滚让末尾贴底，锚点被挤下去，这里按回顶部
            if (pinned && !listState.isScrollInProgress) {
                val anchorItem = info.visibleItemsInfo.firstOrNull { it.index == index }
                val bottomVisible = info.visibleItemsInfo.any { it.key == bottomKey }
                if (anchorItem != null && bottomVisible && anchorItem.offset != 0) {
                    listState.requestScrollToItem(index)
                }
            }
        }
    }

    return minHeight
}

/** 最后一条消息至少有 [minHeight] 高，多出来的空白在消息下方 */
internal fun Modifier.chatAnchorMinHeight(minHeight: IntState): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val height = maxOf(placeable.height, minHeight.intValue).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, height) { placeable.place(0, 0) }
}

/**
 * 锚点停在顶部时，最后一条消息要多高才能把锚点到末尾的内容补够一屏（标题栏和输入框之间的区域）。
 * 算不准时宁可多留一些，内容多出来没关系，少了列表就会被往回推。
 * 返回 null 表示这一帧没法判断，保持原样。
 */
private fun LazyListLayoutInfo.lastNodeMinHeight(anchorIndex: Int, lastNodeIndex: Int, bottomKey: Any): Int? {
    val items = visibleItemsInfo
    if (items.isEmpty()) return null
    val visibleHeight = viewportSize.height - beforeContentPadding - afterContentPadding
    // 锚点还在视口下面，先留足一屏
    if (anchorIndex > items.last().index) return visibleHeight
    // 锚点滚到视口上面，或者最后一条消息在视口下面，量不到；这时下面的内容本来就超过一屏
    val anchorItem = items.firstOrNull { it.index == anchorIndex } ?: return null
    val lastItem = items.firstOrNull { it.index == lastNodeIndex } ?: return null
    val bottomItem = items.lastOrNull { it.key == bottomKey }
    // 除最后一条消息以外，锚点到末尾的内容高度；不含最后一条消息自己，所以和它补了多少空白无关
    val otherHeight = if (bottomItem != null) {
        bottomItem.offset + bottomItem.size - anchorItem.offset - lastItem.size
    } else {
        // 末尾不在视口里，只算到最后一条消息为止，少算的部分让空白偏大
        lastItem.offset - anchorItem.offset
    }
    return (visibleHeight - otherHeight).coerceAtLeast(0)
}
