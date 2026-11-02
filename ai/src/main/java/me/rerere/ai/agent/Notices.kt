package me.rerere.ai.agent

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 把消息里的 [UIMessagePart.Notice] 在所在位置拆成 user 消息，组装请求时在最后调用。
 *
 * 助手消息里的 Notice 是这一轮中途取走的，只会出现在开头或一批工具执行完之后，拆开后和模型看到的轮次一致：
 * `[Tool, Notice, Text]` 变成 助手(tool_use) → user(tool_result) → user(notice) → 助手(text)。
 * 开始新的一轮时取走的 Notice 单独存成 user 消息（见 [UIMessage.isNoticeOnly]）。
 *
 * 由调用方在组装请求时调用，不放进 agent 循环：压缩这类不经过 [runAgent] 的请求也要用，否则请求前缀不一致。
 */
fun List<UIMessage>.expandNotices(): List<UIMessage> = flatMap { message ->
    if (message.parts.none { it is UIMessagePart.Notice }) {
        return@flatMap listOf(message)
    }
    buildList {
        val buffer = mutableListOf<UIMessagePart>()
        fun flush() {
            if (buffer.isNotEmpty()) add(message.copy(parts = buffer.toList()))
            buffer.clear()
        }
        message.parts.forEach { part ->
            if (part is UIMessagePart.Notice) {
                flush()
                add(UIMessage.user(part.text).copy(createdAt = message.createdAt, isSynthetic = true))
            } else {
                buffer.add(part)
            }
        }
        flush()
    }
}
