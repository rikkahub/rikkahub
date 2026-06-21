package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.ANTIGRAVITY_IMAGE_MODEL_ID
import me.rerere.ai.provider.providers.codexImageModels
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptGatingTest {

    private val codexImageModelId = codexImageModels().single().modelId

    private fun chatgpt(
        token: String = "sk-codex-tok",
        enabled: Boolean = true,
    ) = ProviderSetting.ChatGPT(
        name = "ChatGPT",
        enabled = enabled,
        accessToken = token,
    )

    private fun google() = ProviderSetting.Google(
        name = "Gemini",
        antigravity = true,
        antigravityRefreshToken = "refresh-tok",
    )

    private fun settings(vararg providers: ProviderSetting) = Settings(providers = providers.toList())

    @Test
    fun `hasChatGpt true only when enabled chatgpt with a token`() {
        assertTrue(settings(chatgpt()).hasChatGpt())
        assertFalse("blank token", settings(chatgpt(token = "")).hasChatGpt())
        assertFalse("disabled", settings(chatgpt(enabled = false)).hasChatGpt())
        assertFalse("no providers", settings().hasChatGpt())
        assertFalse("only openai", settings(ProviderSetting.OpenAI(name = "x")).hasChatGpt())
    }

    @Test
    fun `chatGptAccessToken returns the configured token or null`() {
        assertEquals("sk-codex-tok", settings(chatgpt()).chatGptAccessToken())
        assertNull(settings(chatgpt(token = "")).chatGptAccessToken())
    }

    @Test
    fun `withChatGptImageModels adds the IMAGE model to the chatgpt provider only`() {
        val openai = ProviderSetting.OpenAI(name = "x")
        val out = settings(chatgpt(), openai).withChatGptImageModels()

        val cg = out.first { it is ProviderSetting.ChatGPT }
        val img = cg.models.singleOrNull { it.modelId == codexImageModelId }
        assertTrue("chatgpt provider gains the image model", img != null)
        assertEquals(ModelType.IMAGE, img!!.type)

        assertTrue(out.first { it is ProviderSetting.OpenAI }.models.isEmpty())
    }

    @Test
    fun `image model surfaces even when a chat model shares the driver modelId`() {
        // The codex image model's modelId is the driver slug (e.g. "gpt-5.5"), which a normal chat
        // model also uses. Dedup must be by Model.id, not modelId, or the image model gets dropped.
        val cg = ProviderSetting.ChatGPT(
            name = "ChatGPT",
            accessToken = "sk-codex-tok",
            models = listOf(Model(modelId = codexImageModelId, displayName = "GPT-5.5")),
        )
        val out = settings(cg).withChatGptImageModels()
        val cgOut = out.first { it is ProviderSetting.ChatGPT }
        assertTrue(
            "image model present despite a chat model sharing the modelId",
            cgOut.models.any { it.type == ModelType.IMAGE && it.modelId == codexImageModelId },
        )
    }

    @Test
    fun `two chatgpt providers do not duplicate the fixed-id image model`() {
        val out = settings(chatgpt(), chatgpt()).withChatGptImageModels()
        val total = out.flatMap { it.models }.count { it.modelId == codexImageModelId }
        assertEquals("image model added to exactly one provider", 1, total)
    }

    @Test
    fun `withChatGptImageModels is idempotent and a no-op without chatgpt`() {
        val once = settings(chatgpt()).withChatGptImageModels()
        val twice = Settings(providers = once).withChatGptImageModels()
        val cg = twice.first { it is ProviderSetting.ChatGPT }
        assertEquals(1, cg.models.count { it.modelId == codexImageModelId })

        val plain = settings(ProviderSetting.OpenAI(name = "x")).withChatGptImageModels()
        assertTrue(plain.first { it is ProviderSetting.OpenAI }.models.isEmpty())
    }

    @Test
    fun `withManagedImageModels surfaces both gagy and codex image models`() {
        val out = settings(google(), chatgpt()).withManagedImageModels()
        val ids = out.flatMap { it.models }.map { it.modelId }.toSet()
        assertTrue("gagy image model present", ANTIGRAVITY_IMAGE_MODEL_ID in ids)
        assertTrue("codex image model present", codexImageModelId in ids)
    }

    @Test
    fun `resolveSearchOptions injects the access token for Codex Search and passes others through`() {
        val withCg = settings(chatgpt())
        val resolved = withCg.resolveSearchOptions(SearchServiceOptions.CodexSearchOptions())
        assertEquals("sk-codex-tok", (resolved as SearchServiceOptions.CodexSearchOptions).accessToken)

        val exa = SearchServiceOptions.ExaOptions(apiKey = "k")
        assertEquals(exa, withCg.resolveSearchOptions(exa))
    }
}
