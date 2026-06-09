package me.rerere.rikkahub.data.ai.memory

/**
 * Always-available fallback recaller: ranks an assistant's memories by recency (most-recently-updated
 * first) and returns the top-[k]. Used when no memory embedding model is configured/resolvable
 * (issue #210 §5 D1) — the feature works with zero embedder calls.
 *
 * [loadMemories] resolves the memory rows for an assistant scope (injected so this stays IO-light and
 * unit-testable; the production wiring loads from [me.rerere.rikkahub.data.repository.MemoryRepository]).
 */
class RecencyMemoryRecaller(
    private val loadMemories: suspend (assistantId: String) -> List<RecalledMemory>,
) : MemoryRecaller {
    override suspend fun recall(query: String, assistantId: String, k: Int): List<RecalledMemory> {
        if (k <= 0) return emptyList()
        return rankByRecency(loadMemories(assistantId), k)
    }

    companion object {
        /**
         * Pure recency ranking core (no IO) — extracted for headless unit testing. Most-recently
         * updated first, then take [k]. Ties on `updatedAt` are broken by descending id (the more
         * recently inserted row) so the order is deterministic.
         */
        fun rankByRecency(memories: List<RecalledMemory>, k: Int): List<RecalledMemory> {
            if (k <= 0) return emptyList()
            return memories
                .sortedWith(compareByDescending<RecalledMemory> { it.updatedAt }.thenByDescending { it.id })
                .take(k)
        }
    }
}
