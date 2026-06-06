package me.rerere.rikkahub.data.rag

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import ai.koog.rag.base.storage.search.ScoreMetric
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.rag.KnowledgeBase
import me.rerere.rikkahub.data.db.dao.KnowledgeChunkDAO
import me.rerere.rikkahub.data.db.entity.KnowledgeChunkEntity
import me.rerere.rikkahub.data.rag.store.Chunk
import me.rerere.rikkahub.data.rag.store.RoomVectorStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end (headless) test of [RoomVectorStore.add] -> [RoomVectorStore.search] over an in-memory
 * fake DAO and a deterministic fake embedder, proving the embed -> persist -> cosine top-k path and
 * that List<Float>/List<Double> mapping survives the round trip.
 */
class RoomVectorStoreSearchTest {

    /** Fake embedder: maps known phrases to fixed 2D vectors so similarity is predictable. */
    private class FakeEmbedder : Embedder {
        override suspend fun embed(text: String): Vector = when {
            text.contains("cat") -> Vector(listOf(1.0, 0.0))
            text.contains("dog") -> Vector(listOf(0.9, 0.1))
            text.contains("car") -> Vector(listOf(0.0, 1.0))
            else -> Vector(listOf(0.5, 0.5))
        }

        override fun diff(embedding1: Vector, embedding2: Vector): Double =
            1.0 - embedding1.cosineSimilarity(embedding2)
    }

    private class FakeDao : KnowledgeChunkDAO {
        val rows = mutableListOf<KnowledgeChunkEntity>()
        // Records which read path search() takes, so a regression test can pin the SQL-filtered seam
        // (getByKbAndModel) instead of the old load-all-then-Kotlin-filter (getByKb).
        var getByKbCalls = 0
        var getByKbAndModelCalls = 0
        override suspend fun insertAll(chunks: List<KnowledgeChunkEntity>) {
            chunks.forEach { c -> rows.removeAll { it.id == c.id }; rows.add(c) }
        }
        override suspend fun getByKb(kbId: String): List<KnowledgeChunkEntity> {
            getByKbCalls++
            return rows.filter { it.kbId == kbId }
        }
        override suspend fun getByKbAndModel(kbId: String, embeddingModel: String): List<KnowledgeChunkEntity> {
            getByKbAndModelCalls++
            return rows.filter { it.kbId == kbId && it.embeddingModel == embeddingModel }
        }
        override suspend fun getByIds(ids: List<String>): List<KnowledgeChunkEntity> =
            rows.filter { it.id in ids }
        override suspend fun countByKb(kbId: String): Int = rows.count { it.kbId == kbId }
        override suspend fun deleteByKb(kbId: String) { rows.removeAll { it.kbId == kbId } }
        override suspend fun deleteByDoc(kbId: String, docId: String) {
            rows.removeAll { it.kbId == kbId && it.docId == docId }
        }
    }

    private fun chunk(id: String, content: String, docId: String = "doc") =
        Chunk(chunkId = id, docId = docId, sourceRef = "src", chunkIndex = 0, content = content)

    @Test
    fun `add then search returns the most similar chunk first`() = runBlocking {
        val dao = FakeDao()
        val store = RoomVectorStore(
            dao = dao,
            embedder = FakeEmbedder(),
            defaultNamespace = "kb1",
            embeddingModelLabel = "fake-embed",
        )

        store.add(
            listOf(
                chunk("c1", "a cat sat"),
                chunk("c2", "a dog ran"),
                chunk("c3", "a car drove"),
            )
        )
        assertEquals(3, dao.countByKb("kb1"))

        val results = store.search(SimilaritySearchRequest(queryText = "the cat", limit = 2))
        assertEquals(2, results.size)
        assertEquals("c1", results[0].document.chunkId)   // cat == cat, cosine 1
        assertEquals("c2", results[1].document.chunkId)   // dog close to cat
        assertEquals(ScoreMetric.COSINE_SIMILARITY, results[0].score.metric)
        assertTrue(results[0].score.value >= results[1].score.value)
    }

