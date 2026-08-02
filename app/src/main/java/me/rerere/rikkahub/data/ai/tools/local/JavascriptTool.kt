package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal const val JAVASCRIPT_EXECUTION_DISABLED_MESSAGE =
    "JavaScript execution is disabled because this QuickJS wrapper cannot interrupt a running script safely."

internal fun buildJavascriptTool(): Tool = Tool(
    name = "eval_javascript",
    description = """
        JavaScript execution is currently unavailable. The installed QuickJS wrapper cannot safely interrupt
        a script that exceeds its execution budget.
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "The JavaScript code to execute")
                })
            },
            required = listOf("code")
        )
    },
    execute = {
        val payload = buildJsonObject {
            put("error", "JAVASCRIPT_EXECUTION_DISABLED")
            put("message", JAVASCRIPT_EXECUTION_DISABLED_MESSAGE)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
