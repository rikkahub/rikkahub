package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the bug "discovered embedding models are typed CHAT, so they never appear as
 * embedding models". The fix is at [OpenAIProvider.listModels] (OpenAIProvider.kt:86), which must
 * infer the [ModelType] per discovered id via [me.rerere.ai.registry.guessModelType] rather than
 * hard-coding CHAT. The pure guessModelType unit tests do not guard this wiring: deleting that line
 * leaves them green while this test fails.
 */
class OpenAIProviderListModelsTest {

    @Test
    fun `discovered models are typed by id`() {
        val body = """
            {
              "data": [
                { "id": "text-embedding-3-small" },
                { "id": "gpt-4o" }
              ]
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody())
                    .build()
            }
            .build()

        val provider = OpenAIProvider(client = client)

        val models = runBlocking {
            provider.listModels(
                providerSetting = ProviderSetting.OpenAI(baseUrl = "https://example.invalid/v1"),
            )
        }

        val byId = models.associateBy { it.modelId }
        assertEquals(ModelType.EMBEDDING, byId.getValue("text-embedding-3-small").type)
        assertEquals(ModelType.CHAT, byId.getValue("gpt-4o").type)
    }
}
