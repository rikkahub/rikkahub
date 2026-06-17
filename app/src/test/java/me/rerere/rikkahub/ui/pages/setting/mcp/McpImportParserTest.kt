package me.rerere.rikkahub.ui.pages.setting.mcp

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import me.rerere.ai.runtime.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.mcpAutoConnectCandidates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `invariant imported servers are always disabled`() {
        runBlocking {
            checkAll(
                Arb.element(listOf("sse", "streamable_http")),
                Arb.element(listOf("http://", "https://")),
                Arb.element(listOf(true, false)),
            ) { transportType, scheme, enabled ->
                val json = """
                    {
                      "mcpServers": {
                        "node": {
                          "type": "$transportType",
                          "url": "${scheme}example.com/$transportType",
                          "enable": $enabled
                        }
                      }
                    }
                """.trimIndent()

                val result = parseMcpServersFromJson(json)

                assertEquals(1, result.size)
                assertFalse(result[0].commonOptions.enable)
            }
        }
    }

    @Test
    fun `boundary only valid http(s) entries stay and are disabled`() {
        val json = """
            {
              "mcpServers": {
                "enabledValid": { "type": "sse", "url": "https://good.example.com/mcp", "enable": true },
                "blank": { "type": "sse", "url": "   " },
                "fileScheme": { "type": "streamable_http", "url": "file:///tmp/mcp" },
                "wsScheme": { "type": "sse", "url": "ws://bad.example.com/mcp" }
              }
            }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.size)
        assertTrue(result[0] is McpServerConfig.SseTransportServer)
        val server = result[0] as McpServerConfig.SseTransportServer
        assertFalse(server.commonOptions.enable)
        assertEquals("https://good.example.com/mcp", server.url)
    }

    @Test
    fun `invariant imported json never produces auto-connect candidates`() {
        runBlocking {
            checkAll(
                Arb.element(listOf("http://", "https://")),
                Arb.element(listOf("sse", "streamable_http")),
                Arb.element(listOf(true, false)),
            ) { scheme, transportType, enabled ->
                val json = """
                    {
                      "mcpServers": {
                        "node": {
                          "type": "$transportType",
                          "url": "${scheme}example.com/$transportType",
                          "enable": $enabled
                        }
                      }
                    }
                """.trimIndent()

                val result = parseMcpServersFromJson(json)

                assertTrue(mcpAutoConnectCandidates(result).isEmpty())
                assertFalse(result[0].commonOptions.enable)
            }
        }
    }

    @Test
    fun `metamorphic import concat stays non-connectable`() {
        val json = """
            {
              "mcpServers": {
                "imported": { "type": "streamable_http", "url": "https://example.com/mcp", "enable": true }
              }
            }
        """.trimIndent()

        val first = parseMcpServersFromJson(json)
        val second = parseMcpServersFromJson(json)

        assertTrue(mcpAutoConnectCandidates(first + second).isEmpty())
    }
}
