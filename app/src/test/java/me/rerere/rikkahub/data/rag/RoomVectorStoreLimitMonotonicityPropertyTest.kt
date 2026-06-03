package me.rerere.rikkahub.data.rag

import ai.koog.embeddings.base.Vector
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filterNot
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.rag.store.Chunk
import me.rerere.rikkahub.data.rag.store.RoomVectorStore
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * PROPERTY #4 — MONOTONICITY of rankBySimilarity in the limit parameter.
 *
 * For the same inputs, the top-k result for limit=k must be a PREFIX of the result for limit=k+1:
 * raising the limit only appends lower-ranked items, never reorders or drops the head.
 *
 * Determinism: rows are constructed with STRICTLY SEPARATED cosine scores so sortedByDescending is
 * a total order and the prefix relation is unambiguous (no tie-group reordering). Each row is a unit
 * vector at a DISTINCT angle θ_i in (0, π/2) against the query (1,0,...), so its cosine = cos θ_i and
 * all cosines are distinct. All vectors are finite and non-zero, so finiteRowCount == N.
 */
class RoomVectorStoreLimitMonotonicityPropertyTest {

    private fun chunk(id: String, docId: String = "doc") =
        Chunk(chunkId = id, docId = docId, sourceRef = "src", chunkIndex = 0, content = "x")

    private data class Case(
        val query: Vector,
        val rows: List<Pair<Chunk, Vector>>,
        val n: Int,
    )

    private val arbCase: Arb<Case> = arbitrary {
        val n = Arb.int(1..8).bind()
        // dim 2: query along x-axis; rows are unit vectors at distinct angles in (0, pi/2).
        val query = Vector(listOf(1.0, 0.0))
        // Build N STRICTLY increasing angles: cumulative sum of strictly-positive gaps, then scale
        // the whole sequence to fit inside (0, pi/2). Strictly increasing angles => strictly
        // decreasing cosines => no ties => unambiguous prefix relation.
        val maxAngle = Math.PI / 2 - 1e-3
        val gaps = (0 until n).map { Arb.double(0.01..1.0).filterNot { d -> d.isNaN() }.bind() }
        val cumulative = run {
            var acc = 0.0
            gaps.map { g -> acc += g; acc } // strictly increasing (every gap >= 0.01)
        }
        val span = cumulative.last() // > 0
        val angles = cumulative.map { c -> (c / span) * maxAngle } // strictly increasing in (0, maxAngle]
        val rows = angles.mapIndexed { i, theta ->
            chunk("c$i") to Vector(listOf(cos(theta), sin(theta)))
        }
        Case(query, rows, n)
    }

    @Test
    fun `increasing limit appends only - result(k) is a prefix of result(k+1)`() {
        runBlocking {
            checkAll(200, arbCase) { case ->
                val ids = (0..case.n).map { k ->
                    RoomVectorStore.rankBySimilarity(
                        query = case.query,
                        rows = case.rows,
                        request = SimilaritySearchRequest(queryText = "", limit = k),
                        allowedDocIds = null,
                    ).map { it.document.chunkId }
                }

                // (1) size bound: all rows are finite & non-zero, so finiteRowCount == n.
                for (k in 0..case.n) {
                    assertEquals(
                        "size at limit=$k must be min(k, n)",
                        min(k, case.n),
                        ids[k].size,
                    )
                }

                // (2) prefix relation: result(k) == result(k+1).take(result(k).size).
                for (k in 0 until case.n) {
                    assertEquals(
                        "result(limit=$k) must be a prefix of result(limit=${k + 1})",
                        ids[k],
                        ids[k + 1].take(ids[k].size),
                    )
                }
            }
        }
    }
}
