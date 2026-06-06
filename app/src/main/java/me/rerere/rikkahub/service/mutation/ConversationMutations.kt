package me.rerere.rikkahub.service.mutation

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import kotlin.uuid.Uuid

/**
 * 纯函数会话变更核心：把此前内嵌在 ChatService 里的消息节点重写逻辑（删除 / 选支 / 翻译 / 编辑 / 分叉）
 * 原样搬出，使其无 Android / IO / settings 依赖，可被 JVM 单测钉死。
 *
 * 副作用（本地文件拷贝、持久化 saveConversation/updateConversation）刻意留在 ChatService 编排边界：
 * - 文件拷贝通过 [forkAtMessage] 的 copyPart 缝隙注入（默认恒等）；
 * - 持久化由调用方在拿到返回的新 Conversation 后自行执行。
 *
 * 这些方法行为与原 ChatService 实现逐字一致，包括 selectMessageNode / forkAtMessage 抛出的 web 异常类型
 * —— 其 ApiException.status 驱动 ConversationRoutes 的 HTTP 状态码，必须保持不变。
 */
object ConversationMutations {

    /**
     * 删除指定 message：若所在节点删空则连节点一并移除，并把 selectIndex 收敛到剩余消息的 lastIndex。
     * 找不到 messageId 时返回 null（调用方据此决定是否抛 NotFoundException）。
     */
    fun deleteMessage(conversation: Conversation, messageId: Uuid): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    /**
     * 切换某节点当前选中的分支。节点不存在抛 [NotFoundException]，索引越界抛 [BadRequestException]
     * （保留原 HTTP-status 契约）。若目标索引已是当前选中值，返回原 conversation 实例（无变更，调用方据
     * 引用相等跳过持久化）。
     */
    fun selectMessageNode(conversation: Conversation, nodeId: Uuid, selectIndex: Int): Conversation {
        val targetNode = conversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return conversation
        }

        val updatedNodes = conversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    /**
     * 写入 / 清除某 message 的 translation 字段（text 传 null 即清除）。统一原 updateTranslationField 与
     * clearTranslationField 两处实现，遍历所有节点，仅改命中 messageId 的那条消息，恒返回非空。
     */
    fun updateTranslation(conversation: Conversation, messageId: Uuid, text: String?): Conversation {
        val updatedNodes = conversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = text)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    /**
     * 把 [newMessage] 作为新分支追加到包含 messageId 的节点，并把 selectIndex 指向追加项。为保持原
     * ChatService 的“每个命中节点都用本节点 role 重新落款”语义，对每个命中节点用 role = node.role 复制
     * [newMessage]。未命中任何节点返回 null（调用方据此决定不持久化）。
     *
     * 纯部分仅含节点重写；空输入守卫、settings/assistant 查询、preprocessUserInputParts 等不纯步骤留在
     * ChatService。
     */
    fun editMessage(conversation: Conversation, messageId: Uuid, newMessage: UIMessage): Conversation? {
        var edited = false

        val updatedNodes = conversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + newMessage.copy(role = node.role),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return null

        return conversation.copy(messageNodes = updatedNodes)
    }

    /**
     * 在指定 message 处分叉：取 [0, targetNode] 区间的节点深拷贝（节点 id 与会话 id 全部重新生成），每个
     * part 经 [copyPart] 缝隙转换（默认恒等；ChatService 注入本地文件拷贝），其余会话元数据原样保留。
     * 找不到目标抛 [NotFoundException]。不在此持久化。
     */
    fun forkAtMessage(
        conversation: Conversation,
        messageId: Uuid,
        copyPart: (UIMessagePart) -> UIMessagePart = { it },
    ): Conversation {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = conversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                copyPart(part)
                            }
                        )
                    }
                )
            }

        return Conversation(
            id = Uuid.random(),
            assistantId = conversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = conversation.customSystemPrompt,
            modeInjectionIds = conversation.modeInjectionIds,
            lorebookIds = conversation.lorebookIds,
        )
    }
}
