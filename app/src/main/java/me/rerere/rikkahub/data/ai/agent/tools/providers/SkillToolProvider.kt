package me.rerere.rikkahub.data.ai.agent.tools.providers

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.agent.tools.ToolProvider
import me.rerere.rikkahub.data.ai.agent.tools.ToolProviderOrder
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.files.SkillManager

class SkillToolProvider(
    private val skillManager: SkillManager,
) : ToolProvider {
    override val order: Int = ToolProviderOrder.SKILL

    override fun isEnabled(ctx: ToolResolveContext): Boolean =
        ctx.assistant.enabledSkills.isNotEmpty()

    override suspend fun provide(ctx: ToolResolveContext): List<Tool> =
        createSkillTools(
            enabledSkills = ctx.assistant.enabledSkills,
            allSkills = skillManager.listSkills(),
        )
}
