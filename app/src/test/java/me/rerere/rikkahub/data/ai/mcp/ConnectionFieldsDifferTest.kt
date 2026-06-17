package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Locks in the reconnect-on-edit predicate the settings collector uses: editing an enabled
 * server's transport/url/headers must be detected as a connection change (so the live client
 * is torn down and re-added), while enable-only / name / tool-list edits must NOT, to avoid
 * needlessly dropping a healthy connection.
 */
class ConnectionFieldsDifferTest {
    private val id = Uuid.random()

    private fun sse(url: String, headers: List<Pair<String, String>> = emptyList(), enable: Boolean = true, name: String = "s") =
        McpServerConfig.SseTransportServer(
            id = id,
            commonOptions = McpCommonOptions(enable = enable, name = name, headers = headers),
            url = url,
        )

    private fun http(url: String, headers: List<Pair<String, String>> = emptyList()) =
        McpServerConfig.StreamableHTTPServer(
            id = id,
            commonOptions = McpCommonOptions(headers = headers),
            url = url,
        )

    @Test
    fun `identical configs do not differ`() {
        assertFalse(connectionFieldsDiffer(sse("https://a"), sse("https://a")))
    }

    @Test
    fun `url change is a connection change`() {
        assertTrue(connectionFieldsDiffer(sse("https://a"), sse("https://b")))
    }

    @Test
    fun `transport subclass change is a connection change`() {
        assertTrue(connectionFieldsDiffer(sse("https://a"), http("https://a")))
    }

    @Test
    fun `header change is a connection change`() {
        assertTrue(
            connectionFieldsDiffer(
                sse("https://a", headers = listOf("Authorization" to "old")),
                sse("https://a", headers = listOf("Authorization" to "new")),
            )
        )
    }

    @Test
    fun `enable-only toggle is not a connection change`() {
        assertFalse(connectionFieldsDiffer(sse("https://a", enable = true), sse("https://a", enable = false)))
    }

    @Test
    fun `name change is not a connection change`() {
        assertFalse(connectionFieldsDiffer(sse("https://a", name = "old"), sse("https://a", name = "new")))
    }
}
