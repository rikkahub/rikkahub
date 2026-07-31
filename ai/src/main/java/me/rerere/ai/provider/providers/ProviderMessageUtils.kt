package me.rerere.ai.provider.providers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 消息 parts 按工具边界分组的结果
 * - Content: 普通内容（Text、Image、Reasoning 等）
 * - Tools: 连续的已执行工具
 */
internal sealed class PartGroup {
    data class Content(val parts: List<UIMessagePart>) : PartGroup()
    data class Tools(val tools: List<UIMessagePart.Tool>) : PartGroup()
}

/**
 * 将消息 parts 按工具边界分组
 *
 * 例如 [Text1, Tool1, Tool2, Text2, Tool3] 会分组为:
 * - Content([Text1])
 * - Tools([Tool1, Tool2])
 * - Content([Text2])
 * - Tools([Tool3])
 *
 * 这样可以确保 tool_use/functionCall 后面紧跟 tool_result/functionResponse
 */
internal fun groupPartsByToolBoundary(parts: List<UIMessagePart>): List<PartGroup> {
    val groups = mutableListOf<PartGroup>()
    val currentContent = mutableListOf<UIMessagePart>()
    val currentTools = mutableListOf<UIMessagePart.Tool>()

    fun flushContent() {
        if (currentContent.isNotEmpty()) {
            groups.add(PartGroup.Content(currentContent.toList()))
            currentContent.clear()
        }
    }

    fun flushTools() {
        if (currentTools.isNotEmpty()) {
            groups.add(PartGroup.Tools(currentTools.toList()))
            currentTools.clear()
        }
    }

    for (part in parts) {
        if (part is UIMessagePart.Tool && part.isExecuted) {
            flushContent()
            currentTools.add(part)
        } else {
            flushTools()
            currentContent.add(part)
        }
    }

    flushContent()
    flushTools()
    return groups
}

/**
 * Makes old or malformed tool history acceptable to strict gateways. A [UIMessagePart.Tool]
 * contains both the call and its result, so rewriting its ID keeps the pair consistent.
 */
internal fun List<UIMessage>.withUniqueToolCallIds(): List<UIMessage> {
    val usedIds = mutableSetOf<String>()
    return mapIndexed { messageIndex, message ->
        if (message.role != MessageRole.ASSISTANT) return@mapIndexed message
        var changed = false
        val parts = message.parts.mapIndexed { partIndex, part ->
            if (part !is UIMessagePart.Tool || part.toolCallId.isBlank() || usedIds.add(part.toolCallId)) {
                part
            } else {
                val base = "${part.toolCallId}-history-$messageIndex-$partIndex"
                var candidate = base
                var attempt = 1
                while (!usedIds.add(candidate)) {
                    candidate = "$base-$attempt"
                    attempt += 1
                }
                changed = true
                part.copy(toolCallId = candidate)
            }
        }
        if (changed) message.copy(parts = parts) else message
    }
}
