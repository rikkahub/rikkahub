package me.rerere.rikkahub.data.datastore.migration

import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PreferenceStoreV4MigrationTest {
    @Test
    fun `custom JS services are removed and selected provider is preserved`() {
        val bing = SearchServiceOptions.BingLocalOptions()
        val migrated = migrateDisabledCustomJsSearchServices(
            servicesJson = JsonInstant.encodeToString(
                listOf(SearchServiceOptions.CustomJsOptions(), bing),
            ),
            selectedIndex = 1,
        )

        val services = JsonInstant.decodeFromString<List<SearchServiceOptions>>(migrated.servicesJson)
        assertEquals(SearchServiceOptions.BingLocalOptions::class, services.single()::class)
        assertEquals(bing.id, services.single().id)
        assertEquals(0, migrated.selectedIndex)
        assertFalse(services.any { it is SearchServiceOptions.CustomJsOptions })
    }

    @Test
    fun `only custom JS service falls back to Bing`() {
        val migrated = migrateDisabledCustomJsSearchServices(
            servicesJson = JsonInstant.encodeToString(listOf(SearchServiceOptions.CustomJsOptions())),
            selectedIndex = 0,
        )

        val services = JsonInstant.decodeFromString<List<SearchServiceOptions>>(migrated.servicesJson)
        assertEquals(1, services.size)
        assertEquals(SearchServiceOptions.BingLocalOptions::class, services.single()::class)
        assertEquals(0, migrated.selectedIndex)
    }
}
