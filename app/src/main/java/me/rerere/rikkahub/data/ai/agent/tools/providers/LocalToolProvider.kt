package me.rerere.rikkahub.data.ai.agent.tools.providers

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.agent.tools.ToolProvider
import me.rerere.rikkahub.data.ai.agent.tools.ToolProviderOrder
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.ai.tools.local.LocalTools

class LocalToolProvider(
    private val localTools: LocalTools,
) : ToolProvider {
    override val order: Int = ToolProviderOrder.LOCAL

    override fun isEnabled(ctx: ToolResolveContext): Boolean =
        ctx.assistant.localTools.isNotEmpty()

    override suspend fun provide(ctx: ToolResolveContext): List<Tool> =
        localTools.getTools(ctx.assistant.localTools, ctx.settings)
}