    @Test
    fun `relevance floor drops chunks the query is near-orthogonal to`() = runBlocking {
        val dao = FakeDao()
        val store = RoomVectorStore(dao, FakeEmbedder(), "kb1", "fake-embed")

        // Only chunks that are near-orthogonal to the query "car" ([0,1]).
        store.add(
            listOf(
                chunk("c1", "a cat sat"),   // [1,0] cosine ~0 to [0,1]
                chunk("c2", "a dog ran"),   // [0.9,0.1] cosine ~0.11 to [0,1]
            )
        )

        // The transformer now floors the request with KnowledgeBase.DEFAULT_MIN_SCORE.
        // With no relevant chunk, the floored search returns nothing, so the transformer's
        // results.isEmpty() gate fires and no <knowledge_base_context> block is injected.
        val results = store.search(
            SimilaritySearchRequest(
                queryText = "the car",
                limit = 4,
                minScore = KnowledgeBase.DEFAULT_MIN_SCORE,
            )
        )
        assertTrue(
            "irrelevant chunks must be filtered out by the relevance floor, got ${results.size}",
            results.isEmpty(),
        )
    }

    @Test
    fun `relevance floor keeps genuinely relevant chunks`() = runBlocking {
        val dao = FakeDao()
        val store = RoomVectorStore(dao, FakeEmbedder(), "kb1", "fake-embed")
        store.add(
            listOf(
                chunk("c1", "a cat sat"),    // [1,0] cosine ~0 to "car"
                chunk("c3", "a car drove"),  // [0,1] cosine 1 to "car"
            )
        )

        val results = store.search(
            SimilaritySearchRequest(
                queryText = "the car",
                limit = 4,
                minScore = KnowledgeBase.DEFAULT_MIN_SCORE,
            )
        )
        assertEquals(1, results.size)
        assertEquals("c3", results[0].document.chunkId)
    }

    @Test
    fun `searchInDocuments hides chunks whose doc is absent from the manifest`() = runBlocking {
        val dao = FakeDao()
        val store = RoomVectorStore(dao, FakeEmbedder(), "kb1", "fake-embed")

        // An orphan chunk: its docId is NOT in the KB manifest (e.g. left behind by a partial
        // ingest where the chunk row committed but the Settings manifest entry never did). It is a
        // perfect cosine match for the query, so without manifest enforcement it ranks first.
        store.add(listOf(chunk("orphan", "a cat sat", docId = "orphan-doc")))
        // A valid chunk whose docId IS in the manifest, also a perfect match.
        store.add(listOf(chunk("valid", "a cat sat", docId = "valid-doc")))

        val results = store.searchInDocuments(
            request = SimilaritySearchRequest(queryText = "the cat", limit = 10),
            namespace = "kb1",
            allowedDocIds = setOf("valid-doc"),
        )

        val ids = results.map { it.document.chunkId }
        assertTrue("orphan chunk must be hidden from retrieval, got $ids", "orphan" !in ids)
        assertTrue("valid in-manifest chunk must be retrievable, got $ids", "valid" in ids)
    }

    @Test
    fun `rankBySimilarity drops rows whose docId is outside the allowed manifest set`() {
        val query = Vector(listOf(1.0, 0.0))
        val rows = listOf(
            chunk("orphan", "x", docId = "orphan-doc") to Vector(listOf(1.0, 0.0)),
            chunk("valid", "y", docId = "valid-doc") to Vector(listOf(1.0, 0.0)),
        )
        val results = RoomVectorStore.rankBySimilarity(
            query = query,
            rows = rows,
            request = SimilaritySearchRequest(queryText = "", limit = 10),
            allowedDocIds = setOf("valid-doc"),
        )
        val ids = results.map { it.document.chunkId }
        assertEquals(listOf("valid"), ids)
    }

