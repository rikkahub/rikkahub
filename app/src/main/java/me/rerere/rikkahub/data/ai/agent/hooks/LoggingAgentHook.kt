package me.rerere.rikkahub.data.ai.agent.hooks

import android.util.Log
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 默认接入生成路径的日志钩子（学 Claude Code hooks 的可观测性）。
 * 不改变工具语义；失败不影响调用方（异常向上抛出，由 AgentLoop 处理）。
 */
class LoggingAgentHook(
    private val log: (String, String) -> Unit = { tag, msg -> Log.i(tag, msg) },
) : AgentHook {
    override suspend fun beforeTool(tool: Tool, args: JsonElement) {
        log(TAG, "beforeTool name=${tool.name} args=${args.toString().take(500)}")
    }

    override suspend fun afterTool(
        tool: Tool,
        args: JsonElement,
        result: Result<List<UIMessagePart>>,
    ) {
        log(
            TAG,
            "afterTool name=${tool.name} success=${result.isSuccess} " +
                "error=${result.exceptionOrNull()?.message ?: "-"}",
        )
    }

    companion object {
        const val TAG = "AgentHook"
    }
}
