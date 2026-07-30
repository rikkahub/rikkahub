package me.rerere.rikkahub.data.ai.agent.tools.providers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.subagent.EXPLORE_SUBAGENT_TOOL_NAME
import me.rerere.rikkahub.data.ai.agent.subagent.SubagentKind
import me.rerere.rikkahub.data.ai.agent.subagent.SubagentRequest
import me.rerere.rikkahub.data.ai.agent.subagent.SubagentRunner
import me.rerere.rikkahub.data.ai.agent.subagent.SubagentSpec
import me.rerere.rikkahub.data.ai.agent.prompt.ProjectDocsTransformer
import me.rerere.rikkahub.data.ai.agent.tools.ToolProvider
import me.rerere.rikkahub.data.ai.agent.tools.ToolProviderOrder
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.repository.WorkspaceRepository

/**
 * 向主 Agent 暴露 [EXPLORE_SUBAGENT_TOOL_NAME]。
 * 子代理会话内 [ToolResolveContext.isSubagentRun] = true，不会再次注册本工具。
 *
 * [subagentRunner] 使用延迟获取，避免与 ToolRegistry 构造循环依赖。
 */
class ExploreSubagentToolProvider(
    private val subagentRunner: () -> SubagentRunner,
    private val json: Json,
    private val workspaceRepository: WorkspaceRepository,
    private val projectDocsTransformer: ProjectDocsTransformer,
) : ToolProvider {
    private val exploreInputTransformers: List<InputMessageTransformer> by lazy {
        listOf(
            WorkspaceReminderTransformer(workspaceRepository),
            projectDocsTransformer,
        )
    }
    override val order: Int = ToolProviderOrder.SUBAGENT

    override fun isEnabled(ctx: ToolResolveContext): Boolean =
        !ctx.isSubagentRun

    override suspend fun provide(ctx: ToolResolveContext): List<Tool> = listOf(
        Tool(
            name = EXPLORE_SUBAGENT_TOOL_NAME,
            description = """
                Spawn a read-only Explore subagent in an isolated session to investigate a question
                about the workspace, codebase, or related context.
                Use this for non-trivial research before making changes: map files, understand modules,
                gather facts. The subagent cannot write files or run shell.
                Returns a structured findings report for you (the parent agent) to act on.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("task", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Clear investigation goal for the explore subagent " +
                                    "(what to find out, which area to inspect)."
                            )
                        })
                        put("max_steps", buildJsonObject {
                            put("type", "integer")
                            put(
                                "description",
                                "Max tool-loop steps for the subagent (default ${SubagentSpec.DEFAULT_MAX_STEPS}, " +
                                    "max ${SubagentSpec.HARD_MAX_STEPS})."
                            )
                        })
                    },
                    required = listOf("task"),
                )
            },
            systemPrompt = { _, _ ->
                """
                **Explore subagent**
                - Call `$EXPLORE_SUBAGENT_TOOL_NAME` when you need a focused, read-only investigation
                  without polluting the main chat transcript with intermediate tool noise.
                - Pass a specific `task`. Use the returned findings before editing or running shell.
                - The subagent runs in PLAN-like isolation; it cannot modify the workspace.
                """.trimIndent()
            },
            needsApproval = { false },
            execute = { args ->
                val obj = args.jsonObject
                val task = obj["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (task.isEmpty()) {
                    return@Tool listOf(
                        UIMessagePart.Text(
                            json.encodeToString(
                                buildJsonObject {
                                    put("error", JsonPrimitive("task is required"))
                                }
                            )
                        )
                    )
                }
                val maxSteps = obj["max_steps"]?.jsonPrimitive?.intOrNull
                    ?: SubagentSpec.DEFAULT_MAX_STEPS

                val status: MutableStateFlow<String?> =
                    ctx.processingStatus ?: MutableStateFlow(null)
                val result = subagentRunner().run(
                    SubagentRequest(
                        settings = ctx.settings,
                        assistant = ctx.assistant,
                        conversation = ctx.conversation,
                        task = task,
                        parentRunId = ctx.agentRunId,
                        spec = SubagentSpec(
                            kind = SubagentKind.EXPLORE,
                            maxSteps = maxSteps,
                        ),
                        inputTransformers = exploreInputTransformers,
                        processingStatus = status,
                    )
                )

                val payload = buildJsonObject {
                    result.childRunId?.let { put("child_run_id", it) }
                    putJsonArray("findings") {
                        result.report.findings.forEach { add(it) }
                    }
                    putJsonArray("evidence_paths") {
                        result.report.evidencePaths.forEach { add(it) }
                    }
                    put("confidence", result.report.confidence)
                    putJsonArray("unresolved") {
                        result.report.unresolved.distinct().forEach { add(it) }
                    }
                }
                listOf(UIMessagePart.Text(json.encodeToString(payload)))
            },
        )
    )
}
