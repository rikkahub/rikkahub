package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.estimateTokens
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.knowledge.KnowledgeContextBlock
import me.rerere.rikkahub.data.ai.knowledge.KnowledgeContextRenderer
import me.rerere.rikkahub.data.ai.knowledge.KnowledgeScope
import me.rerere.rikkahub.data.ai.knowledge.KnowledgeSource
import me.rerere.rikkahub.data.ai.memory.RecalledMemory
import me.rerere.rikkahub.data.ai.memory.memoryAgeLabel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate

// MEMORY is the only source on the system-prompt surface (recent-chats is not routed and there is no
// 4th source), so a single priority is sufficient; the assembler still orders by (priority desc,
// source ordinal). A value > the attachment/RAG priorities is harmless because those never reach this
// surface — it only documents "memory wins" if a future phase routes another system-surface source.
private const val MEMORY_PRIORITY = 100

// Emits the relevance-recalled subset (issue #210) as budgetable knowledge blocks (issue #141 Phase 2)
// — ONE block per recalled memory so the assembler can bound them against the system-prompt surface
// and the renderer can source-label each as `<memory>` (invariant 4: memory must not masquerade as
// document evidence). Each block's content is the SAME single-object `{id, content, age}` the old
// per-row dump produced, so the memory_tool can still edit/delete by id and a stale preference still
// reads as stale (`age` from updatedAt). [nowMs] is injected so the age render is deterministic in
// tests. Returns emptyList for no memories — the caller only invokes this under `enableMemory`, so an
// empty result is the memory-off / no-recall case (no block appended).
internal fun buildMemoryPrompt(
    memories: List<RecalledMemory>,
    scope: KnowledgeScope,
    nowMs: Long = System.currentTimeMillis(),
): List<KnowledgeContextBlock> =
    memories.map { memory ->
        val content = JsonInstantPretty.encodeToString(
            buildJsonObject {
                put("id", memory.id)
                put("content", memory.content)
                put("age", memoryAgeLabel(memory.updatedAt, nowMs))
            }
        )
        val block = KnowledgeContextBlock(
            source = KnowledgeSource.MEMORY,
            scope = scope,
            title = null,
            content = content,
            priority = MEMORY_PRIORITY,
            estimatedTokens = 0,
        )
        // Count the RENDERED <memory> block (not the bare JSON), matching how Phase 1 counts the
        // wrapped ragBlock/attachment over their own render() — the per-block separator the system
        // buildString adds is framing overhead uniformly uncounted across ALL sources (counting it for
        // MEMORY alone would be inconsistent with RAG/attachment; it is ~1 token, negligible vs budget).
        block.copy(
            estimatedTokens = estimateTokens(
                listOf(UIMessagePart.Text(KnowledgeContextRenderer.render(block)))
            )
        )
    }

internal suspend fun buildRecentChatsPrompt(
    assistant: Assistant,
    conversationRepo: ConversationRepository
): String {
    val recentConversations = conversationRepo.getRecentConversations(
        assistantId = assistant.id,
        limit = 10,
    )
    if (recentConversations.isNotEmpty()) {
        return buildString {
            appendLine()
            append("**Recent Chats**")
            appendLine()
            append("These are some of the user's recent conversations. You can use them to understand user preferences:")
            appendLine()
            val json = buildJsonArray {
                recentConversations.forEach { conversation ->
                    add(buildJsonObject {
                        put("title", conversation.title)
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            append(JsonInstantPretty.encodeToString(json))
            appendLine()
        }
    }
    return ""
}
