package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart

/**
 * OpenAI Chat Completions may interleave tool-call deltas. The protocol's `index` is therefore
 * retained as the stream-local key until an ID is available for normal message merging.
 */
internal class OpenAIChatToolCallAssembler {
    private data class ToolCallState(
        var id: String? = null,
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
        var emittedName: Boolean = false,
        var emittedArgumentLength: Int = 0,
    )

    private val statesByChoice = mutableMapOf<Int, MutableMap<Int, ToolCallState>>()

    /** Returns null until a call ID and function name are both available, so it cannot execute. */
    fun resolve(choiceIndex: Int, toolCall: JsonObject): UIMessagePart.Tool? {
        val toolIndex = toolCall["index"]?.jsonPrimitive?.intOrNull ?: return null
        val state = statesByChoice.getOrPut(choiceIndex) { mutableMapOf() }
            .getOrPut(toolIndex) { ToolCallState() }
        toolCall["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { state.id = it }
        toolCall["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull?.let { state.name += it }
        toolCall["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull?.let {
            state.arguments.append(it)
        }

        val id = state.id ?: return null
        if (state.name.isBlank()) return null
        val name = if (state.emittedName) "" else state.name.also { state.emittedName = true }
        val arguments = state.arguments.substring(state.emittedArgumentLength)
        state.emittedArgumentLength = state.arguments.length
        if (name.isEmpty() && arguments.isEmpty()) return null
        return UIMessagePart.Tool(id, name, arguments)
    }
}