    /** Seeds a row directly so its embedding_model label can differ from the store's current model. */
    private fun seedRow(
        dao: FakeDao,
        id: String,
        vector: Vector,
        embeddingModel: String,
        kbId: String = "kb1",
        docId: String = "doc",
    ) = dao.rows.add(
        KnowledgeChunkEntity(
            id = id,
            kbId = kbId,
            docId = docId,
            sourceRef = "src",
            chunkIndex = 0,
            text = "x",
            embedding = RoomVectorStore.encodeVector(vector),
            embeddingModel = embeddingModel,
        )
    )

    @Test
    fun `search excludes chunks embedded under a different model`() = runBlocking {
        val dao = FakeDao()
        val store = RoomVectorStore(dao, FakeEmbedder(), "kb1", embeddingModelLabel = "fake-embed")

        // Both rows are a perfect cosine match for the query "the cat" -> [1,0]; the only thing that
        // distinguishes them is the embedding-model label. The same-model row must rank; the
        // foreign-model row (vector lives in a different embedding space, comparison is garbage)
        // must never appear, even though its cosine to the current query happens to be 1.0.
        seedRow(dao, id = "same", vector = Vector(listOf(1.0, 0.0)), embeddingModel = "fake-embed")
        seedRow(dao, id = "foreign", vector = Vector(listOf(1.0, 0.0)), embeddingModel = "other-embed")

        val results = store.search(SimilaritySearchRequest(queryText = "the cat", limit = 10))
        val ids = results.map { it.document.chunkId }
        assertTrue("foreign-model chunk must be excluded, got $ids", "foreign" !in ids)
        assertEquals(listOf("same"), ids)
    }

    @Test
    fun `search loads only same-model rows via the SQL-filtered query`() = runBlocking {
        val dao = FakeDao()
        val store = RoomVectorStore(dao, FakeEmbedder(), "kb1", embeddingModelLabel = "fake-embed")

        // One same-model row and one foreign-model row, both perfect cosine matches. The fix pushes
        // the embedding-model predicate into SQL, so search() must go through getByKbAndModel and
        // never load the whole KB via getByKb. On the unfixed code search() calls getByKb(...) then
        // filters in Kotlin, so getByKbCalls == 1 / getByKbAndModelCalls == 0 -> this fails.
        seedRow(dao, id = "same", vector = Vector(listOf(1.0, 0.0)), embeddingModel = "fake-embed")
        seedRow(dao, id = "foreign", vector = Vector(listOf(1.0, 0.0)), embeddingModel = "other-embed")

        val results = store.search(SimilaritySearchRequest(queryText = "the cat", limit = 10))

        assertEquals(listOf("same"), results.map { it.document.chunkId })
        assertTrue(
            "search must use the SQL-filtered getByKbAndModel path, calls=${dao.getByKbAndModelCalls}",
            dao.getByKbAndModelCalls >= 1,
        )
        assertEquals(
            "search must not load the whole KB via getByKb (foreign-model rows never decoded)",
            0,
            dao.getByKbCalls,
        )
    }

    @Test
    fun `searchInDocuments loads only same-model rows via the SQL-filtered query`() = runBlocking {
        val dao = FakeDao()
        val store = RoomVectorStore(dao, FakeEmbedder(), "kb1", embeddingModelLabel = "fake-embed")

        seedRow(dao, id = "same", vector = Vector(listOf(1.0, 0.0)), embeddingModel = "fake-embed", docId = "valid-doc")
        seedRow(dao, id = "foreign", vector = Vector(listOf(1.0, 0.0)), embeddingModel = "other-embed", docId = "valid-doc")

        val results = store.searchInDocuments(
            request = SimilaritySearchRequest(queryText = "the cat", limit = 10),
            namespace = "kb1",
            allowedDocIds = setOf("valid-doc"),
        )

        assertEquals(listOf("same"), results.map { it.document.chunkId })
        assertTrue(dao.getByKbAndModelCalls >= 1)
        assertEquals(0, dao.getByKbCalls)
    }

