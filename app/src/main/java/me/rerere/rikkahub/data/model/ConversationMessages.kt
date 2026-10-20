package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishPendingTools
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * 删除一条消息：节点里没有别的分支时整个节点一起删掉，否则选中的分支向前收拢。
 * 消息不存在时返回 null。
 */
internal fun Conversation.deleteMessage(messageId: Uuid): Conversation? {
    val targetNodeIndex = messageNodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
    if (targetNodeIndex == -1) return null

    val updatedNodes = messageNodes.mapIndexedNotNull { index, node ->
        if (index != targetNodeIndex) return@mapIndexedNotNull node
        val nextMessages = node.messages.filterNot { it.id == messageId }
        if (nextMessages.isEmpty()) return@mapIndexedNotNull null
        node.copy(messages = nextMessages, selectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex))
    }
    return copy(messageNodes = updatedNodes)
}

/**
 * 用 [parts] 替换消息的内容，作为原节点的新分支；压缩检查点原地改写。消息不存在时原样返回。
 */
internal fun Conversation.editMessage(messageId: Uuid, parts: List<UIMessagePart>): Conversation {
    val node = getMessageNodeByMessageId(messageId) ?: return this
    val original = node.messages.first { it.id == messageId }
    val edited = if (original.isContextCheckpoint) {
        // 摘要原地改写：新建分支会丢掉检查点标记，删除摘要时还会露出旧版本。
        original.copy(parts = parts)
    } else {
        UIMessage(
            role = node.role,
            parts = parts,
            // 编辑的是原消息的内容，来源仍是生成它的模型
            modelId = original.modelId,
            modelSnapshot = original.modelSnapshot,
        )
    }
    return upsertMessage(node.id, edited)
}

/** 设置消息的翻译，[translation] 为 null 时清除；消息不存在时原样返回 */
internal fun Conversation.withTranslation(messageId: Uuid, translation: String?): Conversation {
    val node = getMessageNodeByMessageId(messageId) ?: return this
    return upsertMessage(node.id, node.messages.first { it.id == messageId }.copy(translation = translation))
}

/** 切换节点选中的分支，调用方负责校验节点和下标 */
internal fun Conversation.selectBranch(nodeId: Uuid, selectIndex: Int): Conversation = copy(
    messageNodes = messageNodes.map { node ->
        if (node.id == nodeId) node.copy(selectIndex = selectIndex) else node
    }
)

internal fun Conversation.withNodeFavorite(nodeId: Uuid, favorite: Boolean): Conversation = copy(
    messageNodes = messageNodes.map { node ->
        if (node.id == nodeId) node.copy(isFavorite = favorite) else node
    }
)

/**
 * 记录用户对工具调用的审批结果。
 * 当前分支里没有这个还在等待审批的工具调用时返回 null，用来忽略重复点击和对已完成或非当前分支工具的过期审批。
 */
internal fun Conversation.withToolApproval(toolCallId: String, approvalState: ToolApprovalState): Conversation? {
    if (currentMessages.none { message ->
            message.getTools().any { it.toolCallId == toolCallId && it.isPending }
        }) {
        return null
    }
    return copy(
        messageNodes = messageNodes.map { node ->
            node.copy(messages = node.messages.map { message ->
                message.copy(parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                        part.copy(approvalState = approvalState)
                    } else {
                        part
                    }
                })
            })
        }
    )
}

/**
 * 生成被停止时，最后一条消息里还没执行的工具补上取消的结果，否则这条消息之后没法继续发给模型。
 * 没有需要补的工具时返回 null。
 */
internal fun Conversation.cancelInterruptedTools(): Conversation? {
    val lastNode = messageNodes.lastOrNull() ?: return null
    val updatedMessage = lastNode.currentMessage.finishPendingTools(::cancelToolByUser)
    if (updatedMessage === lastNode.currentMessage) return null
    return upsertMessage(lastNode.id, updatedMessage)
}

private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
    output = listOf(
        UIMessagePart.Text(
            """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
        )
    ),
    // 执行到一半被停止的工具，耗时算到取消为止
    finishedAt = tool.finishedAt ?: tool.startedAt?.let { Clock.System.now() },
)
