package me.rerere.rikkahub.data.ai.memory

import ai.koog.embeddings.base.Vector
import me.rerere.ai.runtime.contract.RecalledMemory
import me.rerere.ai.runtime.memory.MEMORY_MIN_SCORE
import me.rerere.ai.runtime.memory.memoryContentHash
import me.rerere.rikkahub.data.ai.memory.EmbeddingMemoryRecaller.MemoryCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the candidate PARTITIONING contract of [EmbeddingMemoryRecaller.rankRecall]: every candidate
 * lands in exactly one of three buckets —
 *
 *  1. USABLE (non-null vector + fresh content hash + same embedding space + matching dimension)
 *     ⇒ cosine-ranked head, kept only at/above [MEMORY_MIN_SCORE];
 *  2. NOT USABLE for any single reason (null vector, stale hash, different space, dimension
 *     mismatch) ⇒ recency tail, never cosine-scored;
 *  3. USABLE but below the floor ⇒ DROPPED entirely (not demoted to the tail).
 *
 * The `usable` predicate is the exact code the always-false-condition warning fix
 * (EmbeddingMemoryRecaller.kt:110, run 27388701759) restructures, so this suite is the behavior
 * guard for that restructure: each not-usable reason is pinned individually to fail if the
 * early-continue rewrite drops or reorders any freshness key.
 */
class EmbeddingMemoryRecallerTest {

    private val space = "https://api.example.com#text-embedding-3-small"
    private val query = Vector(listOf(1.0, 0.0))

    private fun memory(id: Int, updatedAt: Long) =
        RecalledMemory(id = id, content = "m$id", createdAt = 0, updatedAt = updatedAt)

    private fun usable(memory: RecalledMemory, vector: Vector) = MemoryCandidate(
        memory = memory,
        storedVector = vector,
        storedContentHash = memoryContentHash(memory.content),
        storedEmbeddingSpace = space,
    )

    private fun freshHash(candidates: List<MemoryCandidate>): (Int) -> String {
        val byId = candidates.associate { it.memory.id to memoryContentHash(it.memory.content) }
        return { id -> byId.getValue(id) }
    }

    private fun rank(candidates: List<MemoryCandidate>, k: Int) =
        EmbeddingMemoryRecaller.rankRecall(
            query = query,
            candidates = candidates,
            currentSpace = space,
            currentContentHash = freshHash(candidates),
            k = k,
            minScore = MEMORY_MIN_SCORE,
        )

    @Test
    fun `each not-usable reason demotes to the recency tail, below-floor usable is dropped`() {
        val aligned = usable(memory(id = 1, updatedAt = 1), Vector(listOf(1.0, 0.0)))
        val nullVector = MemoryCandidate(
            memory = memory(id = 2, updatedAt = 40),
            storedVector = null,
            storedContentHash = null,
            storedEmbeddingSpace = null,
        )
        val staleHash = usable(memory(id = 3, updatedAt = 30), Vector(listOf(1.0, 0.0)))
            .copy(storedContentHash = "stale")
        val otherSpace = usable(memory(id = 4, updatedAt = 20), Vector(listOf(1.0, 0.0)))
            .copy(storedEmbeddingSpace = "$space-other")
        val wrongDimension = usable(memory(id = 5, updatedAt = 10), Vector(listOf(1.0, 0.0, 0.0)))
        val belowFloor = usable(memory(id = 6, updatedAt = 50), Vector(listOf(0.0, 1.0)))

        val result = rank(
            candidates = listOf(belowFloor, wrongDimension, otherSpace, staleHash, nullVector, aligned),
            k = 10,
        )

        // Head: the one usable at/above-floor candidate. Tail: the four not-usable candidates by
        // recency (updatedAt DESC). Dropped: the below-floor usable candidate (id 6) — even though
        // it is the most recently updated and k leaves room, it must NOT reappear in the tail.
        assertEquals(listOf(1, 2, 3, 4, 5), result.map { it.id })
    }

    @Test
    fun `recency tail only fills the slots cosine ranking left open`() {
        val alignedNewer = usable(memory(id = 1, updatedAt = 2), Vector(listOf(1.0, 0.0)))
        val alignedOlder = usable(memory(id = 2, updatedAt = 1), Vector(listOf(1.0, 0.0)))
        val unembedded = MemoryCandidate(
            memory = memory(id = 3, updatedAt = 99),
            storedVector = null,
            storedContentHash = null,
            storedEmbeddingSpace = null,
        )

        // k == 2 is exhausted by the cosine head (score ties broken by updatedAt DESC), so the
        // most-recent-but-unembedded candidate gets no slot.
        assertEquals(listOf(1, 2), rank(listOf(unembedded, alignedOlder, alignedNewer), k = 2).map { it.id })
        // One extra slot ⇒ the tail supplies it.
        assertEquals(listOf(1, 2, 3), rank(listOf(unembedded, alignedOlder, alignedNewer), k = 3).map { it.id })
    }
}