    @Test
    fun `search over a KB embedded entirely under a foreign model returns nothing`() = runBlocking {
        val dao = FakeDao()
        val store = RoomVectorStore(dao, FakeEmbedder(), "kb1", embeddingModelLabel = "fake-embed")

        // Every row was embedded under a model the store no longer uses. Comparing the current query
        // vector against vectors from a foreign space is meaningless, so retrieval must yield nothing
        // (the transformer's results.isEmpty() gate then injects no context).
        seedRow(dao, id = "f1", vector = Vector(listOf(1.0, 0.0)), embeddingModel = "other-embed")
        seedRow(dao, id = "f2", vector = Vector(listOf(0.9, 0.1)), embeddingModel = "other-embed")

        val results = store.search(SimilaritySearchRequest(queryText = "the cat", limit = 10))
        assertTrue("foreign-model KB must return no results, got ${results.size}", results.isEmpty())
    }

    @Test
    fun `search excludes rows embedded under the same model but a different endpoint`() = runBlocking {
        // Issue #23 repro at the store layer: the KB's embedding model `baseUrl` was repointed at a
        // different 1536-dim model while the `modelId` string stayed identical. The store's label
        // therefore comes from endpoint+model (see KnowledgeStoreFactory.embeddingSpaceLabel), so a
        // baseUrl-only edit changes it. Rows ingested under the old endpoint must be excluded even
        // though their `modelId` substring is unchanged and their cosine to the query is 1.0.
        val dao = FakeDao()
        val modelId = "text-embedding-3-small"
        val store = RoomVectorStore(
            dao, FakeEmbedder(), "kb1",
            embeddingModelLabel = KnowledgeStoreFactory.embeddingSpaceLabel(
                baseUrl = "https://new-host.example/v1",
                modelId = modelId,
            ),
        )

        seedRow(
            dao, id = "same", vector = Vector(listOf(1.0, 0.0)),
            embeddingModel = KnowledgeStoreFactory.embeddingSpaceLabel(
                baseUrl = "https://new-host.example/v1",
                modelId = modelId,
            ),
        )
        seedRow(
            dao, id = "stale", vector = Vector(listOf(1.0, 0.0)),
            embeddingModel = KnowledgeStoreFactory.embeddingSpaceLabel(
                baseUrl = "https://api.openai.com/v1",
                modelId = modelId,
            ),
        )

        val results = store.search(SimilaritySearchRequest(queryText = "the cat", limit = 10))
        val ids = results.map { it.document.chunkId }
        assertTrue("row from the old endpoint must be excluded, got $ids", "stale" !in ids)
        assertEquals(listOf("same"), ids)
    }

    @Test
    fun `searchInDocuments excludes chunks embedded under a different model`() = runBlocking {
        val dao = FakeDao()
        val store = RoomVectorStore(dao, FakeEmbedder(), "kb1", embeddingModelLabel = "fake-embed")

        seedRow(dao, id = "same", vector = Vector(listOf(1.0, 0.0)), embeddingModel = "fake-embed", docId = "valid-doc")
        seedRow(dao, id = "foreign", vector = Vector(listOf(1.0, 0.0)), embeddingModel = "other-embed", docId = "valid-doc")

        val results = store.searchInDocuments(
            request = SimilaritySearchRequest(queryText = "the cat", limit = 10),
            namespace = "kb1",
            allowedDocIds = setOf("valid-doc"),
        )
        val ids = results.map { it.document.chunkId }
        assertTrue("foreign-model chunk must be excluded, got $ids", "foreign" !in ids)
        assertEquals(listOf("same"), ids)
    }

    @Test
    fun `namespace scopes search to the requested knowledge base`() = runBlocking {
        val dao = FakeDao()
        val store = RoomVectorStore(dao, FakeEmbedder(), "kbA", "fake-embed")
        store.add(listOf(chunk("a1", "a cat")), namespace = "kbA")
        store.add(listOf(chunk("b1", "a cat")), namespace = "kbB")

        val results = store.search(SimilaritySearchRequest(queryText = "cat", limit = 10), namespace = "kbB")
        assertEquals(1, results.size)
        assertEquals("b1", results[0].document.chunkId)
    }
}
