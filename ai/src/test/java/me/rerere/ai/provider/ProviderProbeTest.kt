package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Pure-function tests for the probe body/transport classifiers. The HTTP plumbing
 * (runModelListProbe / runChatProbe) is thin glue over these and OkHttp, and is exercised by the
 * connection-classifier property tests + on-device QA; here we lock the shape decisions that decide
 * ProviderError vs JsonWrongShape vs NotJson, and the transport-error taxonomy.
 */
class ProviderProbeTest {

    @Test
    fun `provider-shaped error object is ProviderError`() {
        assertEquals(
            ProbeOutcome.Body.ProviderError,
            bodyShapeOf("""{"error":{"message":"invalid api key","type":"auth"}}"""),
        )
    }

    @Test
    fun `bare message-or-detail-or-description fields are ProviderError`() {
        assertEquals(ProbeOutcome.Body.ProviderError, bodyShapeOf("""{"message":"nope"}"""))
        assertEquals(ProbeOutcome.Body.ProviderError, bodyShapeOf("""{"detail":"nope"}"""))
        assertEquals(ProbeOutcome.Body.ProviderError, bodyShapeOf("""{"description":"nope"}"""))
    }

    @Test
    fun `json object without an error field is JsonWrongShape`() {
        assertEquals(ProbeOutcome.Body.JsonWrongShape, bodyShapeOf("""{"foo":1,"bar":"x"}"""))
        assertEquals(ProbeOutcome.Body.JsonWrongShape, bodyShapeOf("""{}"""))
    }

    @Test
    fun `json that is not an object is JsonWrongShape`() {
        assertEquals(ProbeOutcome.Body.JsonWrongShape, bodyShapeOf("""[1,2,3]"""))
        assertEquals(ProbeOutcome.Body.JsonWrongShape, bodyShapeOf("""42"""))
    }

    @Test
    fun `non-json bodies are NotJson`() {
        assertEquals(ProbeOutcome.Body.NotJson, bodyShapeOf("<html><body>502 Bad Gateway</body></html>"))
        assertEquals(ProbeOutcome.Body.NotJson, bodyShapeOf(""))
        assertEquals(ProbeOutcome.Body.NotJson, bodyShapeOf("not json at all"))
    }

    @Test
    fun `transport throwables map to their taxonomy entry`() {
        assertEquals(ProbeOutcome.TransportError.DNS, UnknownHostException("x").toTransportError())
        assertEquals(ProbeOutcome.TransportError.TIMEOUT, SocketTimeoutException("x").toTransportError())
        assertEquals(ProbeOutcome.TransportError.CONNECT, ConnectException("x").toTransportError())
        assertEquals(ProbeOutcome.TransportError.SSL, (SSLException("x") as Throwable).toTransportError())
        assertEquals(ProbeOutcome.TransportError.SSL, SSLHandshakeException("x").toTransportError())
        assertEquals(ProbeOutcome.TransportError.IO, IOException("x").toTransportError())
        assertEquals(ProbeOutcome.TransportError.OTHER, RuntimeException("x").toTransportError())
    }

    @Test
    fun `socket timeout is classified as TIMEOUT not IO despite being an IOException`() {
        // SocketTimeoutException extends IOException; order in the when-branch must keep it TIMEOUT.
        val e: Throwable = SocketTimeoutException("read timed out")
        assertEquals(ProbeOutcome.TransportError.TIMEOUT, e.toTransportError())
    }

    @Test
    fun `jsonObjectHasField detects presence and absence`() {
        assertTrue(jsonObjectHasField("""{"choices":[{"message":{}}]}""", "choices"))
        assertTrue(jsonObjectHasField("""{"candidates":[]}""", "candidates"))
        assertTrue(jsonObjectHasField("""{"content":[{"type":"text"}]}""", "content"))
        assertFalse(jsonObjectHasField("""{"foo":1}""", "choices"))
        assertFalse(jsonObjectHasField("not json", "choices"))
        assertFalse(jsonObjectHasField("[1,2,3]", "choices"))
    }
}
