package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart

private const val TOOL_EXECUTOR_TAG = "ToolCallExecutor"

data class ToolApprovalDecision(
    val tools: List<UIMessagePart.Tool>,
    val pendingIds: List<String>,
)

class ToolCallExecutor(
    private val json: Json,
    private val storeOutput: (String, List<UIMessagePart>, Boolean) -> List<UIMessagePart>,
) {
    constructor(json: Json, outputStore: ToolOutputStore) : this(json, outputStore::storeIfNeeded)

    internal constructor(json: Json) : this(json, { _, output, _ -> output })

    fun prepareApproval(
        calls: List<UIMessagePart.Tool>,
        definitions: List<Tool>,
    ): ToolApprovalDecision {
        val tools = calls.map { call ->
            val definition = definitions.find { it.name == call.toolName }
            val needsApproval = runCatching {
                definition?.needsApproval(call.inputAsJson()) == true
            }.getOrDefault(false)
            if (call.approvalState is ToolApprovalState.Auto &&
                needsApproval
            ) {
                call.copy(approvalState = ToolApprovalState.Pending)
            } else {
                call
            }
        }
        return ToolApprovalDecision(
            tools = tools,
            pendingIds = tools.filter(UIMessagePart.Tool::isPending).map(UIMessagePart.Tool::toolCallId),
        )
    }

    suspend fun execute(
        calls: List<UIMessagePart.Tool>,
        definitions: List<Tool>,
    ): List<UIMessagePart.Tool> {
        val hasShellAccess = definitions.any { it.name == "workspace_shell" }
        return calls.mapNotNull { call ->
            when (val approval = call.approvalState) {
                is ToolApprovalState.Pending -> null
                is ToolApprovalState.Denied -> call.copy(
                    output = errorOutput("tool_denied", "Tool execution denied by user"),
                )
                is ToolApprovalState.Answered -> call.copy(
                    output = listOf(UIMessagePart.Text(approval.answer)),
                )
                else -> executeCall(call, definitions, hasShellAccess)
            }
        }
    }

    private suspend fun executeCall(
        call: UIMessagePart.Tool,
        definitions: List<Tool>,
        hasShellAccess: Boolean,
    ): UIMessagePart.Tool {
        return try {
            val definition = definitions.find { it.name == call.toolName }
                ?: throw IllegalArgumentException("Tool not found")
            val args = json.parseToJsonElement(call.input.ifBlank { "{}" })
            val output = definition.execute(args)
            call.copy(output = storeOutput(call.toolCallId, output, hasShellAccess))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TOOL_EXECUTOR_TAG, "Tool execution failed: ${call.toolName}", error)
            call.copy(output = errorOutput("tool_execution_failed", "Tool execution failed"))
        }
    }

    private fun errorOutput(code: String, message: String) = listOf(
        UIMessagePart.Text(
            json.encodeToString(
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                }
            )
        )
    )
}
