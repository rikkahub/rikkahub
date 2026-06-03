package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Regression test for issue #14: a non-2xx HTTP response from the /embeddings endpoint must surface
 * as an [IOException], not an [IllegalStateException]. The sole production caller
 * (KnowledgeRetrievalTransformer) degrades RAG to best-effort by catching only [IOException]; an HTTP
 * status failure (429/401/5xx) is an external-service / I/O failure and must be classified as such so
 * it is absorbed instead of hard-aborting the user's chat turn.
 */
class OpenAIProviderEmbeddingErrorTest {

    @Test
    fun `non-2xx embedding response throws IOException`() {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("{\"error\":\"rate limited\"}".toResponseBody())
                    .build()
            }
            .build()

        val provider = OpenAIProvider(client = client)

        val thrown = runCatching {
            runBlocking {
                provider.generateEmbedding(
                    providerSetting = ProviderSetting.OpenAI(baseUrl = "https://example.invalid/v1"),
                    params = EmbeddingGenerationParams(
                        model = Model(modelId = "text-embedding-3-small"),
                        input = listOf("query"),
                    ),
                )
            }
        }.exceptionOrNull()

        assertTrue(
            "expected IOException but got ${thrown?.let { it::class.java.name }}: ${thrown?.message}",
            thrown is IOException,
        )
        assertTrue(
            "error message should retain the HTTP status code",
            thrown?.message?.contains("429") == true,
        )
    }
}
