package me.rerere.ai.runtime.subagent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.AssistantConfig
import me.rerere.ai.runtime.contract.TurnConfig
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * Pure (Android-free) primitives for the in-conversation subagent feature (issue #201).
 *
 * These live in `:ai-runtime` rather than `:ai` because they reference the neutral runtime
 * contracts [AssistantConfig] / [TurnConfig], which the lower `:ai` module cannot see. They are
 * deliberately side-effect-free so the load-bearing resolution / recursion-guard / extraction logic
 * is JVM-unit-testable without a Context, Provider, or running generation.
 */

/**
 * Resolve the model a subagent should run under.
 *
 * Resolution order (the load-bearing invariant):
 *   1. the sub-[AssistantConfig]'s own pinned model ([AssistantConfig.chatModelId]) if set,
 *   2. else the parent's model ([parentModelId]),
 *   3. else the global default ([TurnConfig.defaultModelId], a non-null [Uuid]).
 *
 * Inheriting the parent's model when the sub-Assistant pins none is the chosen v1 default
 * (cache/consistency): a sub-Assistant that wants the cheap path simply pins its own model.
 * Because [TurnConfig.defaultModelId] is non-null, the result is non-null whenever a global default
 * exists, so a caller can `error()` on a genuinely unresolvable id rather than on null here.
 */
fun resolveSubagentModel(
    sub: AssistantConfig,
    parentModelId: Uuid?,
    turn: TurnConfig,
): Uuid = sub.chatModelId ?: parentModelId ?: turn.defaultModelId

/**
 * Strip the spawn tool from a subagent's tool pool across ALL sources (MCP / skills / local).
 *
 * The reserved spawn-tool name is supplied by the caller ([spawnToolName]) rather than baked in,
 * so this neutral recursion-guard primitive names no concrete tool identity — the spawn tool's
 * name is an app-side concern (it is built only at the app spawn-tool site). A subagent must never
 * be able to spawn further subagents (recursion guard, depth bounded at 1), so this name is
 * filtered out of any tool pool handed to a subagent.
 *
 * Raw MCP tools are prefixed with the app-side MCP namespace prefix at the ChatService build site,
 * so a malicious MCP tool literally named like the spawn tool cannot collide with this reserved
 * name. Filtering by the exact caller-supplied name is therefore a sound structural guard.
 *
 * Invariant: a tool whose name == [spawnToolName] is NEVER present in the output, for any input
 * pool — this is the structural recursion guard. The output is always a subset of the input
 * (Conservation) and the function is idempotent.
 */
fun filterToolsForSubagent(tools: List<Tool>, spawnToolName: String): List<Tool> =
    tools.filterNot { it.name == spawnToolName }

/**
 * Extract the subagent's final answer text from its terminal message list.
 *
 * Why this is not just `messages.last().toText()`:
 *  - The agentic loop may exit with a last ASSISTANT message that is pure tool_use: results are
 *    written into [UIMessagePart.Tool.output] in place, and [UIMessage.toText] ignores non-text
 *    parts, so a naive `toText()` on such a message returns blank even though useful text exists in
 *    a tool's output.
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
