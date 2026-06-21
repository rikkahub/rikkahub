package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.ANTIGRAVITY_IMAGE_MODEL_ID
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityGatingTest {

    private fun google(
        antigravity: Boolean = true,
        token: String = "refresh-tok",
        enabled: Boolean = true,
    ) = ProviderSetting.Google(
        name = "Gemini",
        enabled = enabled,
        antigravity = antigravity,
        antigravityRefreshToken = token,
    )

    private fun settings(vararg providers: ProviderSetting) = Settings(providers = providers.toList())

    @Test
    fun `hasAntigravity true only when enabled antigravity google with a token`() {
        assertTrue(settings(google()).hasAntigravity())
        assertFalse("antigravity off", settings(google(antigravity = false)).hasAntigravity())
        assertFalse("blank token", settings(google(token = "")).hasAntigravity())
        assertFalse("disabled", settings(google(enabled = false)).hasAntigravity())
        assertFalse("no providers", settings().hasAntigravity())
        assertFalse("only openai", settings(ProviderSetting.OpenAI(name = "x")).hasAntigravity())
    }

    @Test
    fun `antigravityRefreshToken returns the configured token or null`() {
        assertEquals("refresh-tok", settings(google()).antigravityRefreshToken())
        assertNull(settings(google(antigravity = false)).antigravityRefreshToken())
    }

    @Test
    fun `withAntigravityImageModels adds the IMAGE model to the antigravity provider only`() {
        val openai = ProviderSetting.OpenAI(name = "x")
        val out = settings(google(), openai).withAntigravityImageModels()

        val g = out.first { it is ProviderSetting.Google }
        val img = g.models.singleOrNull { it.modelId == ANTIGRAVITY_IMAGE_MODEL_ID }
        assertTrue("antigravity provider gains the image model", img != null)
        assertEquals(ModelType.IMAGE, img!!.type)

        // Non-gagy providers are untouched.
        assertTrue(out.first { it is ProviderSetting.OpenAI }.models.isEmpty())
    }

    @Test
    fun `two antigravity providers do not duplicate the fixed-id image model`() {
        val out = settings(google(), google()).withAntigravityImageModels()
        val total = out.flatMap { it.models }.count { it.modelId == ANTIGRAVITY_IMAGE_MODEL_ID }
        assertEquals("image model added to exactly one provider", 1, total)
    }

    @Test
    fun `resolveSearchOptions injects the token for Google Search and passes others through`() {
        val withAg = settings(google())
        val resolved = withAg.resolveSearchOptions(SearchServiceOptions.GoogleSearchOptions())
        assertEquals("refresh-tok", (resolved as SearchServiceOptions.GoogleSearchOptions).refreshToken)

        // A non-Google engine is returned unchanged.
        val exa = SearchServiceOptions.ExaOptions(apiKey = "k")
        assertEquals(exa, withAg.resolveSearchOptions(exa))
    }

    @Test
    fun `withAntigravityImageModels is idempotent and a no-op without antigravity`() {
        // No duplicate on repeated application.
        val once = settings(google()).withAntigravityImageModels()
        val twice = Settings(providers = once).withAntigravityImageModels()
        val g = twice.first { it is ProviderSetting.Google }
        assertEquals(1, g.models.count { it.modelId == ANTIGRAVITY_IMAGE_MODEL_ID })

        // No gagy → providers returned unchanged (image model stays hidden).
        val plain = settings(google(antigravity = false)).withAntigravityImageModels()
        assertTrue(plain.first { it is ProviderSetting.Google }.models.isEmpty())
    }
}
