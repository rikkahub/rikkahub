package me.rerere.rikkahub.data.rag

import ai.koog.embeddings.base.Vector
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filterNot
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.rag.store.Chunk
import me.rerere.rikkahub.data.rag.store.RoomVectorStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * PROPERTY #3 — METAMORPHIC scale-invariance + SYMMETRY for cosine / rankBySimilarity.
 *
 * (a) SCALE: cosine is scale-invariant, so multiplying the query AND every row vector by the same
 *     positive constant k must not change the ranking ORDER. Compared tie-tolerantly: a score gap
 *     under 1e-9 is treated as a tie (sort order is then not load-bearing) to dodge float-multiply
 *     reordering of near-equal cosines. Exact scores are NOT compared (Kahan sum + scaling drift).
 *
 * (b) SYMMETRY: a.cosineSimilarity(b) == b.cosineSimilarity(a) within 1e-9.
 *
 * Generators exclude NaN/Inf components (a non-finite cosine is dropped at RoomVectorStore.kt:148,
 * silently shrinking results) and exclude all-zero vectors (cosine short-circuits to 0.0 at
 * Vector.isNull(), making the assertions vacuous).
 */
class RoomVectorStoreScaleSymmetryPropertyTest {

    private fun chunk(id: String, docId: String = "doc") =
        Chunk(chunkId = id, docId = docId, sourceRef = "src", chunkIndex = 0, content = "x")

    // A component is either exactly 0.0 or has magnitude in [1e-3, 100]. The [1e-3, 100] floor/ceiling
    // keeps every component^2 well inside the normal Double range, so a vector's magnitude can neither
    // UNDERFLOW to 0 (subnormal squares -> 0, which would make cosine divide-by-zero -> non-finite,
    // exactly the case RoomVectorStore.kt:148 drops) nor OVERFLOW to Inf. This excludes only the
    // degenerate vectors production code itself discards as non-finite, not the symmetry space.
    private val arbComponent: Arb<Double> = Arb.double(-100.0..100.0)
        .filterNot { it.isNaN() || it.isInfinite() }
        .map { if (kotlin.math.abs(it) < 1e-3) 0.0 else it }

    private fun arbVector(dim: Int): Arb<Vector> =
        Arb.list(arbComponent, dim..dim)
            .map { Vector(it) }
            // Reject all-zero (cosine 0.0 short-circuit) and any vector whose magnitude is not a
            // strictly-positive finite Double (defensive; the component range already guarantees it).
            .filterNot { it.isNull() }
            .filterNot { val m = it.magnitude(); !m.isFinite() || m <= 0.0 }

    private val arbDim: Arb<Int> = Arb.int(2..8)
    private val arbK: Arb<Double> = Arb.double(0.0001..1000.0).filterNot { it.isNaN() || it.isInfinite() }

    private fun scale(v: Vector, k: Double): Vector = Vector(v.values.map { it * k })

    /**
     * Tie-tolerant ranking-order equality: two id lists agree if at every position the ids match, OR
     * the two rankings differ only within a tie group (scores within 1e-9). We assert the id SET is
     * identical always, and the ORDER is identical wherever scores are strictly separated.
     */
    private fun assertSameRanking(
        r1: List<Pair<String, Double>>,
        r2: List<Pair<String, Double>>,
    ) {
        assertEquals("ranking must contain the same chunk set", r1.map { it.first }.toSet(), r2.map { it.first }.toSet())
        assertEquals("ranking length must match", r1.size, r2.size)
        for (i in r1.indices) {
            val (id1, s1) = r1[i]
            val (id2, _) = r2[i]
            if (id1 == id2) continue
            // Ids differ at this position: tolerate ONLY if this is a tie wrt the neighbour that
            // would have been needed to swap them, i.e. r1's score here is within 1e-9 of r2's
            // score for r1's id at its own position.
            val s2ForId1 = r2.first { it.first == id1 }.second
            assertTrue(
                "order flip at $i between $id1 ($s1) and $id2 not explained by a <=1e-9 tie",
                abs(s1 - s2ForId1) <= 1e-9,
            )
        }
    }

    private data class ScaleCase(
        val query: Vector,
        val rows: List<Pair<Chunk, Vector>>,
        val k: Double,
    )

    private val arbScaleCase: Arb<ScaleCase> = arbitrary {
        val dim = arbDim.bind()
        val query = arbVector(dim).bind()
        val n = Arb.int(1..6).bind()
        val rows = (0 until n).map { i -> chunk("c$i") to arbVector(dim).bind() }
        ScaleCase(query, rows, arbK.bind())
    }

    private data class VectorPair(val a: Vector, val b: Vector)

    private val arbVectorPair: Arb<VectorPair> = arbitrary {
        val dim = arbDim.bind()
        VectorPair(arbVector(dim).bind(), arbVector(dim).bind())
    }

    @Test
    fun `ranking order is invariant under positive scaling of all vectors`() {
        runBlocking {
            checkAll(200, arbScaleCase) { case ->
                val req = SimilaritySearchRequest(queryText = "", limit = case.rows.size)
                val r1 = RoomVectorStore.rankBySimilarity(case.query, case.rows, req, allowedDocIds = null)
                    .map { it.document.chunkId to it.score.value }

                val scaledQuery = scale(case.query, case.k)
                val scaledRows = case.rows.map { (c, v) -> c to scale(v, case.k) }
                val r2 = RoomVectorStore.rankBySimilarity(scaledQuery, scaledRows, req, allowedDocIds = null)
                    .map { it.document.chunkId to it.score.value }

                assertSameRanking(r1, r2)
            }
        }
    }

    @Test
    fun `cosine similarity is symmetric`() {
        runBlocking {
            checkAll(200, arbVectorPair) { pair ->
                assertTrue(abs(pair.a.cosineSimilarity(pair.b) - pair.b.cosineSimilarity(pair.a)) <= 1e-9)
            }
        }
    }
}
