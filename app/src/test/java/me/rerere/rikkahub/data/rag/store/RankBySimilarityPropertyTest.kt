package me.rerere.rikkahub.data.rag.store

import ai.koog.embeddings.base.Vector
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TARGET 4: invariants of the pure ranking core [RoomVectorStore.rankBySimilarity].
 *
 * Properties checked (NOT exact float scores):
 *  (1) results are sorted by score DESCENDING;
 *  (2) result size equals an independently-computed expected count
 *      (filter-by-minScore -> drop(offset) -> take(limit)), mirroring production order exactly;
 *  (3) every returned chunkId came from the input rows (membership);
 *  (4) every score is within cosine range [-1, 1] (with a small fp epsilon).
 */
class RankBySimilarityPropertyTest {

    // Realistic embedding-magnitude doubles. Bounded to [-100, 100] AND with tiny non-zero
    // magnitudes excluded (|x| in (0, 1e-3) dropped). Rationale: koog's Vector.cosineSimilarity
    // sums squares of components, so the magnitude computation is fragile at both extremes —
    //   * components near sqrt(Double.MAX) (~1.3e154) square to +Infinity  => ratio NaN, and
    //   * subnormal components (e.g. 4.9E-324) square to underflow 0.0      => magnitude 0 => ratio
    //     Infinity for an otherwise non-zero vector.
    // Neither 1e307 nor 4.9e-324 is a plausible embedding component (real embeddings are O(1)). This
    // property targets rikkahub's ordering/size/membership/bounds logic, so we feed sane magnitudes;
    // the koog numerical fragility is reported separately rather than asserted on here. Exact-zero
    // vectors are still allowed via arbVector's all-zero branch (they hit cosine's isNull -> 0.0).
    private val arbFiniteDouble: Arb<Double> =
        Arb.double(-100.0, 100.0).filter {
            it.isFinite() && !it.isNaN() && (it == 0.0 || kotlin.math.abs(it) >= 1e-3)
        }

    private fun arbVector(dim: Int): Arb<Vector> = Arb.choice(
        // Sometimes an all-zero vector to exercise the cosine isNull/0.0 branch.
        Arb.of(listOf(Vector(List(dim) { 0.0 }))),
        Arb.list(arbFiniteDouble, dim..dim).map { Vector(it) },
    )

    private fun arbRows(dim: Int): Arb<List<Pair<Chunk, Vector>>> = arbitrary {
        val count = Arb.int(0..20).bind()
        (0 until count).map { i ->
            val chunk = Chunk(
                chunkId = "chunk-$i",
                docId = "doc",
                sourceRef = "src",
                chunkIndex = i,
                content = "c$i",
            )
            chunk to arbVector(dim).bind()
        }
    }

    @Test
    fun `rankBySimilarity preserves ordering, size, membership and score bounds`() {
        runBlocking {
        val arbCase = arbitrary {
            val dim = Arb.int(1..8).bind()
            val query = arbVector(dim).bind()
            val rows = arbRows(dim).bind()
            val limit = Arb.int(0..25).bind()
            val offset = Arb.int(0..5).bind()
            val minScore = Arb.choice(
                Arb.of(listOf<Double?>(null)),
                Arb.double(-1.0..1.0).filter { it.isFinite() }.map { it as Double? },
            ).bind()
            Case(query, rows, limit, offset, minScore)
        }

        checkAll(200, arbCase) { case ->
            val request = SimilaritySearchRequest(
                queryText = "",
                limit = case.limit,
                offset = case.offset,
                minScore = case.minScore,
            )
            val results = RoomVectorStore.rankBySimilarity(case.query, case.rows, request)

            // (1) sorted DESCENDING by score
            results.zipWithNext { a, b ->
                assertTrue(
                    "scores must be non-increasing: ${a.score.value} then ${b.score.value}",
                    a.score.value >= b.score.value,
                )
            }

            // (2) size equals independently-computed expected count (same op order as production)
            val expectedSize = case.rows
                .map { (_, v) -> case.query.cosineSimilarity(v) }
                .filter { score -> case.minScore?.let { score >= it } ?: true }
                .drop(case.offset)
                .take(case.limit)
                .size
            assertEquals(expectedSize, results.size)

            // (3) membership
            val inputIds = case.rows.map { it.first.chunkId }.toSet()
            results.forEach { assertTrue(it.document.chunkId in inputIds) }

            // (4) cosine bounds with fp epsilon
            val eps = 1e-9
            results.forEach {
                assertTrue(
                    "score ${it.score.value} out of cosine range",
                    it.score.value in (-1.0 - eps)..(1.0 + eps),
                )
            }
        }
        }
    }

    /**
     * Regression for the minScore=NaN edge. The pure core's minScore filter must mirror the old
     * `.filter { score >= minScore }` exactly: a NaN threshold makes `score >= NaN` false for every
     * row, so ALL rows are dropped (reject-all). The negated form `score < NaN` is always false and
     * would instead KEEP every row, flipping the behavior. The property generator only emits finite
     * minScore (see arbCase), so this case is covered here as an explicit example.
     */
    @Test
    fun `rankBySimilarity with NaN minScore rejects all rows`() = runBlocking {
        val rows = listOf(
            Chunk("c0", "doc", "src", 0, "a") to Vector(listOf(1.0, 0.0)),
            Chunk("c1", "doc", "src", 1, "b") to Vector(listOf(0.0, 1.0)),
        )
        val request = SimilaritySearchRequest(
            queryText = "",
            limit = 10,
            offset = 0,
            minScore = Double.NaN,
        )
        val results = RoomVectorStore.rankBySimilarity(
            query = Vector(listOf(1.0, 0.0)),
            rows = rows,
            request = request,
        )
        assertEquals(0, results.size)
    }

    private data class Case(
        val query: Vector,
        val rows: List<Pair<Chunk, Vector>>,
        val limit: Int,
        val offset: Int,
        val minScore: Double?,
    )
}
