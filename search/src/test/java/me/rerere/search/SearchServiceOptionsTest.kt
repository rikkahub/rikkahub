package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SearchServiceOptionsTest {
    @Test
    fun `Custom JS is not an available search service type`() {
        assertFalse(SearchServiceOptions.TYPES.containsKey(SearchServiceOptions.CustomJsOptions::class))
    }

    @Test
    fun `migrating only Custom JS falls back to Bing`() {
        val (services, selectedIndex) = SearchServiceOptions.migrateDisabledCustomJs(
            services = listOf(SearchServiceOptions.CustomJsOptions()),
            selectedIndex = 0,
        )

        assertEquals(listOf(SearchServiceOptions.BingLocalOptions::class), services.map { it::class })
        assertEquals(0, selectedIndex)
    }
}
