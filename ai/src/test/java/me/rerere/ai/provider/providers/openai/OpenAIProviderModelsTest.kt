package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.OPENAI_CODEX_BASE_URL
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAIProviderModelsTest {
    @Test
    fun `Codex subscription model URL includes client version`() {
        val setting = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            baseUrl = OPENAI_CODEX_BASE_URL,
        )

        val url = openAIModelsUrl(setting)

        assertEquals("$OPENAI_CODEX_BASE_URL/models", url.toString().substringBefore('?'))
        assertEquals("0.148.0", url.queryParameter("client_version"))
    }

    @Test
    fun `API key model URL keeps standard shape`() {
        val setting = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.API_KEY,
            baseUrl = "https://api.openai.com/v1",
        )

        val url = openAIModelsUrl(setting)

        assertEquals("https://api.openai.com/v1/models", url.toString())
        assertNull(url.queryParameter("client_version"))
    }

    @Test
    fun `Codex model response uses visible API supported slugs`() {
        val models = parseOpenAIModels(
            """
            {
              "models": [
                {
                  "slug": "gpt-5.6-sol",
                  "display_name": "GPT-5.6 Sol",
                  "supported_in_api": true,
                  "visibility": "list"
                },
                {
                  "slug": "hidden-model",
                  "supported_in_api": true,
                  "visibility": "hide"
                },
                {
                  "slug": "unsupported-model",
                  "supported_in_api": false,
                  "visibility": "list"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, models.size)
        assertEquals("gpt-5.6-sol", models.single().modelId)
        assertEquals("GPT-5.6 Sol", models.single().displayName)
    }
}
