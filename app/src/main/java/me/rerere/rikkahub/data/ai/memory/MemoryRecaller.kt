package me.rerere.rikkahub.data.ai.memory

/**
 * DIP seam for memory relevance recall (issue #210). Replaces the historic "dump every memory into
 * every prompt" path: instead of returning all of an assistant's memories, an implementation returns
 * the top-[k] memories most relevant to [query].
 *
 * Two concrete implementations are injected at the composition root:
 *  - [EmbeddingMemoryRecaller] — cosine over per-memory vectors when an embedding model is configured,
 *  - [RecencyMemoryRecaller] — most-recently-updated top-k fallback when no usable embedding exists.
 *
 * High-level callers (ChatService) depend only on this interface and the constants below.
 */
interface MemoryRecaller {
    /**
     * @param query text of the current turn to rank memories against (the last user message).
     * @param assistantId memory scope — the assistant's id, or [GLOBAL_MEMORY_ID] when global memory
     *   is enabled.
     * @param k maximum number of memories to return.
     * @return the recalled memories (size <= [k]); ids are always a subset of the store. An empty
     *   store ⇒ empty list with no embedder call.
     */
    suspend fun recall(query: String, assistantId: String, k: Int): List<RecalledMemory>
}

/** Default candidate count fed into the prompt (§13 Q1). */
const val MEMORY_RECALL_K = 5

/**
 * Relevance floor for cosine-selected memories (§13 Q2). Same value as
 * [me.rerere.rikkahub.data.rag.KnowledgeBase.DEFAULT_MIN_SCORE] today, but a SEPARATE constant so the
 * two can diverge later (memories are shorter than RAG chunks ⇒ a different similarity distribution).
 */
const val MEMORY_MIN_SCORE = 0.5
