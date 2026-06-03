package me.rerere.rikkahub.data.rag

import ai.koog.embeddings.base.Vector
import ai.koog.rag.base.storage.search.ScoreMetric
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import me.rerere.rikkahub.data.rag.chunk.Chunker
import me.rerere.rikkahub.data.rag.store.Chunk
import me.rerere.rikkahub.data.rag.store.RoomVectorStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RagCoreTest {

    // ---- Chunker ----

    @Test
    fun `text shorter than chunkSize yields exactly one chunk equal to input`() {
        val text = "hello world"
        val chunks = Chunker.chunk(text, chunkSize = 100, overlap = 10)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }

    @Test
    fun `text equal to chunkSize yields one chunk`() {
        val text = "a".repeat(50)
        val chunks = Chunker.chunk(text, chunkSize = 50, overlap = 10)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }

    @Test
    fun `long text produces chunks of expected size with specified overlap`() {
        // 0..99 distinct chars via index marker; use a long deterministic string
        val text = (0 until 250).joinToString("") { ('a' + (it % 26)).toString() }
        val chunkSize = 100
        val overlap = 20
        val chunks = Chunker.chunk(text, chunkSize = chunkSize, overlap = overlap)

        // stride = 80 -> windows at 0 [0,100), 80 [80,180), 160 [160,250) (clamped, then break)
        // => 3 chunks
        assertEquals(3, chunks.size)
        // first two chunks are full-size
        assertEquals(chunkSize, chunks[0].length)
        assertEquals(chunkSize, chunks[1].length)
        // last chunk is the clamped remainder, no larger than chunkSize
        assertTrue(chunks[2].length <= chunkSize)

        // each chunk[n] starts `overlap` chars before chunk[n-1]'s end
        val stride = chunkSize - overlap
        assertEquals(text.substring(0, chunkSize), chunks[0])
        assertEquals(text.substring(stride, stride + chunkSize), chunks[1])
        assertEquals(text.substring(2 * stride), chunks[2])
    }

    @Test
    fun `overlap region content matches between consecutive chunks`() {
        val text = (0 until 300).joinToString("") { ('a' + (it % 26)).toString() }
        val chunkSize = 100
        val overlap = 30
        val chunks = Chunker.chunk(text, chunkSize = chunkSize, overlap = overlap)

        // tail of chunk[0] (last `overlap` chars) equals head of chunk[1] (first `overlap` chars)
        val tail = chunks[0].takeLast(overlap)
        val head = chunks[1].take(overlap)
        assertEquals(tail, head)
    }

    @Test
    fun `no empty or whitespace-only chunks emitted`() {
        val text = "   " + "word ".repeat(200) + "   "
        val chunks = Chunker.chunk(text, chunkSize = 64, overlap = 8)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.none { it.isBlank() })
    }

    @Test
    fun `empty input yields empty list`() {
        assertEquals(emptyList<String>(), Chunker.chunk("   \n  ", chunkSize = 50, overlap = 10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `overlap not less than chunkSize is rejected`() {
        Chunker.chunk("abc", chunkSize = 10, overlap = 10)
    }

    // ---- Cosine + top-k (rankBySimilarity) ----

    private fun chunk(id: String, text: String) =
        Chunk(chunkId = id, docId = "d", sourceRef = "src", chunkIndex = 0, content = text)

    @Test
    fun `results returned nearest-first by cosine similarity`() {
        val query = Vector(listOf(1.0, 0.0))
        val rows = listOf(
            chunk("orthogonal", "x") to Vector(listOf(0.0, 1.0)),     // cos 0
            chunk("identical", "y") to Vector(listOf(1.0, 0.0)),      // cos 1
            chunk("close", "z") to Vector(listOf(0.9, 0.1)),          // cos ~0.99
        )
        val results = RoomVectorStore.rankBySimilarity(
            query = query,
            rows = rows,
            request = SimilaritySearchRequest(queryText = "q", limit = 10),
        )
        assertEquals(3, results.size)
        // nearest in direction is the identical vector
        assertEquals("identical", results[0].document.chunkId)
        assertEquals("close", results[1].document.chunkId)
        assertEquals("orthogonal", results[2].document.chunkId)
        // descending order invariant
        assertTrue(results[0].score.value >= results[1].score.value)
        assertTrue(results[1].score.value >= results[2].score.value)
    }

    @Test
    fun `limit caps result count and offset skips`() {
        val query = Vector(listOf(1.0, 0.0))
        val rows = listOf(
            chunk("a", "1") to Vector(listOf(1.0, 0.0)),   // cos 1
            chunk("b", "2") to Vector(listOf(0.8, 0.2)),   // cos ~0.97
            chunk("c", "3") to Vector(listOf(0.5, 0.5)),   // cos ~0.71
        )
        val limited = RoomVectorStore.rankBySimilarity(
            query, rows, SimilaritySearchRequest(queryText = "q", limit = 2),
        )
        assertEquals(2, limited.size)
        assertEquals("a", limited[0].document.chunkId)
        assertEquals("b", limited[1].document.chunkId)

        val offset = RoomVectorStore.rankBySimilarity(
            query, rows, SimilaritySearchRequest(queryText = "q", limit = 2, offset = 1),
        )
        assertEquals("b", offset[0].document.chunkId)
        assertEquals("c", offset[1].document.chunkId)
    }

    @Test
    fun `minScore filters out below-threshold results and metric is cosine`() {
        val query = Vector(listOf(1.0, 0.0))
        val rows = listOf(
            chunk("hi", "1") to Vector(listOf(1.0, 0.0)),     // cos 1
            chunk("lo", "2") to Vector(listOf(0.0, 1.0)),     // cos 0
        )
        val results = RoomVectorStore.rankBySimilarity(
            query, rows, SimilaritySearchRequest(queryText = "q", limit = 10, minScore = 0.5),
        )
        assertEquals(1, results.size)
        assertEquals("hi", results[0].document.chunkId)
        assertEquals(ScoreMetric.COSINE_SIMILARITY, results[0].score.metric)
    }

    @Test
    fun `vector encode round-trips through string column`() {
        val v = Vector(listOf(0.1, -0.25, 1.5, 0.0))
        val decoded = RoomVectorStore.decodeVector(RoomVectorStore.encodeVector(v))
        assertEquals(v.values.size, decoded.values.size)
        v.values.zip(decoded.values).forEach { (a, b) -> assertEquals(a, b, 1e-9) }
        // search result carries the id we stored
        assertNotNull(decoded)
    }
}
