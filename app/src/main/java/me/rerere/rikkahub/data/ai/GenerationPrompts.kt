package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.toLocalDate
import me.rerere.rikkahub.utils.toLocalDateTime

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        append("## Memories")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        append("<memories>\n")
        memories.forEach { memory ->
            append("<record>\n")
            append("<id>${memory.id}</id>")
            append("<content>${memory.content}</content>")
            append("</record>\n")
        }
        append("</memories>")
        appendLine()
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
            append("## Recent Chats\n")
            append("These are some of the user's recent conversations. You can use them to understand user preferences:\n")
            append("\n<recent_chats>\n")
            recentConversations.forEach { conversation ->
                append("<conversation>\n")
                append("  <title>${conversation.title}</title>\n")
                append("  <last_chat>${conversation.updateAt.toLocalDate()}</last_chat>\n")
                append("</conversation>\n")
            }
            append("</recent_chats>\n")
        }
    }
    return ""
}
