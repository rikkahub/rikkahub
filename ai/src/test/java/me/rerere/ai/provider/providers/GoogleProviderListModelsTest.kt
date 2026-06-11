package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for GoogleProvider.listModels force-unwrapping fields from third-party JSON
 * (issue #241). A Gemini-compatible proxy that omits `displayName` (or `name` /
 * `supportedGenerationMethods`) for one model previously NPE'd via `!!`, failing the entire
 * listModels call even though the surrounding mapNotNull is designed to skip bad entries.
 *
 * Fix (mirrors ClaudeProvider.listModels): missing required fields skip the entry
 * (return@mapNotNull null); a missing displayName falls back to the model id (a model with a valid
 * id is still usable).
 */
class GoogleProviderListModelsTest {

    @Test
    fun `malformed model entry is skipped, displayName falls back to id, rest returned`() {
        val body = """
            {
              "models": [
                {
                  "name": "models/gemini-no-display",
                  "supportedGenerationMethods": ["generateContent"]
                },
                {
                  "name": "models/gemini-no-methods",
                  "displayName": "No Methods"
                },
                {
                  "name": "models/gemini-valid",
                  "displayName": "Gemini Valid",
                  "supportedGenerationMethods": ["generateContent"]
                }
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

        val provider = GoogleProvider(client = client)

        val models = runBlocking {
            provider.listModels(ProviderSetting.Google(apiKey = "test"))
        }

        assertTrue("listModels must not throw and must return surviving entries", models.isNotEmpty())

        val byId = models.associateBy { it.modelId }

        // Missing-methods entry is skipped (neither generateContent nor embedContent).
        assertNull(byId["gemini-no-methods"])

        // Missing-displayName entry survives with displayName == modelId.
        val noDisplay = byId.getValue("gemini-no-display")
        assertEquals("gemini-no-display", noDisplay.displayName)

        // Fully valid entry present.
        val valid = byId.getValue("gemini-valid")
        assertEquals("Gemini Valid", valid.displayName)
    }
}
