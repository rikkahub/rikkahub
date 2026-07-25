package me.rerere.rikkahub.data.ai.agent.subagent

import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation

/**
 * Subagent 种类（对齐 Claude Code：Explore 只读探索 / General 预留）。
 */
enum class SubagentKind {
    EXPLORE,
    GENERAL,
}

data class SubagentSpec(
    val kind: SubagentKind = SubagentKind.EXPLORE,
    /** 追加到内置 explore system prompt 之后 */
    val systemPrompt: String = "",
    /**
     * 若非 null，仅允许这些工具名（再与 [ExploreToolAllowlist] 取交集）。
     * null 表示使用默认 explore 白名单。
     */
    val allowedToolNames: Set<String>? = null,
    val mode: AgentMode = AgentMode.PLAN,
    val maxSteps: Int = DEFAULT_MAX_STEPS,
) {
    companion object {
        const val DEFAULT_MAX_STEPS = 12
        const val HARD_MAX_STEPS = 24
    }
}

/**
 * 子代理内单次工具调用轨迹（用于父会话 UI 观测面板）。
 */
data class SubagentTraceStep(
    val index: Int,
    val toolName: String,
    val inputPreview: String = "",
    val outputPreview: String = "",
    /** 输出是否像错误（含 "error" 字段或 error 前缀） */
    val isError: Boolean = false,
)

data class SubagentResult(
    val summary: String,
    val rawNotes: String = "",
    val stepsUsed: Int = 0,
    val toolsUsed: List<String> = emptyList(),
    /** 按执行顺序的工具轨迹，供 UI 时间线展示 */
    val trace: List<SubagentTraceStep> = emptyList(),
    val success: Boolean = true,
    val error: String? = null,
)

/**
 * 一次 subagent 运行请求（由父 agent 工具或内部调用方构造）。
 */
data class SubagentRequest(
    val settings: Settings,
    val assistant: Assistant,
    val conversation: Conversation,
    val task: String,
    val spec: SubagentSpec = SubagentSpec(),
    val inputTransformers: List<InputMessageTransformer> = emptyList(),
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
)

interface SubagentRunner {
    suspend fun run(request: SubagentRequest): SubagentResult
}

object NoOpSubagentRunner : SubagentRunner {
    override suspend fun run(request: SubagentRequest): SubagentResult =
        SubagentResult(
            summary = "",
            success = false,
            error = "Subagent runner is not enabled",
        )
}

/**
 * Explore 默认允许的工具（只读 / 无副作用为主）。
 * 故意排除：写文件、shell、memory 写入、ask_user、MCP、explore 自身（防嵌套）。
 */
object ExploreToolAllowlist {
    val DEFAULT: Set<String> = setOf(
        "workspace_read_file",
        "search_web",
        "scrape_web",
        "recent_chats",
        "conversation_search",
        "use_skill",
        "get_time_info",
    )

    fun isAllowed(name: String): Boolean = name in DEFAULT

    fun filter(names: Collection<String>, extra: Set<String>? = null): List<String> {
        val base = if (extra != null) DEFAULT.intersect(extra) else DEFAULT
        return names.filter { it in base }
    }
}

internal val EXPLORE_SYSTEM_PROMPT = """
You are an Explore subagent running in an isolated session (inspired by Claude Code Explore).

Rules:
1. READ-ONLY. Never modify files, never run shell, never change memory or user state.
2. Use available tools to investigate the task thoroughly before concluding.
3. Prefer workspace_read_file for code/docs; use search/conversation tools when relevant.
4. Your FINAL message must be a structured report for the parent agent (not the end user):

## Findings
- ...

## Relevant paths
- ...

## Recommendations for parent agent
- ...

## Open questions
- ...

5. Be concise but complete. Cite concrete file paths and symbols when possible.
6. If tools fail or information is missing, say so explicitly in Open questions.
""".trimIndent()

const val EXPLORE_SUBAGENT_TOOL_NAME = "explore_subagent"
