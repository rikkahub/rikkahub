package me.rerere.rikkahub.data.ai.memory

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.MemoryRepository

/**
 * The gated decision the ChatService recall site computes (issue #210 §6/§7): WHICH memory scope to
 * recall against and WHICH query text to rank by, or `null` when recall must not run at all.
 */
data class MemoryRecallScope(val query: String, val assistantId: String)

/**
 * Resolves the memory recall scope for a turn, or `null` if recall must be skipped.
 *
 * Skips (returns `null`) when:
 *  - [Assistant.enableMemory] is false — recall must not run when memory is off (consistency with the
 *    write-tool gate and subagent isolation; the historic full-dump fetch ran unconditionally),
 *  - there is no user message to rank against, or its text is blank — no query, nothing to recall.
 *
 * Otherwise the scope is the global memory set when [Assistant.useGlobalMemory] (global-only recall,
 * §13 Q3) else the assistant's own id, and the query is the last user message's text (same idiom as
 * `KnowledgeContextTransformer`). Pure (no IO) so the gate is unit-testable without the full
 * ChatService.
 */
fun resolveMemoryRecallScope(assistant: Assistant, messages: List<UIMessage>): MemoryRecallScope? {
    if (!assistant.enableMemory) return null

    val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
    if (lastUserIndex < 0) return null
    val query = messages[lastUserIndex].parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
    if (query.isEmpty()) return null

    val assistantId = if (assistant.useGlobalMemory) {
        MemoryRepository.GLOBAL_MEMORY_ID
    } else {
        assistant.id.toString()
    }
    return MemoryRecallScope(query = query, assistantId = assistantId)
}
