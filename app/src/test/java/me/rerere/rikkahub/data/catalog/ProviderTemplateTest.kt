package me.rerere.rikkahub.data.catalog

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTemplateTest {

    @Test
    fun `default template is Anthropic and listed first`() {
        assertEquals("anthropic", ProviderTemplates.DEFAULT.modelsDevId)
        assertEquals(ProviderWire.ANTHROPIC, ProviderTemplates.DEFAULT.wire)
        assertSame(ProviderTemplates.DEFAULT, ProviderTemplates.ALL.first())
    }

    @Test
    fun `every OpenAI-wire template has a base url`() {
        val missing = ProviderTemplates.ALL
            .filter { it.wire == ProviderWire.OPENAI && it.baseUrl.isNullOrBlank() }
        assertTrue("OpenAI-compatible templates must carry a base URL: $missing", missing.isEmpty())
    }

    @Test
    fun `display names and openai base urls are unique`() {
        val names = ProviderTemplates.ALL.map { it.displayName }
        assertEquals("duplicate display names", names.size, names.toSet().size)
        val urls = ProviderTemplates.ALL.mapNotNull { it.baseUrl }
        assertEquals("duplicate base URLs", urls.size, urls.toSet().size)
    }

    @Test
    fun `instantiate mints a fresh, user-owned provider of the right wire`() {
        val anthropic = ProviderTemplates.DEFAULT.instantiate()
        assertTrue(anthropic is ProviderSetting.Claude)
        assertEquals("Anthropic", anthropic.name)
        assertFalse("a catalog-added provider must be deletable (not built-in)", anthropic.builtIn)
        assertEquals("", (anthropic as ProviderSetting.Claude).apiKey)

        val openrouter = ProviderTemplates.ALL.first { it.displayName == "OpenRouter" }.instantiate()
        assertTrue(openrouter is ProviderSetting.OpenAI)
        assertEquals("https://openrouter.ai/api/v1", (openrouter as ProviderSetting.OpenAI).baseUrl)

        val gemini = ProviderTemplates.ALL.first { it.wire == ProviderWire.GOOGLE }.instantiate()
        assertTrue(gemini is ProviderSetting.Google)
    }

    @Test
    fun `two instantiations get distinct ids`() {
        val a = ProviderTemplates.DEFAULT.instantiate()
        val b = ProviderTemplates.DEFAULT.instantiate()
        assertFalse("each added provider must get a unique id", a.id == b.id)
    }
}
