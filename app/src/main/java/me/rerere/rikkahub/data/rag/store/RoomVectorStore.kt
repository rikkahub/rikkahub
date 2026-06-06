package me.rerere.rikkahub.data.rag.store

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import ai.koog.rag.base.storage.LookupStorage
import ai.koog.rag.base.storage.SearchStorage
import ai.koog.rag.base.storage.WriteStorage
import ai.koog.rag.base.storage.search.Score
import ai.koog.rag.base.storage.search.ScoreMetric
import ai.koog.rag.base.storage.search.SearchResult
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.KnowledgeChunkDAO
import me.rerere.rikkahub.data.db.entity.KnowledgeChunkEntity

/**
 * Koog [SearchStorage] implementing cosine-similarity vector search over Room-persisted embeddings.
 *
 * Documents are embedded via [embedder] on write and persisted as primitive columns. Queries embed
 * the query text, load the namespace's rows into memory, and rank by cosine similarity (linear scan
 * — fine for the small per-assistant knowledge bases this slice targets; no ANN/FTS index).
 *
 * Namespace == knowledge-base id ([defaultNamespace] when a request omits it).
 *
 * @property embeddingModelLabel stored on each row so a later embedding-model change is detectable.
 */
class RoomVectorStore(
    private val dao: KnowledgeChunkDAO,
    private val embedder: Embedder,
    private val defaultNamespace: String,
    private val embeddingModelLabel: String,
) : SearchStorage<Chunk, SimilaritySearchRequest>,
    WriteStorage<Chunk>,
    LookupStorage<Chunk> {

    override suspend fun add(documents: List<Chunk>, namespace: String?): List<String> {
        if (documents.isEmpty()) return emptyList()
        val kbId = namespace ?: defaultNamespace
        return withContext(Dispatchers.IO) {
            val rows = documents.map { chunk ->
                val vector = embedder.embed(chunk.content)
                KnowledgeChunkEntity(
                    id = chunk.chunkId,
                    kbId = kbId,
                    docId = chunk.docId,
                    sourceRef = chunk.sourceRef,
                    chunkIndex = chunk.chunkIndex,
                    text = chunk.content,
                    embedding = encodeVector(vector),
                    embeddingModel = embeddingModelLabel,
                )
            }
            dao.insertAll(rows)
            rows.map { it.id }
        }
    }

    override suspend fun update(documents: Map<String, Chunk>, namespace: String?): List<String> {
        // REPLACE-on-conflict insert is an upsert, so update is structurally identical to add.
        return add(documents.values.toList(), namespace)
    }

    /** Removes every chunk of [docId] in this store's namespace. Used to roll back a partial ingest. */
    suspend fun deleteDocument(docId: String, namespace: String? = null) {
        val kbId = namespace ?: defaultNamespace
        withContext(Dispatchers.IO) {
            dao.deleteByDoc(kbId, docId)
        }
    }

    override suspend fun get(ids: List<String>, namespace: String?): List<Chunk> {
        if (ids.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            dao.getByIds(ids).map { it.toChunk() }
        }
    }

    override suspend fun search(
        request: SimilaritySearchRequest,
        namespace: String?,
    ): List<SearchResult<Chunk>> {
        val kbId = namespace ?: defaultNamespace
        return withContext(Dispatchers.IO) {
            val query = embedder.embed(request.queryText)
            // Cosine ranking is only meaningful between vectors from the same embedding space. Rows
            // embedded under a different model (dimension may even coincide, e.g. 1536) would be
            // silently mis-ranked, so the embedding-model predicate is pushed into SQL — foreign-model
            // rows are never loaded or decoded, and the rows stay in Room.
            val rows = dao.getByKbAndModel(kbId, embeddingModelLabel)
                .map { it.toChunk() to decodeVector(it.embedding) }
            rankBySimilarity(query, rows, request, kbId)
        }
    }

    /**
     * Manifest-scoped retrieval. Identical to [search] but additionally drops any stored chunk whose
     * [Chunk.docId] is absent from [allowedDocIds].
     *
     * Why this exists: chunk rows (Room) and the document manifest ([KnowledgeBase.documents] in the
     * Settings DataStore) are written across two stores that cannot share a transaction, so a process
     * death in the gap between "chunks committed" and "manifest entry committed" leaves orphan rows
     * whose document the user can no longer see or delete. [search] ranks purely by namespace and
     * would keep serving those orphans forever. The manifest is the source of truth for which
     * documents exist, so retrieval reconciles against it: [allowedDocIds] is required (not nullable)
     * precisely so the real retrieval path cannot forget the invariant.
     */
    suspend fun searchInDocuments(
        request: SimilaritySearchRequest,
        namespace: String?,
        allowedDocIds: Set<String>,
    ): List<SearchResult<Chunk>> {
        val kbId = namespace ?: defaultNamespace
        return withContext(Dispatchers.IO) {
            val query = embedder.embed(request.queryText)
            // Same embedding-space invariant as [search], enforced in SQL: rows embedded under another
            // model are never loaded.
            val rows = dao.getByKbAndModel(kbId, embeddingModelLabel)
                .map { it.toChunk() to decodeVector(it.embedding) }
            rankBySimilarity(query, rows, request, kbId, allowedDocIds)
        }
    }

    companion object {
        /**
         * Pure ranking core (no IO) — extracted for headless unit testing. Computes cosine
         * similarity of [query] against each stored vector, applies [SimilaritySearchRequest]'s
         * minScore/offset/limit, and returns results nearest-first.
         */
        fun rankBySimilarity(
            query: Vector,
            rows: List<Pair<Chunk, Vector>>,
            request: SimilaritySearchRequest,
            namespace: String? = null,
            allowedDocIds: Set<String>? = null,
        ): List<SearchResult<Chunk>> {
            // Scored, manifest- and minScore-filtered candidates, each tagged with its input index so
            // ties can be broken by original order (matching the stable sortedByDescending this used to
            // be). The index is the rank key, NOT the row's identity, so it is assigned over the
            // filtered sequence in encounter order.
            val candidates = ArrayList<Scored>()
            var index = 0
            rows.forEach { (chunk, vector) ->
                // null = allow all (used by non-manifest callers/tests); a set enforces the manifest
                // invariant by hiding orphan chunks whose document no longer exists.
                if (allowedDocIds != null && !allowedDocIds.contains(chunk.docId)) return@forEach
                val score = query.cosineSimilarity(vector)
                // koog's cosineSimilarity can emit NaN/±Infinity for extreme-magnitude vectors
                // (overflow -> Inf/Inf = NaN; subnormal underflow -> non-zero dot / 0 magnitude =
                // Inf). A non-finite score corrupts top-k: Double.compareTo orders NaN above every
                // real number, so a descending sort would put it first, and +Infinity also slips past
                // the minScore `>=` filter. Drop such rows — the chunk row stays in Room untouched.
                if (!score.isFinite()) return@forEach
                // Mirror the old `.filter { score >= minScore }` exactly, including its NaN edge:
                // a NaN minScore makes `score >= minScore` false for every row, so all rows are
                // dropped. Writing the negated form `score < minScore` would instead KEEP every row
                // (`score < NaN` is always false), flipping reject-all to keep-all and breaking the
                // stated behavior-preserving contract.
                if (request.minScore?.let { !(score >= it) } == true) return@forEach
                candidates.add(Scored(chunk, score, index++))
            }

            // Bounded top-k selection. We only need the first offset+limit of the descending order, so
            // keep a min-heap of size k (worst-of-the-best at the head) and evict the worst once full:
            // O(n log k) instead of the old O(n log n) full sort. The result is byte-for-byte identical
            // to sortedByDescending(score).drop(offset).take(limit) because the heap orders by the same
            // (score DESC, input-index ASC) key the stable sort produced.
            val k = request.offset + request.limit
            if (k <= 0) return emptyList()

            // Heap comparator is the INVERSE of the output order so poll() yields the worst candidate.
            val worstFirst = Comparator<Scored> { a, b ->
                val byScore = a.score.compareTo(b.score)          // ascending score: smallest at head
                if (byScore != 0) byScore else b.index.compareTo(a.index) // larger index = "worse" tie
            }
            val heap = java.util.PriorityQueue(worstFirst)
            candidates.forEach { c ->
                if (heap.size < k) {
                    heap.add(c)
                } else if (worstFirst.compare(c, heap.peek()) > 0) {
                    heap.poll()
                    heap.add(c)
                }
            }

            val bestFirst = Comparator<Scored> { a, b ->
                val byScore = b.score.compareTo(a.score)          // descending score
                if (byScore != 0) byScore else a.index.compareTo(b.index) // smaller index wins ties
            }
            return heap.sortedWith(bestFirst)
                .drop(request.offset)
                .take(request.limit)
                .map { scored ->
                    SearchResult(
                        document = scored.chunk,
                        score = Score(value = scored.score, metric = ScoreMetric.COSINE_SIMILARITY),
                        id = scored.chunk.chunkId,
                        namespace = namespace,
                    )
                }
        }

        private class Scored(val chunk: Chunk, val score: Double, val index: Int)

        fun encodeVector(vector: Vector): String =
            vector.values.joinToString(",")

        fun decodeVector(encoded: String): Vector {
            if (encoded.isEmpty()) return Vector(emptyList())
            return Vector(encoded.split(",").map { it.toDouble() })
        }
    }
}

private fun KnowledgeChunkEntity.toChunk(): Chunk = Chunk(
    chunkId = id,
    docId = docId,
    sourceRef = sourceRef,
    chunkIndex = chunkIndex,
    content = text,
)
