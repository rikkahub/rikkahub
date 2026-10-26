package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.BackgroundShellManager
import me.rerere.rikkahub.service.BackgroundShellTask

private const val COMMAND_PREVIEW_CHARS = 80

/**
 * 后台命令完成提醒注入转换器
 *
 * 会话里有后台命令自行结束、而模型还没读过结果时, 在消息末尾追加一条 <background_shell_notice>,
 * 生成过程中的下一步或用户的下一条消息都能看到。提醒不落库, 所以每一步都会注入,
 * 直到模型用 workspace_shell_output 读过结果。
 */
class BackgroundShellReminderTransformer(
    private val manager: BackgroundShellManager,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val conversationId = ctx.conversationId ?: return messages
        // 解绑工作区后读取输出的工具也没有了, 提醒只会让模型无所适从
        if (ctx.assistant.workspaceId == null) return messages
        val finished = manager.tasksOf(conversationId).filter { it.unreported }
        if (finished.isEmpty() || !messages.canAppendNotice()) return messages
        return messages + UIMessage.user(buildNotice(finished)).copy(isSynthetic = true)
    }
}

/**
 * 只追加在用户消息或已执行完工具的助手消息之后: 这两种情况下一条本来就该轮到模型回复。
 * 末尾是普通助手消息时是在续写, 追加用户消息会改变语义。
 */
private fun List<UIMessage>.canAppendNotice(): Boolean {
    val last = lastOrNull() ?: return false
    return when (last.role) {
        MessageRole.USER -> true
        MessageRole.ASSISTANT -> (last.parts.lastOrNull() as? UIMessagePart.Tool)?.isExecuted == true
        else -> false
    }
}

private fun buildNotice(tasks: List<BackgroundShellTask>): String = buildString {
    appendLine("<background_shell_notice>")
    appendLine("These background commands have finished since you last checked them:")
    tasks.forEach { task ->
        val command = task.command.replace("\n", " ").trim().let {
            if (it.length > COMMAND_PREVIEW_CHARS) it.take(COMMAND_PREVIEW_CHARS) + "…" else it
        }
        appendLine("- `${task.id}` exited with code ${task.exitCode}: `$command`")
    }
    appendLine("Read their remaining output with `workspace_shell_output`.")
    append("</background_shell_notice>")
}
