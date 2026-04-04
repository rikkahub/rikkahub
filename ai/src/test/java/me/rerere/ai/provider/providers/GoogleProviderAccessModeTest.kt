package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.GoogleAccessMode
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.UIMessage
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleProviderAccessModeTest {
    @Test
    fun `vertex api key should use publisher endpoints for text stream and image requests`() = runBlocking {
        val interceptor = RecordingInterceptor { request ->
            when {
                request.url.encodedPath.endsWith(":generateContent") -> RecordedResponse(
                    body = """
                        {
                          "candidates": [
                            {
                              "content": {
                                "role": "model",
                                "parts": [{"text": "hello"}]
                              }
                            }
                          ],
                          "usageMetadata": {
                            "promptTokenCount": 1,
                            "candidatesTokenCount": 1,
                            "totalTokenCount": 2
                          }
                        }
                    """.trimIndent()
                )

                request.url.encodedPath.endsWith(":streamGenerateContent") -> RecordedResponse(
                    contentType = "text/event-stream",
                    body = """
                        data: {"candidates":[{"content":{"role":"model","parts":[{"text":"stream"}]}}],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"totalTokenCount":2}}

                    """.trimIndent()
                )

                request.url.encodedPath.endsWith(":predict") -> RecordedResponse(
                    body = """{"predictions":[{"bytesBase64Encoded":"aGVsbG8="}]}"""
                )

                else -> error("Unexpected request path: ${request.url}")
            }
        }
        val provider = GoogleProvider(
            OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build()
        )
        val providerSetting = ProviderSetting.Google(
            name = "Vertex",
            apiKey = "vertex-key",
            vertexAI = true,
            accessMode = GoogleAccessMode.VERTEX_API_KEY
        )

        val textModel = Model(modelId = "gemini-2.5-flash", displayName = "Gemini 2.5 Flash")
        provider.generateText(
            providerSetting = providerSetting,
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(model = textModel)
        )

        withTimeout(3_000) {
            provider.streamText(
                providerSetting = providerSetting,
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = textModel)
            ).collect {}
        }

        provider.generateImage(
            providerSetting = providerSetting,
            params = ImageGenerationParams(
                model = Model(
                    modelId = "gemini-2.5-flash-image",
                    displayName = "Gemini 2.5 Flash Image"
                ),
                prompt = "draw a cat",
                aspectRatio = ImageAspectRatio.SQUARE
            )
        )

        assertEquals(3, interceptor.requests.size)

        val generateRequest = interceptor.requests[0]
        assertEquals("/v1/publishers/google/models/gemini-2.5-flash:generateContent", generateRequest.url.encodedPath)
        assertEquals("vertex-key", generateRequest.url.queryParameter("key"))
        assertFalse(generateRequest.headers.names().contains("x-goog-api-key"))
        assertFalse(generateRequest.headers.names().contains("Authorization"))

        val streamRequest = interceptor.requests[1]
        assertEquals("/v1/publishers/google/models/gemini-2.5-flash:streamGenerateContent", streamRequest.url.encodedPath)
        assertEquals("vertex-key", streamRequest.url.queryParameter("key"))
        assertEquals("sse", streamRequest.url.queryParameter("alt"))

        val imageRequest = interceptor.requests[2]
        assertEquals("/v1/publishers/google/models/gemini-2.5-flash-image:predict", imageRequest.url.encodedPath)
        assertEquals("vertex-key", imageRequest.url.queryParameter("key"))
    }

    @Test
    fun `gemini api should keep x goog api key header on model listing`() = runBlocking {
        val interceptor = RecordingInterceptor {
            RecordedResponse(
                body = """
                    {
                      "models": [
                        {
                          "name": "models/gemini-2.5-flash",
                          "displayName": "Gemini 2.5 Flash",
                          "supportedGenerationMethods": ["generateContent"]
                        }
                      ]
                    }
                """.trimIndent()
            )
        }
        val provider = GoogleProvider(
            OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build()
        )
        val providerSetting = ProviderSetting.Google(
            name = "Gemini",
            apiKey = "gem-key",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            accessMode = GoogleAccessMode.GEMINI_API
        )

        val models = provider.listModels(providerSetting)

        assertEquals(1, models.size)
        val request = interceptor.requests.single()
        assertEquals("/v1beta/models", request.url.encodedPath)
        assertEquals("100", request.url.queryParameter("pageSize"))
        assertEquals("gem-key", request.headers["x-goog-api-key"])
        assertEquals(null, request.url.queryParameter("key"))
    }

    @Test
    fun `vertex model listing should fall back to local Gemini candidates when remote fetch fails`() = runBlocking {
        val interceptor = RecordingInterceptor {
            RecordedResponse(
                code = 403,
                body = """{"error":{"message":"permission denied"}}"""
            )
        }
        val provider = GoogleProvider(
            OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build()
        )
        val providerSetting = ProviderSetting.Google(
            name = "Vertex",
            apiKey = "vertex-key",
            vertexAI = true,
            accessMode = GoogleAccessMode.VERTEX_API_KEY
        )

        val models = provider.listModels(providerSetting)

        assertTrue(models.any { it.modelId == "gemini-2.5-flash" })
        assertTrue(models.any { it.modelId == "gemini-2.5-flash-image" && it.type == ModelType.IMAGE })
        val request = interceptor.requests.single()
        assertEquals("/v1beta1/publishers/google/models", request.url.encodedPath)
        assertEquals("vertex-key", request.url.queryParameter("key"))
        assertEquals("true", request.url.queryParameter("listAllVersions"))
    }

    private data class RecordedResponse(
        val code: Int = 200,
        val body: String,
        val contentType: String = "application/json",
    )

    private data class CapturedRequest(
        val url: okhttp3.HttpUrl,
        val headers: Headers,
    )

    private class RecordingInterceptor(
        private val responder: (Request) -> RecordedResponse,
    ) : Interceptor {
        val requests = mutableListOf<CapturedRequest>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += CapturedRequest(
                url = request.url,
                headers = request.headers,
            )
            val response = responder(request)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(response.code)
                .message(if (response.code in 200..299) "OK" else "ERROR")
                .body(response.body.toResponseBody(response.contentType.toMediaType()))
                .build()
        }
    }
}
