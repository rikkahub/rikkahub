package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        append("## Memories")
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        append("\n<memories>\n")
        memories.forEach { memory ->
            append("<record>\n")
            append("<id>${memory.id}</id>")
            append("<content>${memory.content}</content>")
            append("</record>\n")
        }
        append("</memories>\n")

    }

internal suspend fun buildRecentChatsPrompt(
    assistant: Assistant,
    conversationRepo: ConversationRepository
): String {
    val recentConversations = conversationRepo.getRecentConversations(
        assistantId = assistant.id,
        limit = 10,
    )
    if (recentConversations.isNotEmpty()) {
        return buildString {
            append("## 最近的对话\n")
            append("这是用户最近的一些对话，你可以参考这些对话了解用户偏好:\n")
            append("\n<recent_chats>\n")
            recentConversations.forEach { conversation ->
                append("<conversation>\n")
                append("  <title>${conversation.title}</title>")
                append("</conversation>\n")
            }
            append("</recent_chats>\n")
        }
    }
    return ""
}
