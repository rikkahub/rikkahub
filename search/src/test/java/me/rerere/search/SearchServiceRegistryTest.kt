package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replaces the compiler's `when`-exhaustiveness guarantee that the old [SearchService.getService]
 * dispatcher had: with the registry, a [SearchServiceOptions] subtype added without a
 * [SEARCH_SERVICE_REGISTRY] entry would compile but crash at runtime. This pins the registry to cover
 * EXACTLY [SearchServiceOptions.TYPES] (the picker's source of truth) in both directions — every
 * listed type has a service, and no service is registered for a type the picker doesn't list — so
 * adding a provider without wiring its service reddens here.
 */
class SearchServiceRegistryTest {

    @Test
    fun `every listed search type has a registered service`() {
        SearchServiceOptions.TYPES.keys.forEach { type ->
            assertTrue(
                "no search service registered for ${type.simpleName}",
                SEARCH_SERVICE_REGISTRY.containsKey(type),
            )
        }
    }

    @Test
    fun `the registry has no orphan service without a matching listed type`() {
        val known = SearchServiceOptions.TYPES.keys
        SEARCH_SERVICE_REGISTRY.keys.forEach { key ->
            assertTrue("registry has a service for an unlisted type ${key.simpleName}", key in known)
        }
    }

    @Test
    fun `registry size matches the number of listed types`() {
        assertEquals(SearchServiceOptions.TYPES.size, SEARCH_SERVICE_REGISTRY.size)
    }
}
