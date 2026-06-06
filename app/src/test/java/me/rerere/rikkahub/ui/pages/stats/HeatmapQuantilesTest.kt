package me.rerere.rikkahub.ui.pages.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class HeatmapQuantilesTest {

    private fun day(offset: Long) = LocalDate.of(2026, 1, 1).plusDays(offset)

    @Test
    fun `empty map falls back to 1 2 3`() {
        val (q1, q2, q3) = heatmapQuantiles(emptyMap())
        assertEquals(1, q1)
        assertEquals(2, q2)
        assertEquals(3, q3)
    }

    @Test
    fun `known distribution locks index arithmetic`() {
        // 8 active counts: sorted = [1,2,3,4,5,6,7,8]
        // q1 index = (8*0.25)=2 -> 3 ; q2 index = (8*0.50)=4 -> 5 ; q3 index = (8*0.75)=6 -> 7
        val counts = (1..8).associate { day(it.toLong()) to it }
        val (q1, q2, q3) = heatmapQuantiles(counts)
        assertEquals(3, q1)
        assertEquals(5, q2)
        assertEquals(7, q3)
    }

    @Test
    fun `zero count days are excluded before sorting`() {
        val active = (1..8).associate { day(it.toLong()) to it }
        val withZeros = active + mapOf(day(100) to 0, day(101) to 0, day(102) to 0)
        assertEquals(heatmapQuantiles(active), heatmapQuantiles(withZeros))
    }
}
