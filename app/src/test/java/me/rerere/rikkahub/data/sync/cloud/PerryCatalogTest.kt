package me.rerere.rikkahub.data.sync.cloud

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerryCatalogTest {
    @Test
    fun providerUuid_isStable() {
        val a = PerryCatalog.providerUuid("openai")
        val b = PerryCatalog.providerUuid("openai")
        val c = PerryCatalog.providerUuid("anthropic")
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun isPerryGateway_detectsPath() {
        val perry = ProviderSetting.OpenAI(
            baseUrl = "http://192.168.3.44:8787/v1/ai/openai/v1",
            apiKey = "tok",
        )
        val direct = ProviderSetting.OpenAI(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk",
        )
        assertTrue(PerryCatalog.isPerryGateway(perry))
        assertFalse(PerryCatalog.isPerryGateway(direct))
        assertEquals("openai", PerryCatalog.monelProviderIdFromBaseUrl(perry.baseUrl))
    }

    @Test
    fun mergeProviders_preservesLocalModels() {
        val id = PerryCatalog.providerUuid("openai")
        val existing = listOf(
            ProviderSetting.OpenAI(
                id = id,
                name = "Perry / OpenAI",
                baseUrl = "http://old/v1/ai/openai/v1",
                apiKey = "old-token",
                models = listOf(
                    me.rerere.ai.provider.Model(modelId = "gpt-4o-mini", displayName = "mini"),
                ),
            )
        )
        val imported = listOf(
            ProviderSetting.OpenAI(
                id = id,
                name = "Perry / OpenAI",
                baseUrl = "http://new/v1/ai/openai/v1",
                apiKey = "new-token",
                models = emptyList(),
            )
        )
        val merged = PerryCatalog.mergeProviders(existing, imported)
        val openai = merged.filterIsInstance<ProviderSetting.OpenAI>().first { it.id == id }
        assertEquals("new-token", openai.apiKey)
        assertEquals("http://new/v1/ai/openai/v1", openai.baseUrl)
        assertEquals(1, openai.models.size)
        assertEquals("gpt-4o-mini", openai.models.first().modelId)
    }

    @Test
    fun toBrowseModels_usesModelUuidWhenPresent() {
        val models = PerryCatalog.toBrowseModels(
            monelProviderId = "openai",
            entries = listOf(
                CatalogModelEntryDto(
                    id = "gpt-4o-mini",
                    modelUuid = "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
                )
            ),
        )
        assertEquals(1, models.size)
        assertEquals("gpt-4o-mini", models.first().modelId)
        assertEquals(
            kotlin.uuid.Uuid.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8"),
            models.first().id,
        )
    }
}
