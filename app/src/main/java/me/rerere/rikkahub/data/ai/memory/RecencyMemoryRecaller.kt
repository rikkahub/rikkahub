package me.rerere.rikkahub.data.ai.memory

import me.rerere.ai.runtime.contract.RecalledMemory
import me.rerere.ai.runtime.memory.rankByRecency

/**
 * Always-available fallback recaller: ranks an assistant's memories by recency (most-recently-updated
 * first) and returns the top-[k]. Used when no memory embedding model is configured/resolvable
 * (issue #210 §5 D1) — the feature works with zero embedder calls.
 *
 * [loadMemories] resolves the memory rows for an assistant scope (injected so this stays IO-light and
 * unit-testable; the production wiring loads from [me.rerere.rikkahub.data.repository.MemoryRepository]).
 *
 * The pure recency ranking core lives in `:ai-runtime` ([rankByRecency]); this class only adds the
 * IO/scope wiring koog/Room-free callers cannot.
 */
class RecencyMemoryRecaller(
    private val loadMemories: suspend (assistantId: String) -> List<RecalledMemory>,
) : MemoryRecaller {
    override suspend fun recall(query: String, assistantId: String, k: Int): List<RecalledMemory> {
        if (k <= 0) return emptyList()
        return rankByRecency(loadMemories(assistantId), k)
    }
}
