package me.rerere.rikkahub.data.ai.agent.tools.providers

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.agent.tools.ToolProvider
import me.rerere.rikkahub.data.ai.agent.tools.ToolProviderOrder
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.repository.ConversationRepository

class ConversationToolProvider(
    private val conversationRepo: ConversationRepository,
) : ToolProvider {
    override val order: Int = ToolProviderOrder.CONVERSATION

    override fun isEnabled(ctx: ToolResolveContext): Boolean =
        ctx.assistant.enableRecentChatsReference

    override suspend fun provide(ctx: ToolResolveContext): List<Tool> =
        createConversationTools(conversationRepo, ctx.assistant.id)
}
