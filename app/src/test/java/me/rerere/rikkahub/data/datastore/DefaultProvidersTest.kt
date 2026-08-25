package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `default providers are empty`() {
        assertTrue(DEFAULT_PROVIDERS.isEmpty())
    }
}
