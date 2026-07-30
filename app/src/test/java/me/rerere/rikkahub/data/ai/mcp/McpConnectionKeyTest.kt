package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class McpConnectionKeyTest {
    private val base = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(name = "demo"),
        url = "https://example.com/mcp",
    )

    @Test
    fun `tool metadata does not affect connection key`() {
        val withTools = base.copy(
            commonOptions = base.commonOptions.copy(
                tools = listOf(McpTool(name = "search", enable = false))
            )
        )

        assertEquals(base.connectionKey(), withTools.connectionKey())
    }

    @Test
    fun `url transport and headers affect connection key`() {
        assertNotEquals(base.connectionKey(), base.copy(url = "https://example.com/other").connectionKey())
        assertNotEquals(
            base.connectionKey(),
            McpServerConfig.SseTransportServer(
                id = base.id,
                commonOptions = base.commonOptions,
                url = base.url,
            ).connectionKey()
        )
        assertNotEquals(
            base.connectionKey(),
            base.copy(
                commonOptions = base.commonOptions.copy(headers = listOf("X-API-Key" to "secret"))
            ).connectionKey()
        )
    }

    @Test
    fun `oauth token affects connection key unless manual authorization header wins`() {
        val oauth = McpOAuthState(enabled = true, accessToken = "oauth-token")
        val withOAuth = base.copy(commonOptions = base.commonOptions.copy(oauth = oauth))
        assertNotEquals(base.connectionKey(), withOAuth.connectionKey())

        val manualAuth = base.copy(
            commonOptions = base.commonOptions.copy(
                headers = listOf("Authorization" to "Bearer manual"),
                oauth = oauth,
            )
        )
        val manualAuthWithoutOAuth = manualAuth.copy(
            commonOptions = manualAuth.commonOptions.copy(oauth = null)
        )
        assertEquals(manualAuthWithoutOAuth.connectionKey(), manualAuth.connectionKey())
    }

    @Test
    fun `frozen identity allows OAuth token rotation but rejects authority changes`() {
        val oauth = McpOAuthState(
            enabled = true,
            clientId = "client",
            tokenEndpoint = "https://example.com/token",
            accessToken = "old-token",
            refreshToken = "old-refresh",
            expiresAt = 1L,
        )
        val planned = base.copy(commonOptions = base.commonOptions.copy(oauth = oauth))
        val refreshed = planned.copy(
            commonOptions = planned.commonOptions.copy(
                oauth = oauth.copy(accessToken = "new-token", refreshToken = "new-refresh", expiresAt = 2L),
            ),
        )

        assertEquals(planned.frozenConnectionKey(), refreshed.frozenConnectionKey())
        assertNotEquals(
            planned.frozenConnectionKey(),
            refreshed.copy(url = "https://example.com/other").frozenConnectionKey(),
        )
        assertNotEquals(
            planned.frozenConnectionKey(),
            refreshed.copy(
                commonOptions = refreshed.commonOptions.copy(
                    oauth = refreshed.commonOptions.oauth?.copy(clientId = "other-client"),
                ),
            ).frozenConnectionKey(),
        )
    }

    @Test
    fun `frozen tool policy ignores metadata refresh but rejects approval drift`() {
        val planned = base.copy(
            commonOptions = base.commonOptions.copy(
                tools = listOf(McpTool(name = "search", description = "old", needsApproval = false)),
            ),
        )
        val metadataRefresh = planned.copy(
            commonOptions = planned.commonOptions.copy(
                tools = listOf(McpTool(name = "search", description = "new", needsApproval = false)),
            ),
        )
        val stricter = metadataRefresh.copy(
            commonOptions = metadataRefresh.commonOptions.copy(
                tools = listOf(McpTool(name = "search", description = "new", needsApproval = true)),
            ),
        )

        assertEquals(true, hasSameFrozenToolPolicy(planned, metadataRefresh, "search"))
        assertEquals(false, hasSameFrozenToolPolicy(planned, stricter, "search"))
    }
}
