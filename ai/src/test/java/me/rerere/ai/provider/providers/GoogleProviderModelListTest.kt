package me.rerere.ai.provider.providers

import me.rerere.ai.provider.ModelType
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleProviderModelListTest {

    @Test
    fun `native Gemini model list should keep chat and embedding models`() {
        val models = parseGoogleModelList(
            """
            {
              "models": [
                {
                  "name": "models/gemini-2.5-flash",
                  "displayName": "Gemini 2.5 Flash",
                  "supportedGenerationMethods": ["generateContent"]
                },
                {
                  "name": "publishers/google/models/text-embedding-004",
                  "displayName": "Text Embedding 004",
                  "supportedGenerationMethods": ["embedContent"]
                },
                {
                  "name": "models/veo-3",
                  "displayName": "Veo 3",
                  "supportedGenerationMethods": ["generateVideo"]
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("gemini-2.5-flash", "text-embedding-004"), models.map { it.modelId })
        assertEquals(listOf("Gemini 2.5 Flash", "Text Embedding 004"), models.map { it.displayName })
        assertEquals(listOf(ModelType.CHAT, ModelType.EMBEDDING), models.map { it.type })
    }

    @Test
    fun `new-api Gemini style model list should fall back when generation methods are absent`() {
        val models = parseGoogleModelList(
            """
            {
              "models": [
                {
                  "name": "gpt-5",
                  "displayName": "gpt-5",
                  "supportedGenerationMethods": null
                },
                {
                  "name": "text-embedding-3-large",
                  "displayName": null,
                  "supportedGenerationMethods": null
                },
                {
                  "name": "gpt-5-codex"
                }
              ],
              "nextPageToken": null
            }
            """.trimIndent()
        )

        assertEquals(listOf("gpt-5", "text-embedding-3-large", "gpt-5-codex"), models.map { it.modelId })
        assertEquals(listOf("gpt-5", "text-embedding-3-large", "gpt-5-codex"), models.map { it.displayName })
        assertEquals(
            listOf(ModelType.CHAT, ModelType.EMBEDDING, ModelType.CHAT),
            models.map { it.type }
        )
    }
}
