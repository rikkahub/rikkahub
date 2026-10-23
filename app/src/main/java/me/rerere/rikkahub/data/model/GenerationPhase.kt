package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** 正在生成的助手消息此刻在做什么，通知和悬浮窗用它展示进度 */
sealed interface GenerationPhase {
    /** 正在执行工具，[toolName] 已去掉 MCP 服务器前缀 */
    data class RunningTool(val toolName: String, val input: String) : GenerationPhase

    data class Thinking(val reasoning: String) : GenerationPhase

    data class Writing(val text: String) : GenerationPhase

    /** 还没有输出内容 */
    data object Waiting : GenerationPhase
}

/** 按最近的内容判断：还没执行完的工具优先，其次是没结束的思考，再其次是正文 */
fun UIMessage.generationPhase(): GenerationPhase {
    val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
    val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
    val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()
    return when {
        lastTool != null && !lastTool.isExecuted -> GenerationPhase.RunningTool(
            toolName = lastTool.toolName.substringAfterLast("__"),
            input = lastTool.input,
        )

        lastReasoning != null && lastReasoning.finishedAt == null -> GenerationPhase.Thinking(lastReasoning.reasoning)
        lastText != null -> GenerationPhase.Writing(lastText.text)
        else -> GenerationPhase.Waiting
    }
}
