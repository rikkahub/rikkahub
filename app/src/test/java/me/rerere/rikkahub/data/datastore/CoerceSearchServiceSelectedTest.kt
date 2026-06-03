package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class CoerceSearchServiceSelectedTest {
    @Test
    fun `empty search services coerces to zero without throwing`() {
        assertEquals(0, coerceSearchServiceSelected(0, 0))
        assertEquals(0, coerceSearchServiceSelected(3, 0))
    }

    @Test
    fun `in range index is preserved`() {
        assertEquals(2, coerceSearchServiceSelected(5, 3))
        assertEquals(1, coerceSearchServiceSelected(1, 3))
    }

    @Test
    fun `negative index clamps to zero`() {
        assertEquals(0, coerceSearchServiceSelected(-1, 3))
    }
}
