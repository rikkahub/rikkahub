package me.rerere.rikkahub.data.ai.agent.tools.providers

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.agent.tools.ToolProvider
import me.rerere.rikkahub.data.ai.agent.tools.ToolProviderOrder
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.ai.tools.createSearchTools

class SearchToolProvider : ToolProvider {
    override val order: Int = ToolProviderOrder.SEARCH

    override fun isEnabled(ctx: ToolResolveContext): Boolean =
        ctx.assistant.enableWebSearch

    override suspend fun provide(ctx: ToolResolveContext): List<Tool> =
        createSearchTools(ctx.settings).toList()
}
