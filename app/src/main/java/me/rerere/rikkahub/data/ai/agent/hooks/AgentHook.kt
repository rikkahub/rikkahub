package me.rerere.rikkahub.data.ai.agent.hooks

import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 工具执行前后钩子（学 Claude Code Hooks）。
 * 默认空实现；可用于截断、日志、metrics。不得吞掉 CancellationException。
 */
interface AgentHook {
    suspend fun beforeTool(
        tool: Tool,
        args: JsonElement,
    ) {
    }

    suspend fun afterTool(
        tool: Tool,
        args: JsonElement,
        result: Result<List<UIMessagePart>>,
    ) {
    }
}

/** 组合多个 hook，按注册顺序调用。 */
class CompositeAgentHook(
    private val hooks: List<AgentHook>,
) : AgentHook {
    override suspend fun beforeTool(tool: Tool, args: JsonElement) {
        hooks.forEach { it.beforeTool(tool, args) }
    }

    override suspend fun afterTool(
        tool: Tool,
        args: JsonElement,
        result: Result<List<UIMessagePart>>,
    ) {
        hooks.forEach { it.afterTool(tool, args, result) }
    }
}

object NoOpAgentHook : AgentHook
