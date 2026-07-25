package me.rerere.rikkahub.data.ai.agent.prompt

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/**
 * 将 [ProjectDocsLoader] 结果追加到 system 消息。
 * 无 Workspace / shell 未就绪 / 无文档时 no-op。
 */
class ProjectDocsTransformer(
    private val workspaceRepository: WorkspaceRepository,
    private val loader: ProjectDocsLoader = ProjectDocsLoader(workspaceRepository),
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages

        val docs = loader.load(workspaceId, ctx.workspaceCwd)
        if (docs.isBlank()) return messages

        return appendToSystem(messages, "\n\n$docs")
    }
}

internal fun appendToSystem(messages: List<UIMessage>, extra: String): List<UIMessage> {
    val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
    return if (systemIndex >= 0) {
        messages.toMutableList().apply {
            this[systemIndex] = this[systemIndex].appendText(extra)
        }
    } else {
        listOf(UIMessage.system(extra.trimStart())) + messages
    }
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
