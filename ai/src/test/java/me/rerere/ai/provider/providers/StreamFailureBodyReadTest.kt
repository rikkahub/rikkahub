package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.StreamEndedBeforeFirstFrameException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.internal.http.RealResponseBody
import okio.Buffer
import okio.Timeout
import okio.buffer
import okio.Source
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Regression test for stream onFailure body-read exceptions across providers.
 *
 * Bug: an IOException during `response.body.string()` in onFailure could escape before
 * `close(exception)` runs, leaving the stream to close successfully (no terminal Throwable).
 * The harness simulates a non-idempotent pre-frame 500 failure with a throwing body and
 * asserts the downstream terminates with a non-null terminal error.
 */
class StreamFailureBodyReadTest {

    private val messages = listOf(UIMessage.user("Hello from regression test"))
    private val params = TextGenerationParams(model = Model(modelId = "gpt-4o"))

    private fun failingStreamingClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Upstream failure")
                .body(RealResponseBody("application/json", 0, ThrowingSource().buffer()))
                .build()
        })
        .build()

    private fun collectFailureFrom(streamFactory: suspend () -> Flow<MessageChunk>): Throwable? {
        return runBlocking {
            val flow = streamFactory()
            runCatching {
                withTimeout(2_000) {
                    flow.collect {
                        throw AssertionError("stream should not emit chunks on terminal failure")
                    }
                }
            }.exceptionOrNull()
        }
    }

    private fun assertFailureIsNonNullTerminal(failure: Throwable?) {
        assertNotNull("non-idempotent pre-frame failure must terminate", failure)
        assertTrue(
            "seeded terminal error should be preserved",
            failure is StreamEndedBeforeFirstFrameException,
        )
    }

    @Test
    fun `ResponseAPI onFailure body-read IOException still closes with seeded terminal error`() {
        val provider = ResponseAPI(client = failingStreamingClient())
        val failure = collectFailureFrom {
            provider.streamText(
                providerSetting = ProviderSetting.OpenAI(baseUrl = "https://example.invalid/v1"),
                messages = messages,
                params = params,
            )
        }

        assertFailureIsNonNullTerminal(failure)
    }

    @Test
    fun `ClaudeProvider onFailure body-read IOException still closes with seeded terminal error`() {
        val provider = ClaudeProvider(client = failingStreamingClient())
        val failure = collectFailureFrom {
            provider.streamText(
                providerSetting = ProviderSetting.Claude(baseUrl = "https://example.invalid/v1"),
                messages = messages,
                params = params,
            )
        }

        assertFailureIsNonNullTerminal(failure)
    }

    @Test
    fun `ChatGPTProvider onFailure body-read IOException still closes with seeded terminal error`() {
        val provider = ChatGPTProvider(client = failingStreamingClient(), context = null)
        val failure = collectFailureFrom {
            provider.streamText(
                providerSetting = ProviderSetting.ChatGPT(
                    baseUrl = "https://chatgpt.com/backend-api/codex",
                    accessToken = "not-a-valid-jwt",
                ),
                messages = messages,
                params = params,
            )
        }

        assertFailureIsNonNullTerminal(failure)
    }

    private class ThrowingSource : Source {
        override fun read(sink: Buffer, byteCount: Long): Long {
            throw IOException("body read must not crash callback close semantics")
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            // no-op
        }
    }
}
