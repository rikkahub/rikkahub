package me.rerere.ai.runtime.memory

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.contract.RecalledMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct unit tests for the neutral memory recall VALUE cores moved to `:ai-runtime`
 * (issue #243 slice 8/10): [rankByRecency], [memoryAgeLabel] / [memoryAgeDays], and
 * [memoryContentHash]. These mirror the app-side property coverage (issue #210 §8 P4/P7/P9) for the
 * exact units that crossed the boundary, so a behaviour drift on the move is caught under the
 * `:ai-runtime:test` gate without standing up Room/koog/Android.
 *
 * On the un-moved tree these symbols do not exist in `me.rerere.ai.runtime.memory`, so this test does
 * not compile — it fails for precisely the reason "the cores have not been moved yet".
 */
class MemoryRecallValuesTest {

    private fun memory(id: Int, content: String = "m$id", createdAt: Long = 0, updatedAt: Long = 0) =
        RecalledMemory(id = id, content = content, createdAt = createdAt, updatedAt = updatedAt)

    // ---- rankByRecency ----------------------------------------------------------------------

    @Test
    fun `rankByRecency returns most-recently-updated first, ties broken by descending id, capped at k`() {
        val a = memory(id = 1, updatedAt = 10)
        val b = memory(id = 2, updatedAt = 30)
        val c = memory(id = 3, updatedAt = 30) // tie on updatedAt with b; higher id wins
        val d = memory(id = 4, updatedAt = 20)
        val ranked = rankByRecency(listOf(a, b, c, d), k = 3)
        assertEquals(listOf(c, b, d), ranked)
    }

    @Test
    fun `rankByRecency with non-positive k returns empty`() {
        val list = listOf(memory(1, updatedAt = 5))
        assertEquals(emptyList<RecalledMemory>(), rankByRecency(list, k = 0))
        assertEquals(emptyList<RecalledMemory>(), rankByRecency(list, k = -1))
    }

    @Test
    fun `rankByRecency never returns more than k and is a subset`(): Unit = runBlocking {
        checkAll(300, Arb.int(1..6)) { k ->
            val memories = (1..8).map { memory(id = it, updatedAt = (it * 7L) % 13) }
            val ids = memories.map { it.id }.toSet()
            val ranked = rankByRecency(memories, k)
            assertTrue("size <= k", ranked.size <= k)
            assertTrue("subset of input", ranked.all { it.id in ids })
            assertEquals("no duplicate ids", ranked.size, ranked.map { it.id }.toSet().size)
        }
    }

    // ---- memoryAgeLabel / memoryAgeDays -----------------------------------------------------

    @Test
    fun `memoryAgeLabel renders today, yesterday, N days ago, and clamps the future`() {
        val now = 1_000L * 24 * 60 * 60 * 1000 // day 1000 in ms
        val day = 24L * 60 * 60 * 1000
        assertEquals("today", memoryAgeLabel(referenceMs = now, nowMs = now))
        assertEquals("today", memoryAgeLabel(referenceMs = now - day / 2, nowMs = now)) // <1 day
        assertEquals("yesterday", memoryAgeLabel(referenceMs = now - day, nowMs = now))
        assertEquals("3 days ago", memoryAgeLabel(referenceMs = now - 3 * day, nowMs = now))
        // Future reference (clock skew) clamps to "today", never a negative count.
        assertEquals("today", memoryAgeLabel(referenceMs = now + 5 * day, nowMs = now))
    }

    @Test
    fun `memoryAgeDays is always non-negative`(): Unit = runBlocking {
        checkAll(300, Arb.long(0L..4_000_000_000_000L), Arb.long(0L..4_000_000_000_000L)) { ref, now ->
            assertTrue(memoryAgeDays(referenceMs = ref, nowMs = now) >= 0L)
        }
    }

    // ---- memoryContentHash ------------------------------------------------------------------

    @Test
    fun `memoryContentHash is deterministic and distinct content yields a distinct hash`(): Unit = runBlocking {
        val arbContent = Arb.string(1..12)
        checkAll(300, arbContent, arbContent) { c1, c2 ->
            assertEquals(memoryContentHash(c1), memoryContentHash(c1)) // deterministic
            if (c1 != c2) {
                assertFalse("distinct content ⇒ distinct hash", memoryContentHash(c1) == memoryContentHash(c2))
            }
        }
    }
}
