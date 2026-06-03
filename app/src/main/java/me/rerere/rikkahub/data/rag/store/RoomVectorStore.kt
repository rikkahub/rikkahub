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
            val rows = dao.getByKb(kbId)
                // Cosine ranking is only meaningful between vectors from the same embedding space.
                // Rows embedded under a different model (dimension may even coincide, e.g. 1536) would
                // be silently mis-ranked, so exclude them from this query — the rows stay in Room.
                .filter { it.embeddingModel == embeddingModelLabel }
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
            val rows = dao.getByKb(kbId)
                // Same embedding-space invariant as [search]: drop rows embedded under another model.
                .filter { it.embeddingModel == embeddingModelLabel }
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
            return rows.asSequence()
                // null = allow all (used by non-manifest callers/tests); a set enforces the manifest
                // invariant by hiding orphan chunks whose document no longer exists.
                .filter { (chunk, _) -> allowedDocIds?.contains(chunk.docId) ?: true }
                .map { (chunk, vector) -> chunk to query.cosineSimilarity(vector) }
                // koog's cosineSimilarity can emit NaN/±Infinity for extreme-magnitude vectors
                // (overflow -> Inf/Inf = NaN; subnormal underflow -> non-zero dot / 0 magnitude =
                // Inf). A non-finite score corrupts top-k: Double.compareTo orders NaN above every
                // real number, so sortedByDescending would sort it to the front, and +Infinity also
                // slips past the minScore `>=` filter. Drop such rows from this query's ranking —
                // the chunk row stays in Room untouched, only this uncomputable similarity is hidden.
                .filter { (_, score) -> score.isFinite() }
                .filter { (_, score) -> request.minScore?.let { score >= it } ?: true }
                .sortedByDescending { (_, score) -> score }
                .drop(request.offset)
                .take(request.limit)
                .map { (chunk, score) ->
                    SearchResult(
                        document = chunk,
                        score = Score(value = score, metric = ScoreMetric.COSINE_SIMILARITY),
                        id = chunk.chunkId,
                        namespace = namespace,
                    )
                }
                .toList()
        }

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
