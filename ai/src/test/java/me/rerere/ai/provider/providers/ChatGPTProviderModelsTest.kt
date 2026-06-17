package me.rerere.ai.provider.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for ChatGPT/Codex models parsing. The Codex models endpoint returns
 * `{"models":[{"slug":...,"display_name":...}]}`, NOT the OpenAI `/v1/models` `{"data":[{"id":...}]}`
 * shape. The previous `bodyJson["data"]` / `modelObj["id"]` read silently returned an empty list for
 * the real response, so no models ever appeared. These assertions fail against that old parse.
 */
class ChatGPTProviderModelsTest {

    @Test
    fun parsesRealCodexModelsShape_slugAndDisplayName() {
        val body = """
            {"models":[
              {"slug":"gpt-5.5","display_name":"GPT-5.5","context_window":272000},
              {"slug":"gpt-5.5-codex","display_name":"GPT-5.5-Codex"}
            ]}
        """.trimIndent()
        val models = parseChatGptModels(body)
        assertEquals(2, models.size)
        assertEquals("gpt-5.5", models[0].modelId)
        assertEquals("GPT-5.5", models[0].displayName)
        assertEquals("gpt-5.5-codex", models[1].modelId)
    }

    @Test
    fun missingDisplayName_fallsBackToSlug() {
        val models = parseChatGptModels("""{"models":[{"slug":"gpt-5.5"}]}""")
        assertEquals(1, models.size)
        assertEquals("gpt-5.5", models[0].modelId)
        assertEquals("gpt-5.5", models[0].displayName)
    }

    @Test
    fun entryWithoutSlug_isSkipped() {
        val models = parseChatGptModels("""{"models":[{"display_name":"no slug"},{"slug":"gpt-5.5"}]}""")
        assertEquals(1, models.size)
        assertEquals("gpt-5.5", models[0].modelId)
    }

    @Test
    fun emptyModelList_isEmpty() {
        // An old client_version makes the backend return {"models":[]}.
        assertTrue(parseChatGptModels("""{"models":[]}""").isEmpty())
    }

    @Test
    fun openAiDataIdShape_yieldsEmpty_notRead() {
        // The backend never sends the OpenAI {"data":[{"id":...}]} shape; we must not read it.
        assertTrue(parseChatGptModels("""{"data":[{"id":"gpt-5.5"}]}""").isEmpty())
    }
}
