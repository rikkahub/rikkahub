package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.OpenAIMode
import me.rerere.ai.provider.ProviderSetting
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-style pins for the Standard-vs-Azure OpenAI wire split ([OpenAIWire]). The invariants the
 * Azure mode must not break: Standard URL/auth stay byte-identical, Azure always carries `api-version`
 * + the deployment in the path and uses `api-key` (never `Authorization`), and a default-constructed
 * provider (no mode set) behaves as Standard.
 */
class OpenAIWireTest {

    private fun standard(baseUrl: String = "https://api.openai.com/v1", path: String = "/chat/completions") =
        ProviderSetting.OpenAI(baseUrl = baseUrl, chatCompletionsPath = path)

    private fun azure(baseUrl: String = "https://my-res.openai.azure.com", apiVersion: String = "2024-10-21") =
        ProviderSetting.OpenAI(mode = OpenAIMode.Azure, baseUrl = baseUrl, azureApiVersion = apiVersion)

    @Test
    fun `default provider is Standard, not Azure`() {
        assertFalse(ProviderSetting.OpenAI().isAzure)
        assertEquals(OpenAIMode.Standard, ProviderSetting.OpenAI().mode)
    }

    @Test
    fun `Standard chat URL is baseUrl plus chatCompletionsPath, byte-identical`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            standard().chatCompletionsUrl("gpt-4o"),
        )
        // A proxy remapping the path must still be honored verbatim.
        assertEquals(
            "https://proxy.example/v1/openai/chat",
            standard(baseUrl = "https://proxy.example/v1", path = "/openai/chat").chatCompletionsUrl("gpt-4o"),
        )
    }

    @Test
    fun `Azure chat URL carries the deployment in the path and exactly one api-version`() {
        val url = azure().chatCompletionsUrl("my-deploy")
        assertEquals(
            "https://my-res.openai.azure.com/openai/deployments/my-deploy/chat/completions?api-version=2024-10-21",
            url,
        )
        assertTrue(url.contains("/openai/deployments/my-deploy/chat/completions"))
        assertEquals("exactly one api-version", 1, Regex("api-version=").findAll(url).count())
    }

    @Test
    fun `Azure URL tolerates a trailing slash on the base URL`() {
        assertEquals(
            "https://my-res.openai.azure.com/openai/deployments/d/embeddings?api-version=2024-10-21",
            azure(baseUrl = "https://my-res.openai.azure.com/").azureDeploymentUrl("d", "embeddings"),
        )
    }

    @Test
    fun `Standard auth is Authorization Bearer, never api-key`() {
        val req = Request.Builder().url("https://x.example").applyOpenAIAuth(standard(), "sk-key").build()
        assertEquals("Bearer sk-key", req.header("Authorization"))
        assertNull(req.header("api-key"))
    }

    @Test
    fun `Azure auth is api-key, never Authorization`() {
        val req = Request.Builder().url("https://x.example").applyOpenAIAuth(azure(), "az-key").build()
        assertEquals("az-key", req.header("api-key"))
        assertNull(req.header("Authorization"))
    }
}
