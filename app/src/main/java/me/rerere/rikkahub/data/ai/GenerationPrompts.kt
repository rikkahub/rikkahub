package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository

internal fun buildMemoryPrompt(model: Model, memories: List<AssistantMemory>) =
    buildString {
        append("## Memories")
        append("These are memories that you can reference in the future conversations.")
        append("\n<memories>\n")
        memories.forEach { memory ->
            append("<record>\n")
            append("<id>${memory.id}</id>")
            append("<content>${memory.content}</content>")
            append("</record>\n")
        }
        append("</memories>\n")

        if (model.abilities.contains(ModelAbility.TOOL)) {
            append(
                """
                    ## Memory Tool
                    你是一个无状态的大模型，你**无法存储记忆**，因此为了记住信息，你需要使用**记忆工具**。
                    记忆工具允许你(助手)存储多条信息(record)以便在跨对话聊天中记住信息。
                    你可以使用`create_memory`, `edit_memory`和`delete_memory`工具创建，更新或删除记忆。
                    - 如果记忆内没有相关信息，你需要调用`create_memory`工具来创建一个记忆记录。
                    - 如果已经有相关记录，请调用`edit_memory`工具来更新一个记忆记录。
                    - 如果一个记忆过时或者无用了，请调用`delete_memory`工具来删除一个记忆记录。
                    这些记忆会自动包含在未来的对话上下文中，在<memories>标签内。
                    请勿在记忆中存储敏感信息，敏感信息包括：用户的民族、宗教信仰、性取向、政治观点及党派归属、性生活、犯罪记录等。
                    在与用户聊天过程中，你可以像一个私人秘书一样**主动的**记录用户相关的信息到记忆里，包括但不限于：
                    - 用户昵称/姓名
                    - 年龄/性别/兴趣爱好
                    - 计划事项等
                    - 聊天风格偏好
                    - 工作相关
                    - 首次聊天时间
                    - ...
                    请主动调用工具记录，而不是需要用户要求。
                    记忆如果包含日期信息，请包含在内，请使用绝对时间格式，并且当前时间是 {cur_datetime}。
                    无需告知用户你已更改记忆记录，也不要在对话中直接显示记忆内容，除非用户主动要求。
                    相似或相关的记忆应合并为一条记录，而不要重复记录，过时记录应删除。
                    你可以在和用户闲聊的时候暗示用户你能记住东西。
                """.trimIndent()
            )
        }
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
