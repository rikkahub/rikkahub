package me.rerere.rikkahub.data.rag.store

import ai.koog.embeddings.base.Vector
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TARGET 3: [RoomVectorStore.encodeVector] / [RoomVectorStore.decodeVector] roundtrip.
 *
 * encodeVector is `values.joinToString(",")` => Double.toString; decodeVector is `String.toDouble`.
 * For finite, non-NaN doubles this pair is EXACT (Java's Double.toString emits the shortest string
 * that parses back to the same bit pattern). Property (Lossless): decode(encode(v)) == v exactly.
 *
 * NaN/Infinity are excluded by the generator: NaN != NaN would make any roundtrip "fail" and
 * Infinity has no finite textual round trip here. This is a documented PRODUCTION FRAGILITY —
 * encodeVector does not guard non-finite values, so a NaN/Infinity embedding would silently corrupt
 * on roundtrip. The property stays honest by only asserting the lossless domain; the gap is
 * reported rather than papered over.
 */
class VectorCodecPropertyTest {

    private val arbFiniteDouble: Arb<Double> =
        Arb.double().filter { it.isFinite() && !it.isNaN() }

    private val arbVector: Arb<Vector> =
        Arb.list(arbFiniteDouble, 1..16).map { Vector(it) }

    @Test
    fun `decodeVector inverts encodeVector exactly for finite vectors`() {
        runBlocking {
            checkAll(200, arbVector) { v ->
                val roundtripped = RoomVectorStore.decodeVector(RoomVectorStore.encodeVector(v))
                assertEquals(v, roundtripped)
            }
        }
    }

    /** Empty vector edge: encode => "" => decode => empty. Asserted explicitly, off the random stream. */
    @Test
    fun `empty vector roundtrips through the empty-string form`() {
        val empty = Vector(emptyList())
        assertEquals(empty, RoomVectorStore.decodeVector(RoomVectorStore.encodeVector(empty)))
    }
}
