package me.rerere.rikkahub.service.notification

import me.rerere.ai.ui.UIMessagePart

/**
 * 一条 Live Update 通知的三段内容：状态徽标（chip）、副标题状态文案（subText）、正文（content）。
 * 取代此前在 ChatService 里以匿名 Triple<chip, status, content> 解构传递的形态，使其可被命名访问。
 */
data class ChatNotificationContent(
    val chipText: String,
    val statusText: String,
    val contentText: String,
)

/**
 * Context 无关的本地化字符串载体。把 R.string 解析后的静态文案以普通 String 携带，唯一带参的工具状态
 * 文案（"Running tool: %s"）以 lambda 携带，使 [determineNotificationContent] 保持纯函数、可被 JVM 单测
 * （无 android.content.Context / R.getString 依赖）。
 */
data class NotificationStrings(
    val chipTool: String,
    val chipThinking: String,
    val chipWriting: String,
    val statusThinking: String,
    val statusWriting: String,
    val statusDefault: String,
    val tool: (toolName: String) -> String,
)

/**
 * 纯函数：根据消息 parts 的最近状态决定 Live Update 通知的三段内容。逐字搬出自 ChatService，分支顺序与
 * 截断规则保持完全一致，以便 JVM 单测钉死。
 */
internal fun determineNotificationContent(
    parts: List<UIMessagePart>,
    strings: NotificationStrings,
): ChatNotificationContent {
    // 检查最近的 part 来确定状态
    val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
    val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
    val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

    return when {
        // 正在执行工具
        lastTool != null && !lastTool.isExecuted -> {
            val toolName = lastTool.toolName.removePrefix("mcp__")
            ChatNotificationContent(
                chipText = strings.chipTool,
                statusText = strings.tool(toolName),
                contentText = lastTool.input.take(100),
            )
        }
        // 正在思考（Reasoning 未结束）
        lastReasoning != null && lastReasoning.finishedAt == null -> {
            ChatNotificationContent(
                chipText = strings.chipThinking,
                statusText = strings.statusThinking,
                contentText = lastReasoning.reasoning.takeLast(200),
            )
        }
        // 正在写回复
        lastText != null -> {
            ChatNotificationContent(
                chipText = strings.chipWriting,
                statusText = strings.statusWriting,
                contentText = lastText.text.takeLast(200),
            )
        }
        // 默认状态
        else -> {
            ChatNotificationContent(
                chipText = strings.chipWriting,
                statusText = strings.statusDefault,
                contentText = "",
            )
        }
    }
}
