package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Issue #271 regression: the non-streaming generateText paths of GoogleProvider and ResponseAPI
 * must resolve non-2xx bodies through parseHttpErrorBody — a typed [HttpException] carrying the
 * provider's structured error message — exactly like ClaudeProvider/ChatCompletionsAPI since
 * 88b043ec. Before the fix they threw a bare Exception embedding the raw JSON dump.
 */
class NonStreamingHttpErrorRoutingTest {

    private val model = Model(modelId = "test-model", displayName = "Test")
    private val params = TextGenerationParams(model = model)
    private val errorBody = """{"error":{"message":"Invalid API key"}}"""

    private fun serveOnce(server: ServerSocket, status: String, body: String): Thread =
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(16384))
                    socket.getOutputStream().run {
                        write(
                            ("HTTP/1.1 $status\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${body.toByteArray().size}\r\n" +
                                "Connection: close\r\n" +
                                "\r\n" +
                                body).toByteArray()
                        )
                        flush()
                    }
                }
            }
        }

    private fun assertTypedError(block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("non-2xx response must throw")
        } catch (e: Exception) {
            assertTrue(
                "expected typed HttpException, got ${e::class.simpleName}: ${e.message}",
                e is HttpException,
            )
            assertTrue(
                "message must carry the parsed provider error, got: ${e.message}",
                e.message.orEmpty().contains("Invalid API key"),
            )
            assertTrue(
                "raw JSON dump must not leak into the message: ${e.message}",
                !e.message.orEmpty().contains("""{"error""""),
            )
        }
    }

    @Test
    fun `GoogleProvider generateText resolves non-2xx through parseHttpErrorBody`() {
        ServerSocket(0).use { server ->
            val stale = serveOnce(server, "400 Bad Request", errorBody)
            val provider = GoogleProvider(client = OkHttpClient())
            assertTypedError {
                provider.generateText(
                    providerSetting = ProviderSetting.Google(
                        baseUrl = "http://127.0.0.1:${server.localPort}",
                        apiKey = "key",
                    ),
                    messages = listOf(UIMessage.user("hi")),
                    params = params,
                )
            }
            stale.interrupt()
        }
    }

    @Test
    fun `ResponseAPI generateText resolves non-2xx through parseHttpErrorBody`() {
        ServerSocket(0).use { server ->
            val stale = serveOnce(server, "400 Bad Request", errorBody)
            val api = ResponseAPI(client = OkHttpClient())
            assertTypedError {
                api.generateText(
                    providerSetting = ProviderSetting.OpenAI(
                        baseUrl = "http://127.0.0.1:${server.localPort}",
                        apiKey = "key",
                        useResponseApi = true,
                    ),
                    messages = listOf(UIMessage.user("hi")),
                    params = params,
                )
            }
            stale.interrupt()
        }
    }
}
