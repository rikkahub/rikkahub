package me.rerere.rikkahub

import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.components.ui.decodeProviderSettings
import me.rerere.rikkahub.ui.components.ui.decodeProviderSetting
import me.rerere.rikkahub.ui.components.ui.encodeForShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ShareSheetTest {
    @Test
    fun `decode should restore OpenAI provider correctly`() {
        val originalId = Uuid.random()
        val original = ProviderSetting.OpenAI(
            id = originalId,
            enabled = true,
            name = "Test OpenAI",
            models = listOf(
                Model(
                    id = Uuid.random(),
                    displayName = "gpt-4",
                )
            ),
            apiKey = "sk-test-key",
            baseUrl = "https://api.openai.com/v1",
            chatCompletionsPath = "/chat/completions",
            useResponseApi = false,
            balanceOption = BalanceOption(enabled = false)
        )

        val encoded = original.encodeForShare()
        val decoded = decodeProviderSetting(encoded)

        assertTrue(decoded is ProviderSetting.OpenAI)
        val decodedOpenAI = decoded as ProviderSetting.OpenAI
        assertEquals(originalId, decodedOpenAI.id)
        assertEquals("Test OpenAI", decodedOpenAI.name)
        assertEquals("sk-test-key", decodedOpenAI.apiKey)
        assertEquals("https://api.openai.com/v1", decodedOpenAI.baseUrl)
        // encodeForShare intentionally strips models
        assertTrue(decodedOpenAI.models.isEmpty())
    }

    @Test
    fun `decode should restore Google provider correctly`() {
        val originalId = Uuid.random()
        val original = ProviderSetting.Google(
            id = originalId,
            enabled = true,
            name = "Test Google",
            models = emptyList(),
            apiKey = "test-google-key",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            vertexAI = false
        )

        val encoded = original.encodeForShare()
        val decoded = decodeProviderSetting(encoded)

        assertTrue(decoded is ProviderSetting.Google)
        val decodedGoogle = decoded as ProviderSetting.Google
        assertEquals(originalId, decodedGoogle.id)
        assertEquals("Test Google", decodedGoogle.name)
        assertEquals("test-google-key", decodedGoogle.apiKey)
        assertEquals(false, decodedGoogle.vertexAI)
    }

    @Test
    fun `decode should restore Claude provider correctly`() {
        val originalId = Uuid.random()
        val original = ProviderSetting.Claude(
            id = originalId,
            enabled = false,
            name = "Test Claude",
            models = emptyList(),
            apiKey = "test-claude-key",
            baseUrl = "https://api.anthropic.com/v1"
        )

        val encoded = original.encodeForShare()
        val decoded = decodeProviderSetting(encoded)

        assertTrue(decoded is ProviderSetting.Claude)
        val decodedClaude = decoded as ProviderSetting.Claude
        assertEquals(originalId, decodedClaude.id)
        assertEquals("Test Claude", decodedClaude.name)
        assertEquals("test-claude-key", decodedClaude.apiKey)
        assertEquals(false, decodedClaude.enabled)
    }

    @Test
    fun `decode should handle balance option`() {
        val original = ProviderSetting.OpenAI(
            id = Uuid.random(),
            enabled = true,
            name = "Test with Balance",
            models = emptyList(),
            apiKey = "test-key",
            baseUrl = "https://api.test.com",
            balanceOption = BalanceOption(
                enabled = true,
                apiPath = "/custom/credits",
                resultPath = "data.balance"
            )
        )

        val encoded = original.encodeForShare()
        val decoded = decodeProviderSetting(encoded) as ProviderSetting.OpenAI

        assertEquals(true, decoded.balanceOption.enabled)
        assertEquals("/custom/credits", decoded.balanceOption.apiPath)
        assertEquals("data.balance", decoded.balanceOption.resultPath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode should throw exception for invalid prefix`() {
        decodeProviderSetting("invalid-string")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode should throw exception for wrong version`() {
        decodeProviderSetting("ai-provider:v3:somedata")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode should throw exception for invalid base64`() {
        decodeProviderSetting("ai-provider:v1:not-valid-base64!!!")
    }

    @Test
    fun `encode and decode should be reversible`() {
        val providers = listOf(
            ProviderSetting.OpenAI(
                name = "OpenAI Test",
                apiKey = "key1",
                baseUrl = "url1"
            ),
            ProviderSetting.Google(
                name = "Google Test",
                apiKey = "key2",
                vertexAI = true
            ),
            ProviderSetting.Claude(
                name = "Claude Test",
                apiKey = "key3"
            )
        )

        providers.forEach { original ->
            val encoded = original.encodeForShare()
            val decoded = decodeProviderSetting(encoded)

            assertEquals(original.id, decoded.id)
            assertEquals(original.name, decoded.name)
            assertEquals(original.enabled, decoded.enabled)
        }
    }

    @Test
    fun `decodeProviderSettings should keep legacy single share format compatible`() {
        val original = ProviderSetting.OpenAI(
            name = "Legacy OpenAI",
            apiKey = "legacy-key"
        )

        val encoded = original.encodeForShare()
        val decoded = decodeProviderSettings(encoded)

        assertEquals(1, decoded.size)
        assertTrue(decoded.single() is ProviderSetting.OpenAI)
        assertEquals(original.name, decoded.single().name)
    }

    @Test
    fun `multi provider share should round trip`() {
        val original = listOf(
            ProviderSetting.OpenAI(
                name = "OpenAI Batch",
                apiKey = "key-1",
                models = listOf(
                    Model(
                        id = Uuid.random(),
                        displayName = "gpt-5"
                    )
                )
            ),
            ProviderSetting.Google(
                name = "Google Batch",
                apiKey = "key-2",
                vertexAI = true
            ),
            ProviderSetting.Claude(
                name = "Claude Batch",
                apiKey = "key-3",
                promptCaching = true
            )
        )

        val encoded = original.encodeForShare()
        val decoded = decodeProviderSettings(encoded)

        assertEquals(3, decoded.size)
        assertEquals(original.map { it.name }, decoded.map { it.name })
        assertTrue(decoded.all { it.models.isEmpty() })
        assertTrue((decoded[1] as ProviderSetting.Google).vertexAI)
        assertTrue((decoded[2] as ProviderSetting.Claude).promptCaching)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decodeProviderSetting should reject multi provider share payload`() {
        val encoded = listOf(
            ProviderSetting.OpenAI(name = "OpenAI"),
            ProviderSetting.Claude(name = "Claude")
        ).encodeForShare()

        decodeProviderSetting(encoded)
    }
}
