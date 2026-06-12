package me.rerere.rikkahub.data.ai.memory

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.runtime.contract.RecalledMemory
import me.rerere.ai.runtime.memory.MEMORY_MIN_SCORE
import me.rerere.ai.runtime.memory.memoryContentHash
import me.rerere.ai.runtime.memory.rankByRecency

/**
 * Default recaller (issue #210 §5 D1, recommendation C): embeds the current turn ONCE and ranks an
 * assistant's memories by cosine similarity over their stored per-memory vectors, keeping the top-k
 * above [MEMORY_MIN_SCORE]. Memories with no usable vector — never embedded, content edited since
 * embedding (hash mismatch), embedded in a different space (model/endpoint changed), or a dimension
 * mismatch — fall back to recency to fill any remaining slots, then are re-embedded lazily on write.
 *
 * Reuses the existing embedding infra ([Embedder]/cosine) rather than an extra LLM generation. The
 * embedder + current embedding-space label are resolved per [recall] from CURRENT settings via
 * [resolveContext] (like `KnowledgeStoreFactory.buildStore` returning null), so adding/changing/
 * deleting the model self-heals: an unresolvable model ⇒ delegate to [fallback] (recency).
 *
 * The ranking algorithm itself is the pure, IO-free [rankRecall] core so the properties run headless
 * with a mocked embedder.
 *
 * @property loadCandidates loads a scope's memories together with their stored vector row (decoded
 *   [Vector] + the row's recorded freshness keys), or `null` vector when un-embedded.
 * @property resolveContext resolves the embedder + current embedding-space label from current
 *   settings, or `null` when no OpenAI-compatible embedding model is configured/resolvable.
 * @property fallback recency recaller used when there is no usable embedding context.
 */
class EmbeddingMemoryRecaller(
    private val loadCandidates: suspend (assistantId: String) -> List<MemoryCandidate>,
    private val resolveContext: () -> EmbeddingContext?,
    private val fallback: MemoryRecaller,
) : MemoryRecaller {

    /** Embedder + the current embedding-space label its vectors live in. */
    class EmbeddingContext(val embedder: Embedder, val embeddingSpace: String)

    /** A memory plus its stored vector (decoded) and the row's recorded freshness keys, if embedded. */
    data class MemoryCandidate(
        val memory: RecalledMemory,
        val storedVector: Vector?,
        val storedContentHash: String?,
        val storedEmbeddingSpace: String?,
    )

    override suspend fun recall(query: String, assistantId: String, k: Int): List<RecalledMemory> {
        if (k <= 0) return emptyList()
        val candidates = loadCandidates(assistantId)
        // Empty store: no embedder call (the no-op gate idiom), and nothing to rank.
        if (candidates.isEmpty()) return emptyList()

        // No usable embedding model ⇒ recency fallback (still zero embedder calls here).
        val context = resolveContext() ?: return fallback.recall(query, assistantId, k)

        val currentSpace = context.embeddingSpace
        val currentHashByMemory = candidates.associate { it.memory.id to memoryContentHash(it.memory.content) }
        val queryVector = withContext(Dispatchers.IO) { context.embedder.embed(query) }

        return rankRecall(
            query = queryVector,
            candidates = candidates,
            currentSpace = currentSpace,
            currentContentHash = { id -> currentHashByMemory.getValue(id) },
            k = k,
            minScore = MEMORY_MIN_SCORE,
        )
    }

    companion object {
        /**
         * Pure ranking core (no IO). Returns the top-[k] memories: cosine-ranked usable vectors above
         * [minScore] first, then recency fills the remaining slots from memories WITHOUT a usable
         * vector.
         *
         * A stored vector is USABLE iff all three freshness keys hold:
         *  1. [MemoryCandidate.storedContentHash] == the memory's current content hash
         *     ([currentContentHash]) — content not edited since embedding,
         *  2. [MemoryCandidate.storedEmbeddingSpace] == [currentSpace] — same model/endpoint,
         *  3. vector dimension == [query] dimension — explicit guard BEFORE cosine so a mismatch never
         *     reaches `cosineSimilarity` (it would crash), the row is simply treated as un-embedded.
         *
         * Usable vectors scoring below [minScore] are DROPPED (not demoted to recency) so that adding
         * an irrelevant memory cannot perturb the result (metamorphic stability). Only memories with
         * no usable vector are eligible for the recency tail. Result ids are always a subset of the
         * candidates.
         */
        fun rankRecall(
            query: Vector,
            candidates: List<MemoryCandidate>,
            currentSpace: String,
            currentContentHash: (id: Int) -> String,
            k: Int,
            minScore: Double,
        ): List<RecalledMemory> {
            if (k <= 0 || candidates.isEmpty()) return emptyList()

            val cosineSelected = ArrayList<Pair<RecalledMemory, Double>>()
            val unembedded = ArrayList<RecalledMemory>()

            candidates.forEach { candidate ->
                val vector = candidate.storedVector
                if (vector == null) {
                    unembedded.add(candidate.memory)
                    return@forEach
                }
                val usable = candidate.storedContentHash == currentContentHash(candidate.memory.id) &&
                    candidate.storedEmbeddingSpace == currentSpace &&
                    vector.values.size == query.values.size
                if (!usable) {
                    unembedded.add(candidate.memory)
                    return@forEach
                }
                val score = query.cosineSimilarity(vector)
                // koog's cosineSimilarity can emit NaN/±Inf for extreme-magnitude vectors; a non-finite
                // score corrupts top-k (NaN sorts above every real number, +Inf slips past a `>=`
                // floor). Drop such rows — never let them rank. (Same guard as RoomVectorStore.)
                if (!score.isFinite()) return@forEach
                // Relevance floor: below minScore the memory is irrelevant to this turn and is dropped
                // (NOT demoted to the recency tail), so an irrelevant memory cannot displace a tail row.
                if (score < minScore) return@forEach
                cosineSelected.add(candidate.memory to score)
            }

            val ranked = cosineSelected
                // Score DESC; ties broken by updatedAt DESC then id DESC for a deterministic order.
                .sortedWith(
                    compareByDescending<Pair<RecalledMemory, Double>> { it.second }
                        .thenByDescending { it.first.updatedAt }
                        .thenByDescending { it.first.id }
                )
                .map { it.first }

            if (ranked.size >= k) return ranked.take(k)

            val recencyTail = rankByRecency(unembedded, k - ranked.size)
            return ranked + recencyTail
        }
    }
}
