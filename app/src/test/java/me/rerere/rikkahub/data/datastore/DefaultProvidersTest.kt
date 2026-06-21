package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `seed is the two flagship wires, Anthropic first`() {
        assertEquals(2, DEFAULT_PROVIDERS.size)

        val anthropic = DEFAULT_PROVIDERS[0]
        assertTrue(anthropic is ProviderSetting.Claude)
        assertEquals("Anthropic", anthropic.name)

        val openai = DEFAULT_PROVIDERS[1]
        assertTrue(openai is ProviderSetting.OpenAI)
        assertEquals("OpenAI", openai.name)
        assertEquals("https://api.openai.com/v1", (openai as ProviderSetting.OpenAI).baseUrl)
    }

    @Test
    fun `seeded providers are enabled, empty, and user-owned (deletable)`() {
        DEFAULT_PROVIDERS.forEach { provider ->
            assertTrue("seed should be enabled", provider.enabled)
            assertTrue("seed should ship no models", provider.models.isEmpty())
            // builtIn=false → the delete affordance is shown; the seed is a convenience, not a fixture.
            assertFalse("seed must be deletable", provider.builtIn)
        }
    }

    @Test
    fun `the hosted auto gateway is gone`() {
        assertFalse(DEFAULT_PROVIDERS.any { it.name == "RikkaHub" })
        assertFalse(DEFAULT_PROVIDERS.flatMap { it.models }.any { it.modelId == "auto" })
    }

    @Test
    fun `the kept OpenAI id is reused (config continuity) and not treated as legacy`() {
        val openaiId = "1eeea727-9ee5-4cae-93e6-6fb01a4d051e"
        assertTrue(DEFAULT_PROVIDERS.any { it.id.toString() == openaiId })
        assertFalse(
            "the current OpenAI default must not be in the legacy-removal set",
            openaiId in LEGACY_BUILTIN_PROVIDER_IDS,
        )
    }

    @Test
    fun `legacy set covers the removed built-ins and excludes current defaults`() {
        // RikkaHub + Gemini + the long tail are all up for pristine removal.
        assertTrue("a8d2d463-e8c0-41f2-b89e-f5eb8e716cce" in LEGACY_BUILTIN_PROVIDER_IDS) // RikkaHub
        assertTrue("6ab18148-c138-4394-a46f-1cd8c8ceaa6d" in LEGACY_BUILTIN_PROVIDER_IDS) // Gemini
        // No current default id leaks into the legacy set.
        DEFAULT_PROVIDERS.forEach {
            assertFalse(it.id.toString() in LEGACY_BUILTIN_PROVIDER_IDS)
        }
    }
}
