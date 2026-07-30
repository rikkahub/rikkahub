package me.rerere.rikkahub.data.ai.agent.subagent

import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ChildRunBudgetSnapshot
import me.rerere.rikkahub.data.model.ChildRunReport

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
    val budget: ChildRunBudget = ChildRunBudget(),
) {
    companion object {
        const val DEFAULT_MAX_STEPS = 12
        const val HARD_MAX_STEPS = 24
    }
}

data class ChildRunBudget(
    val maxToolCalls: Int = 12,
    val maxOutputTokens: Int = 2_048,
    val maxDurationMillis: Long = 120_000,
    val maxContextTokens: Int = 16 * 1024,
) {
    init {
        require(maxToolCalls > 0 && maxOutputTokens > 0 && maxDurationMillis > 0 && maxContextTokens > 0)
    }

    fun snapshot(maxSteps: Int) = ChildRunBudgetSnapshot(
        maxSteps = maxSteps,
        maxToolCalls = maxToolCalls,
        maxOutputTokens = maxOutputTokens,
        maxDurationMillis = maxDurationMillis,
        maxContextTokens = maxContextTokens,
    )
}

/** Parent-level limits. They are injected into the runner so installations may configure them centrally. */
data class ControlledSubagentLimits(
    val maxConcurrentChildren: Int = 2,
    val maxChildrenPerParent: Int = 2,
    val maxTotalTokensPerParent: Int = 16_384,
    val maxTotalDurationMillisPerParent: Long = 10 * 60_000,
) {
    init {
        require(maxConcurrentChildren in 1..2) { "Controlled Explore supports one or two concurrent children" }
        require(maxChildrenPerParent in 1..2) { "Controlled Explore supports at most two children per parent" }
        require(maxTotalTokensPerParent > 0 && maxTotalDurationMillisPerParent > 0)
    }
}

/** Deterministic admission used by the agent loop before launching same-turn Explore children. */
object ControlledExploreBatch {
    fun admittedCallIds(callIds: List<String>, maxConcurrent: Int = 2): Set<String> {
        require(maxConcurrent in 1..2) { "Controlled Explore supports one or two concurrent children" }
        return callIds.take(maxConcurrent).toSet()
    }
}

data class SubagentResult(
    val childRunId: String? = null,
    val report: ChildRunReport = ChildRunReport(),
)

/**
 * 一次 subagent 运行请求（由父 agent 工具或内部调用方构造）。
 */
data class SubagentRequest(
    val settings: Settings,
    val assistant: Assistant,
    val conversation: Conversation,
    val task: String,
    val parentRunId: String? = null,
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
            report = ChildRunReport(unresolved = listOf("SUBAGENT_RUNNER_NOT_ENABLED")),
        )
}

/**
 * Explore 默认允许的工具（只读 / 无副作用为主）。
 * 故意排除：写文件、shell、memory 写入、ask_user、MCP、explore 自身（防嵌套）。
 */
object ExploreToolAllowlist {
    val DEFAULT: Set<String> = setOf(
        "workspace_read_file",
        "workspace_search_files",
        "artifact_read",
        "artifact_search",
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
3. Use only the exposed repository read tools. Do not request network, shell, memory, MCP, local-device, or nested-agent access.
4. Your FINAL message must be a structured report for the parent agent (not the end user):

## Findings
- ...

## Evidence paths
- ...

## Confidence
- HIGH, MEDIUM, or LOW

## Open questions
- ...

5. Be concise but complete. Cite concrete repository paths and symbols when possible.
6. If tools fail or information is missing, say so explicitly in Open questions.
""".trimIndent()

const val EXPLORE_SUBAGENT_TOOL_NAME = "explore_subagent"
