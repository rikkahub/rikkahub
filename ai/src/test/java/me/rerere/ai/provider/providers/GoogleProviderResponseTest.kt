package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * Unit tests for GoogleProvider non-streaming generateContent response parsing.
 *
 * Regression coverage for issue #11: a HTTP 200 response that omits `candidates`
 * (safety-blocked prompt yielding only promptFeedback) or omits `usageMetadata`
 * must not throw a raw NullPointerException. The previous inline code force-unwrapped
 * both fields with `!!`, crashing opaquely instead of surfacing the block reason.
 */
class GoogleProviderResponseTest {

    private lateinit var provider: GoogleProvider

    @Before
    fun setUp() {
        provider = GoogleProvider(OkHttpClient())
    }

    /**
     * Builds a GoogleProvider whose HTTP client short-circuits every call to a
     * canned 200 response, so generateText runs its full real code path without
     * hitting the network. This is the public-path entry that force-unwrapped
     * `candidates!!` on the unfixed code — the exact crash in issue #11.
     */
    private fun providerReturning(body: String): GoogleProvider {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
        return GoogleProvider(client)
    }

    private fun googleSetting() = ProviderSetting.Google(apiKey = "test-key")

    private fun params() = TextGenerationParams(model = Model(modelId = "gemini-test"))

    @Test
    fun `generateText surfaces safety block as HttpException not NPE - issue 11 public path`() {
        // Gemini returns HTTP 200 with only promptFeedback for a safety-blocked prompt:
        // no candidates, no usageMetadata. The unfixed generateText did
        // `bodyJson["candidates"]!!.jsonArray`, NPEing here. The fix must throw
        // HttpException carrying the block reason. This drives generateText end to end.
        val provider = providerReturning("""{ "promptFeedback": { "blockReason": "SAFETY" } }""")
        try {
            runBlocking {
                provider.generateText(googleSetting(), emptyList(), params())
            }
            fail("Expected HttpException for safety-blocked prompt, but no exception was thrown")
        } catch (e: HttpException) {
            assertTrue(
                "Message should carry the block reason, was: ${e.message}",
                e.message?.contains("SAFETY") == true
            )
        } catch (e: NullPointerException) {
            fail("Regressed to issue #11: raw NullPointerException instead of HttpException (${e.message})")
        }
    }

    private fun parse(jsonStr: String, modelId: String = "gemini-test"): MessageChunk {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "parseGenerateContentResponse",
            JsonObject::class.java,
            String::class.java
        )
        method.isAccessible = true
        val bodyJson = json.parseToJsonElement(jsonStr) as JsonObject
        try {
            return method.invoke(provider, bodyJson, modelId) as MessageChunk
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    @Test
    fun `safety-blocked feedback-only 200 response throws HttpException not NPE`() {
        // Gemini returns 200 with only promptFeedback for a blocked prompt: no candidates, no usageMetadata
        val body = """{ "promptFeedback": { "blockReason": "SAFETY" } }"""
        try {
            parse(body)
            fail("Expected HttpException for blocked prompt, but no exception was thrown")
        } catch (e: HttpException) {
            assertTrue(
                "Message should carry the block reason, was: ${e.message}",
                e.message?.contains("SAFETY") == true
            )
        } catch (e: NullPointerException) {
            fail("Regressed: raw NullPointerException instead of HttpException (${e.message})")
        }
    }

    @Test
    fun `200 response with no candidates and no feedback throws HttpException not NPE`() {
        // Genuinely empty / unexpected body: surface server payload, never NPE
        val body = """{ "error": { "message": "internal" } }"""
        try {
            parse(body)
            fail("Expected HttpException for empty body, but no exception was thrown")
        } catch (e: HttpException) {
            assertTrue(
                "Message should carry server detail, was: ${e.message}",
                e.message?.contains("internal") == true
            )
        } catch (e: NullPointerException) {
            fail("Regressed: raw NullPointerException instead of HttpException (${e.message})")
        }
    }

    // The second `!!` the fix removed was on usageMetadata: a 200 response with
    // candidates but no usageMetadata crashed at `bodyJson["usageMetadata"]!!`.
    // parseUsageMeta(null) must return null instead of NPEing. (We cannot drive the
    // full candidate path here because parseMessage calls android.util.Log, which is
    // not mocked in :ai unit tests — only JUnit is on the test classpath.)
    private fun invokeParseUsageMeta(jsonObject: JsonObject?) =
        GoogleProvider::class.java.getDeclaredMethod("parseUsageMeta", JsonObject::class.java)
            .apply { isAccessible = true }
            .invoke(provider, jsonObject)

    @Test
    fun `parseUsageMeta returns null for missing usageMetadata - no NPE`() {
        assertNull(
            "missing usageMetadata must yield null usage, not crash",
            invokeParseUsageMeta(null)
        )
    }

    @Test
    fun `parseUsageMeta parses present usageMetadata`() {
        val usage = json.parseToJsonElement(
            """{ "promptTokenCount": 10, "candidatesTokenCount": 5, "totalTokenCount": 15 }"""
        ) as JsonObject
        val result = invokeParseUsageMeta(usage)
        assertTrue("usage should be parsed", result != null)
    }
}
