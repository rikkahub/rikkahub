package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConfigImportParserTest {
    @Test
    fun `should parse remote http and sse servers`() {
        val json = """
            {
              "mcpServers": {
                "http-server": {
                  "type": "streamable_http",
                  "url": "https://example.com/mcp",
                  "headers": {
                    "Authorization": "Bearer token"
                  }
                },
                "sse-server": {
                  "type": "sse",
                  "url": "https://example.com/sse"
                }
              }
            }
        """.trimIndent()

        val configs = McpConfigImportParser.parseMcpServersFromJson(json)

        assertEquals(2, configs.size)
        val http = configs[0] as McpServerConfig.StreamableHTTPServer
        assertEquals("http-server", http.commonOptions.name)
        assertEquals("https://example.com/mcp", http.url)
        assertEquals(listOf("Authorization" to "Bearer token"), http.commonOptions.headers)

        val sse = configs[1] as McpServerConfig.SseTransportServer
        assertEquals("sse-server", sse.commonOptions.name)
        assertEquals("https://example.com/sse", sse.url)
    }

    @Test
    fun `should parse stdio server from standard command config`() {
        val json = """
            {
              "mcpServers": {
                "filesystem": {
                  "command": "npx",
                  "args": [
                    "-y",
                    "@modelcontextprotocol/server-filesystem",
                    "/data/data/com.termux/files/home"
                  ],
                  "env": {
                    "NODE_ENV": "production"
                  },
                  "cwd": "/data/data/com.termux/files/home/projects"
                }
              }
            }
        """.trimIndent()

        val configs = McpConfigImportParser.parseMcpServersFromJson(json)

        assertEquals(1, configs.size)
        val stdio = configs.single() as McpServerConfig.StdioServer
        assertEquals("filesystem", stdio.commonOptions.name)
        assertEquals("npx", stdio.command)
        assertEquals(
            listOf(
                "-y",
                "@modelcontextprotocol/server-filesystem",
                "/data/data/com.termux/files/home",
            ),
            stdio.args,
        )
        assertEquals(listOf("NODE_ENV" to "production"), stdio.env)
        assertEquals("/data/data/com.termux/files/home/projects", stdio.workdir)
    }

    @Test
    fun `should ignore invalid entries`() {
        val json = """
            {
              "mcpServers": {
                "broken": {
                  "type": "streamable_http"
                },
                "valid": {
                  "type": "stdio",
                  "command": "python3"
                }
              }
            }
        """.trimIndent()

        val configs = McpConfigImportParser.parseMcpServersFromJson(json)

        assertEquals(1, configs.size)
        assertTrue(configs.single() is McpServerConfig.StdioServer)
    }
}
