package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderBaseUrlResetTest {
    private val deepSeekDefault = DEFAULT_PROVIDERS
        .filterIsInstance<ProviderSetting.OpenAI>()
        .first { it.baseUrl == "https://api.deepseek.com/v1" }

    @Test
    fun `resetBaseUrlToDefault should restore built-in provider endpoint by provider id`() {
        val modified = deepSeekDefault.copy(baseUrl = "https://api.openai.com/v1")

        val reset = modified.resetBaseUrlToDefault() as ProviderSetting.OpenAI

        assertEquals("https://api.deepseek.com/v1", reset.baseUrl)
    }

    @Test
    fun `resetBaseUrlToDefault should fallback to type default for custom provider`() {
        val customOpenAI = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "Custom OpenAI",
            baseUrl = "https://proxy.example.com/v1"
        )

        val reset = customOpenAI.resetBaseUrlToDefault() as ProviderSetting.OpenAI

        assertEquals("https://api.openai.com/v1", reset.baseUrl)
    }

    @Test
    fun `resetBaseUrlToDefault should fallback to type default when id matches but type mismatches`() {
        val mismatchGoogle = ProviderSetting.Google(
            id = deepSeekDefault.id,
            name = "Mismatched Google",
            baseUrl = "https://proxy.example.com/v1beta"
        )

        val reset = mismatchGoogle.resetBaseUrlToDefault() as ProviderSetting.Google

        assertEquals("https://generativelanguage.googleapis.com/v1beta", reset.baseUrl)
    }

    @Test
    fun `isUsingDefaultBaseUrl should reflect whether current baseUrl equals resolved default`() {
        assertTrue(deepSeekDefault.isUsingDefaultBaseUrl())
        assertFalse(deepSeekDefault.copy(baseUrl = "https://api.openai.com/v1").isUsingDefaultBaseUrl())
        assertTrue(ProviderSetting.Claude(baseUrl = "https://api.anthropic.com/v1").isUsingDefaultBaseUrl())
    }
}
