package me.rerere.locallm.litert

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool

/**
 * Prompt-engineered tool calling for LiteRT models. The model is taught the
 * <tool_call>{json}</tool_call> shape via a system-prompt prefix, the streaming
 * response is scanned for blocks, and each block is parsed with lenient JSON
 * (handles trailing commas + whitespace).
 *
 * Mirrors the path AICoreProvider already uses for Gemini Nano.
 */
object LiteRtToolPrefix {

    /** Lenient JSON: trailing commas are ignored, unknown keys preserved. */
    private val lenient = Json {
        isLenient = true
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

    private val toolCallPattern = Regex(
        pattern = "<tool_call>\\s*(\\{[\\s\\S]*?\\})\\s*</tool_call>",
        options = setOf(RegexOption.MULTILINE),
    )

    data class ParsedCall(val name: String, val arguments: JsonObject)

    /**
     * Returns the system-prompt prefix that teaches the model how to invoke tools.
     * Empty string when [tools] is empty.
     */
    fun buildPrefix(tools: List<Tool>): String {
        if (tools.isEmpty()) return ""
        val toolList = tools.joinToString("\n") { tool ->
            val schema = runCatching { tool.parameters().toString() }.getOrDefault("{}")
            "- ${tool.name}: ${tool.description}\n  arguments schema: $schema"
        }
        return """
You have access to the following tools. To call one, emit a single line in this exact format:
<tool_call>{"name": "<tool_name>", "arguments": {<json arguments>}}</tool_call>

Available tools:
$toolList

Only emit tool calls when actually needed. Otherwise reply with normal text.

""".trimStart()
    }

    /**
     * Scan [response] for <tool_call>...</tool_call> blocks and return the parsed calls.
     * Returns an empty list when nothing matches or every match fails to parse.
     */
    fun extractToolCalls(response: String): List<ParsedCall> {
        return toolCallPattern.findAll(response).mapNotNull { match ->
            val raw = match.groupValues[1]
            runCatching {
                val obj = lenient.parseToJsonElement(raw).jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                val args = (obj["arguments"] as? JsonObject) ?: JsonObject(emptyMap())
                ParsedCall(name, args)
            }.getOrNull()
        }.toList()
    }

    /**
     * Returns [response] with every <tool_call>...</tool_call> block removed.
     */
    fun stripToolCallBlocks(response: String): String =
        toolCallPattern.replace(response, "").trim().let {
            it.replace(Regex("\\s{3,}"), "  ")
        }
}
