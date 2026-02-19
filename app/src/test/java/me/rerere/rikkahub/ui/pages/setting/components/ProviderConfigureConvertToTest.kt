package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderConfigureConvertToTest {
    @Test
    fun `convertTo should keep common fields and switch official endpoint to target default`() {
        val model = Model(
            id = Uuid.random(),
            modelId = "gpt-custom",
            displayName = "GPT Custom"
        )
        val balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/custom/credits",
            resultPath = "data.balance"
        )
        val original = ProviderSetting.OpenAI(
            id = Uuid.random(),
            enabled = false,
            name = "My Provider",
            models = listOf(model),
            balanceOption = balanceOption,
            apiKey = "sk-test",
            baseUrl = "https://api.openai.com/v1"
        )

        val converted = original.convertTo(ProviderSetting.Google::class)
        assertTrue(converted is ProviderSetting.Google)
        val google = converted as ProviderSetting.Google

        assertEquals(original.id, google.id)
        assertEquals(original.enabled, google.enabled)
        assertEquals(original.name, google.name)
        assertEquals(original.models, google.models)
        assertEquals(original.balanceOption, google.balanceOption)
        assertEquals(original.apiKey, google.apiKey)
        assertEquals("https://generativelanguage.googleapis.com/v1beta", google.baseUrl)
    }

    @Test
    fun `convertTo should preserve third-party host and replace version suffix`() {
        val original = ProviderSetting.OpenAI(
            name = "Proxy OpenAI",
            apiKey = "proxy-key",
            baseUrl = "https://gateway.example.com/api/v1"
        )

        val converted = original.convertTo(ProviderSetting.Google::class) as ProviderSetting.Google
        assertEquals("https://gateway.example.com/api/v1beta", converted.baseUrl)
        assertEquals("gateway.example.com", converted.baseUrl.toHttpUrlOrNull()?.host)
    }

    @Test
    fun `convertTo should preserve third-party host and append target path when needed`() {
        val original = ProviderSetting.Google(
            name = "Proxy Google",
            apiKey = "proxy-google-key",
            baseUrl = "https://proxy.example.com/vendor/gemini"
        )

        val converted = original.convertTo(ProviderSetting.OpenAI::class) as ProviderSetting.OpenAI
        assertEquals("https://proxy.example.com/vendor/gemini/v1", converted.baseUrl)
        assertEquals("proxy.example.com", converted.baseUrl.toHttpUrlOrNull()?.host)
    }

    @Test
    fun `convertTo should preserve third-party host when switching to claude`() {
        val original = ProviderSetting.OpenAI(
            name = "Proxy OpenAI",
            apiKey = "proxy-key",
            baseUrl = "https://gateway.example.com/proxy/v1beta"
        )

        val converted = original.convertTo(ProviderSetting.Claude::class) as ProviderSetting.Claude
        assertEquals("https://gateway.example.com/proxy/v1", converted.baseUrl)
        assertEquals("gateway.example.com", converted.baseUrl.toHttpUrlOrNull()?.host)
    }

    @Test
    fun `convertTo should return same instance for same type`() {
        val original = ProviderSetting.OpenAI(
            name = "Same Type",
            apiKey = "same-key",
            baseUrl = "https://api.openai.com/v1"
        )

        val converted = original.convertTo(ProviderSetting.OpenAI::class)
        assertSame(original, converted)
    }

    @Test
    fun `convertTo should keep original base url when source url is invalid`() {
        val original = ProviderSetting.Claude(
            name = "Invalid URL Provider",
            apiKey = "invalid-key",
            baseUrl = "not-a-url"
        )

        val converted = original.convertTo(ProviderSetting.OpenAI::class) as ProviderSetting.OpenAI
        assertEquals("not-a-url", converted.baseUrl)
    }
}
