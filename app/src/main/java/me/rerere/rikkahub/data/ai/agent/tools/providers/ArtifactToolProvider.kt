package me.rerere.rikkahub.data.ai.agent.tools.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.tools.ToolProvider
import me.rerere.rikkahub.data.ai.agent.tools.ToolProviderOrder
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.artifacts.ToolArtifactRunScope
import me.rerere.rikkahub.data.artifacts.ToolArtifactStore
import me.rerere.rikkahub.data.repository.AgentRunRepository

/** Exposes opaque current-run artifacts and an explicitly authorized direct-parent scope only. */
class ArtifactToolProvider(
    private val artifactStore: ToolArtifactStore,
    private val json: Json,
    private val agentRunRepository: AgentRunRepository? = null,
) : ToolProvider {
    override val order: Int = ToolProviderOrder.ARTIFACT

    override fun isEnabled(ctx: ToolResolveContext): Boolean = ctx.agentRunId != null

    override suspend fun provide(ctx: ToolResolveContext): List<Tool> {
        val runId = checkNotNull(ctx.agentRunId)
        val scope = ToolArtifactRunScope(
            assistantId = ctx.assistant.id.toString(),
            conversationId = ctx.conversation.id.toString(),
            runId = runId,
        )
        return listOf(
            Tool(
                name = "artifact_read",
                description = "Read a bounded fragment of a private artifact created by this run.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("artifact_id", property("Opaque artifact identifier."))
                            put("tool_execution_id", property("Opaque execution identifier that created the artifact."))
                            put("run_id", property("Current run, or the explicitly authorized direct parent run."))
                            put("offset", integerProperty())
                            put("max_bytes", integerProperty())
                        },
                        required = listOf("artifact_id", "tool_execution_id"),
                    )
                },
                execute = { args ->
                    val input = args.jsonObject
                    val fragment = artifactStore.readFragment(
                        sourceScope(ctx, scope, input).forExecution(input.requiredString("tool_execution_id")),
                        input.requiredString("artifact_id"),
                        input["offset"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0,
                        input["max_bytes"]?.jsonPrimitive?.intOrNull ?: 4 * 1024,
                    )
                    listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
                        put("artifact_id", fragment.artifactId)
                        put("offset", fragment.offset)
                        put("content", fragment.content)
                        put("end_reached", fragment.endReached)
                    })))
                },
            ),
            Tool(
                name = "artifact_search",
                description = "Search a private artifact created by this run using an opaque artifact identifier.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("artifact_id", property("Opaque artifact identifier."))
                            put("tool_execution_id", property("Opaque execution identifier that created the artifact."))
                            put("run_id", property("Current run, or the explicitly authorized direct parent run."))
                            put("query", property("Text to find."))
                            put("max_matches", integerProperty())
                        },
                        required = listOf("artifact_id", "tool_execution_id", "query"),
                    )
                },
                execute = { args ->
                    val input = args.jsonObject
                    val matches = artifactStore.search(
                        sourceScope(ctx, scope, input).forExecution(input.requiredString("tool_execution_id")),
                        input.requiredString("artifact_id"),
                        input.requiredString("query"),
                        input["max_matches"]?.jsonPrimitive?.intOrNull ?: 20,
                    )
                    listOf(UIMessagePart.Text(json.encodeToString(matches)))
                },
            ),
        )
    }

    private fun property(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun integerProperty() = buildJsonObject { put("type", "integer") }

    private suspend fun sourceScope(
        ctx: ToolResolveContext,
        currentScope: ToolArtifactRunScope,
        input: kotlinx.serialization.json.JsonObject,
    ): ToolArtifactRunScope {
        val requestedRunId = input["run_id"]?.jsonPrimitive?.contentOrNull ?: return currentScope
        if (requestedRunId == currentScope.runId) return currentScope
        require(requestedRunId == ctx.authorizedParentArtifactRunId) {
            "Artifact source run is not authorized"
        }
        require(agentRunRepository?.isAuthorizedParentArtifactRun(
            childRunId = currentScope.runId,
            parentRunId = requestedRunId,
            assistantId = currentScope.assistantId,
            conversationId = currentScope.conversationId,
        ) == true) { "Parent artifact source is not authorized" }
        return ToolArtifactRunScope(currentScope.assistantId, currentScope.conversationId, requestedRunId)
    }

    private fun kotlinx.serialization.json.JsonObject.requiredString(name: String): String =
        get(name)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$name is required")
}
