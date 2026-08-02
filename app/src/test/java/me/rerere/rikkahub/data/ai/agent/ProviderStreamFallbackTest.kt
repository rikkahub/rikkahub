package me.rerere.rikkahub.data.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.ProviderStreamErrorCode
import me.rerere.ai.util.ProviderStreamException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderStreamFallbackTest {
    @Test
    fun `client generated tool identities are unique across agent runs`() {
        assertNotEquals(
            clientToolExecutionIdentity("run-one", stepIndex = 0, ordinal = 0),
            clientToolExecutionIdentity("run-two", stepIndex = 0, ordinal = 0),
        )
    }

    @Test
    fun `empty failed stream falls back exactly once`() = runBlocking {
        var fallbackCalls = 0
        val received = mutableListOf<MessageChunk>()

        collectProviderWithFallback(
            stream = { flow { throw streamError(ProviderStreamErrorCode.STREAM_UPSTREAM_FAILURE) } },
            fallback = {
                fallbackCalls += 1
                chunk(UIMessagePart.Text("fallback"))
            },
            onChunk = received::add,
        )

        assertEquals(1, fallbackCalls)
        assertEquals("fallback", received.single().choices.single().delta?.toText())
    }

    @Test
    fun `partial output is surfaced without fallback`() = runBlocking {
        var fallbackCalls = 0
        val received = mutableListOf<MessageChunk>()

        try {
            collectProviderWithFallback(
                stream = {
                    flow {
                        emit(chunk(UIMessagePart.Text("partial")))
                        throw streamError(ProviderStreamErrorCode.STREAM_UPSTREAM_FAILURE)
                    }
                },
                fallback = {
                    fallbackCalls += 1
                    chunk(UIMessagePart.Text("duplicate"))
                },
                onChunk = received::add,
            )
            fail("Expected the partial stream failure")
        } catch (error: RuntimeException) {
            assertTrue(error.message.orEmpty().startsWith("Provider stream failed:"))
        }

        assertEquals(0, fallbackCalls)
        assertEquals("partial", received.single().choices.single().delta?.toText())
    }

    @Test
    fun `cancellation never falls back`() = runBlocking {
        var fallbackCalls = 0

        try {
            collectProviderWithFallback(
                stream = { flow { throw CancellationException("cancelled") } },
                fallback = {
                    fallbackCalls += 1
                    chunk()
                },
                onChunk = {},
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertEquals(0, fallbackCalls)
    }

    @Test
    fun `fallback failure reports the fallback cause`() = runBlocking {
        try {
            collectProviderWithFallback(
                stream = { flow { throw streamError(ProviderStreamErrorCode.STREAM_INCOMPLETE) } },
                fallback = { throw IllegalStateException("HTTP 502 gateway") },
                onChunk = {},
            )
            fail("Expected fallback failure")
        } catch (error: RuntimeException) {
            assertTrue(error.message.orEmpty().contains("after stream fallback"))
            assertTrue(error.message.orEmpty().contains("HTTP 502 gateway"))
        }
    }

    @Test
    fun `meaningful provider progress detects content tools and finish signals`() {
        assertFalse(chunk().hasMeaningfulProviderProgress())
        assertFalse(chunk(finishReason = "unknown").hasMeaningfulProviderProgress())
        assertTrue(chunk(UIMessagePart.Text("answer")).hasMeaningfulProviderProgress())
        assertTrue(chunk(UIMessagePart.Reasoning("thinking")).hasMeaningfulProviderProgress())
        assertTrue(
            chunk(UIMessagePart.Tool("call", "workspace_read_file", "{}"))
                .hasMeaningfulProviderProgress(),
        )
        assertTrue(chunk(finishReason = "stop").hasMeaningfulProviderProgress())
    }

    @Test
    fun `only empty upstream and incomplete streams can fall back`() {
        val upstream = streamError(ProviderStreamErrorCode.STREAM_UPSTREAM_FAILURE)
        val incomplete = streamError(ProviderStreamErrorCode.STREAM_INCOMPLETE)
        val malformed = streamError(ProviderStreamErrorCode.STREAM_MALFORMED_EVENT)
        val backpressure = streamError(ProviderStreamErrorCode.STREAM_BACKPRESSURE_EXCEEDED)

        assertTrue(upstream.canFallbackFromEmptyStream(hasMeaningfulProgress = false))
        assertTrue(incomplete.canFallbackFromEmptyStream(hasMeaningfulProgress = false))
        assertFalse(upstream.canFallbackFromEmptyStream(hasMeaningfulProgress = true))
        assertFalse(malformed.canFallbackFromEmptyStream(hasMeaningfulProgress = false))
        assertFalse(backpressure.canFallbackFromEmptyStream(hasMeaningfulProgress = false))
        assertFalse(RuntimeException("network").canFallbackFromEmptyStream(hasMeaningfulProgress = false))
    }

    @Test
    fun `provider error message includes nested cause and redacts secrets`() {
        val error = ProviderStreamException(
            ProviderStreamErrorCode.STREAM_UPSTREAM_FAILURE,
            "Provider stream failed",
            RuntimeException(
                "HTTP 401 Unauthorized: token=very-secret Authorization: Bearer another-secret",
            ),
        )

        val message = error.asUserFacingProviderFailure("Provider request failed").message.orEmpty()

        assertTrue(message.contains("HTTP 401 Unauthorized"))
        assertTrue(message.contains("token [redacted]"))
        assertFalse(message.contains("very-secret"))
        assertFalse(message.contains("another-secret"))
    }

    private fun chunk(
        part: UIMessagePart? = null,
        finishReason: String? = null,
    ) = MessageChunk(
        id = "chunk",
        model = "model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = part?.let { UIMessage(role = MessageRole.ASSISTANT, parts = listOf(it)) },
                message = null,
                finishReason = finishReason,
            ),
        ),
    )

    private fun streamError(code: ProviderStreamErrorCode) = ProviderStreamException(code, code.name)
}
