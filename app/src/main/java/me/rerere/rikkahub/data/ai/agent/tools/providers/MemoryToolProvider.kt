package me.rerere.rikkahub.data.ai.agent.tools.providers

import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.agent.tools.ToolProvider
import me.rerere.rikkahub.data.ai.agent.tools.ToolProviderOrder
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.repository.MemoryRepository

class MemoryToolProvider(
    private val json: Json,
    private val memoryRepo: MemoryRepository,
) : ToolProvider {
    override val order: Int = ToolProviderOrder.MEMORY

    override fun isEnabled(ctx: ToolResolveContext): Boolean =
        ctx.assistant.enableMemory

    override suspend fun provide(ctx: ToolResolveContext): List<Tool> {
        val memoryAssistantId = if (ctx.assistant.useGlobalMemory) {
            MemoryRepository.GLOBAL_MEMORY_ID
        } else {
            ctx.assistant.id.toString()
        }
        return buildMemoryTools(
            json = json,
            onCreation = { content -> memoryRepo.addMemory(memoryAssistantId, content) },
            onUpdate = { id, content -> memoryRepo.updateContent(id, content) },
            onDelete = { id -> memoryRepo.deleteMemory(id) },
        )
    }
}
