package me.rerere.rikkahub.data.ai.subagent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * Pure (Android-free) primitives for the in-conversation subagent feature (issue #201).
 *
 * These live in `:app` rather than `:ai` because they reference [Assistant] / [Settings],
 * which the lower `:ai` module cannot see. They are deliberately side-effect-free so the
 * load-bearing resolution / recursion-guard / extraction logic is JVM-unit-testable without
 * a Context, Provider, or running generation.
 */

/**
 * The reserved tool NAME the spawn ("task") tool occupies. A subagent must never be able to
 * spawn further subagents (recursion guard, depth bounded at 1), so this name is filtered out
 * of any tool pool handed to a subagent — see [filterToolsForSubagent].
 *
 * Raw MCP tools are prefixed `mcp__` at the ChatService build site (`ChatService.kt`), so a
 * malicious MCP tool literally named `task` becomes `mcp__task` and cannot collide with this
 * reserved name. Filtering by the exact name is therefore a sound structural guard.
 */
const val SPAWN_TOOL_NAME: String = "task"

/**
 * Resolve the model a subagent should run under.
 *
 * Resolution order (the load-bearing invariant):
 *   1. the sub-[Assistant]'s own pinned model ([Assistant.chatModelId]) if set,
 *   2. else the parent's model ([parentModelId]),
 *   3. else the global default ([Settings.chatModelId], a non-null [Uuid]).
 *
 * Inheriting the parent's model when the sub-Assistant pins none is the chosen v1 default
 * (cache/consistency): a sub-Assistant that wants the cheap path simply pins its own model.
 * Because [Settings.chatModelId] is non-null, the result is non-null whenever a global default
 * exists, so a caller can `error()` on a genuinely unresolvable id rather than on null here.
 */
fun resolveSubagentModel(
    sub: Assistant,
    parentModelId: Uuid?,
    settings: Settings,
): Uuid = sub.chatModelId ?: parentModelId ?: settings.chatModelId

/**
 * Strip the spawn tool from a subagent's tool pool across ALL sources (MCP / skills / local).
 *
 * Invariant: the spawn tool (name == [SPAWN_TOOL_NAME]) is NEVER present in the output, for any
 * input pool — this is the structural recursion guard. The output is always a subset of the
 * input (Conservation) and the function is idempotent.
 */
fun filterToolsForSubagent(tools: List<Tool>): List<Tool> =
    tools.filterNot { it.name == SPAWN_TOOL_NAME }

/**
 * Extract the subagent's final answer text from its terminal message list.
 *
 * Why this is not just `messages.last().toText()`:
 *  - The agentic loop ([me.rerere.rikkahub.data.ai.GenerationHandler]) may exit with a last
 *    ASSISTANT message that is pure tool_use: results are written into
 *    [UIMessagePart.Tool.output] in place, and [UIMessage.toText] ignores non-text parts, so a
 *    naive `toText()` on such a message returns blank even though useful text exists in a
 *    tool's output.
 *
 * Strategy (walk from the END):
 *  1. For the last ASSISTANT message, try its top-level [UIMessagePart.Text] parts (joined,
 *     blank-trimmed).
 *  2. If that is blank, fall back to the text inside that message's [UIMessagePart.Tool.output]
 *     (only [UIMessagePart.Text] outputs contribute; non-text outputs such as an Image are
 *     skipped).
 *  3. If still blank, keep walking back to the previous ASSISTANT message and repeat.
 *
 * Boundary: an all-tool / empty / text-less conversation yields the empty string.
 *
 * Metamorphic property this guarantees: appending a pure-tool_use ASSISTANT message after a
 * text-bearing one does not change the result (the fallback recovers the earlier text).
 */
fun extractFinalAssistantText(messages: List<UIMessage>): String {
    for (index in messages.indices.reversed()) {
        val message = messages[index]
        if (message.role != MessageRole.ASSISTANT) continue

        val topLevelText = message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString(separator = "\n") { it.text }
            .trim()
        if (topLevelText.isNotBlank()) return topLevelText

        val toolOutputText = message.parts
            .filterIsInstance<UIMessagePart.Tool>()
            .flatMap { it.output }
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString(separator = "\n") { it.text }
            .trim()
        if (toolOutputText.isNotBlank()) return toolOutputText
    }
    return ""
}
