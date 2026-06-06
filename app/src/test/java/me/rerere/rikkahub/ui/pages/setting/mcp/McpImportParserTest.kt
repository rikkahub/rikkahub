package me.rerere.rikkahub.ui.pages.setting.mcp

import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for parseMcpServersFromJson extracted out of SettingMcpPage.kt into
 * McpImportParser.kt (issue #106). These lock the exact behaviour previously inlined in the
 * import dialog so the mechanical extraction is proven behaviour-preserving.
 */
class McpImportParserTest {

    @Test
    fun `parses streamable_http server with url and headers`() {
        val json = """
            {
              "mcpServers": {
                "alpha": {
                  "type": "streamable_http",
                  "url": "https://example.com/mcp",
                  "headers": { "Authorization": "Bearer x", "X-Trace": "1" }
                }
              }
            }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.size)
        val server = result[0]
        assertTrue(server is McpServerConfig.StreamableHTTPServer)
        server as McpServerConfig.StreamableHTTPServer
        assertEquals("alpha", server.commonOptions.name)
        assertEquals("https://example.com/mcp", server.url)
        assertEquals(
            listOf("Authorization" to "Bearer x", "X-Trace" to "1"),
            server.commonOptions.headers
        )
    }

    @Test
    fun `parses sse type into SseTransportServer`() {
        val json = """
            {
              "mcpServers": {
                "beta": { "type": "sse", "url": "https://example.com/sse" }
              }
            }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.size)
        val server = result[0]
        assertTrue(server is McpServerConfig.SseTransportServer)
        server as McpServerConfig.SseTransportServer
        assertEquals("beta", server.commonOptions.name)
        assertEquals("https://example.com/sse", server.url)
    }

    @Test
    fun `defaults to streamable_http when type is absent`() {
        val json = """
            {
              "mcpServers": {
                "gamma": { "url": "https://example.com/default" }
              }
            }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.size)
        assertTrue(result[0] is McpServerConfig.StreamableHTTPServer)
    }

    @Test
    fun `skips entries missing url and keeps the rest`() {
        val json = """
            {
              "mcpServers": {
                "missing": { "type": "sse" },
                "valid": { "type": "sse", "url": "https://example.com/keep" }
              }
            }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.size)
        assertEquals("valid", result[0].commonOptions.name)
    }

    @Test
    fun `returns empty list when mcpServers key is absent`() {
        val json = """{ "somethingElse": {} }"""

        val result = parseMcpServersFromJson(json)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `headers default to empty when absent`() {
        val json = """
            {
              "mcpServers": {
                "delta": { "url": "https://example.com/noheaders" }
              }
            }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.size)
        assertTrue(result[0].commonOptions.headers.isEmpty())
    }
}
