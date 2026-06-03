package me.rerere.rikkahub.data.rag

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import ai.koog.rag.base.storage.search.ScoreMetric
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import kotlinx.coroutines.runBlocking
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
        override suspend fun insertAll(chunks: List<KnowledgeChunkEntity>) {
            chunks.forEach { c -> rows.removeAll { it.id == c.id }; rows.add(c) }
        }
        override suspend fun getByKb(kbId: String): List<KnowledgeChunkEntity> =
            rows.filter { it.kbId == kbId }
        override suspend fun getByIds(ids: List<String>): List<KnowledgeChunkEntity> =
            rows.filter { it.id in ids }
        override suspend fun countByKb(kbId: String): Int = rows.count { it.kbId == kbId }
        override suspend fun deleteByKb(kbId: String) { rows.removeAll { it.kbId == kbId } }
        override suspend fun deleteByDoc(kbId: String, docId: String) {
            rows.removeAll { it.kbId == kbId && it.docId == docId }
        }
    }

    private fun chunk(id: String, content: String) =
        Chunk(chunkId = id, docId = "doc", sourceRef = "src", chunkIndex = 0, content = content)

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
