package me.rerere.rikkahub.data.datastore

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.QuickMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsRevisionStateTest {
    @Test
    fun `rapid stale text updates keep the latest text and concurrent fields`() {
        val state = SettingsRevisionState()
        state.publish(Settings(titlePrompt = "base", developerMode = false))
        val originalSnapshot = state.current

        val firstTextUpdate = state.prepareWholeUpdate(originalSnapshot.copy(titlePrompt = "a"))
        state.publish(firstTextUpdate.copy(developerMode = true))

        val secondTextUpdate = state.prepareWholeUpdate(originalSnapshot.copy(titlePrompt = "ab"))

        assertEquals("ab", secondTextUpdate.titlePrompt)
        assertEquals(true, secondTextUpdate.developerMode)
    }

    @Test
    fun `startup mutation waits for the first authoritative snapshot`() = runBlocking {
        val state = SettingsRevisionState()
        val mutation = async {
            state.awaitReady()
            state.current.copy(launchCount = state.current.launchCount + 1)
        }

        yield()
        assertFalse(mutation.isCompleted)

        state.publish(Settings(launchCount = 41))
        assertEquals(42, mutation.await().launchCount)
    }

    @Test
    fun `stale assistant edit preserves concurrent fields on the same assistant`() {
        val assistantId = Uuid.random()
        val oldModelId = Uuid.random()
        val newModelId = Uuid.random()
        val baseAssistant = Assistant(
            id = assistantId,
            name = "Original",
            chatModelId = oldModelId,
        )

        val merged = mergeStaleSettings(
            base = Settings(assistants = listOf(baseAssistant)),
            incoming = Settings(assistants = listOf(baseAssistant.copy(name = "Renamed"))),
            current = Settings(assistants = listOf(baseAssistant.copy(chatModelId = newModelId))),
        ).assistants.single()

        assertEquals("Renamed", merged.name)
        assertEquals(newModelId, merged.chatModelId)
    }

    @Test
    fun `stale provider edit preserves refreshed credentials nested models and transient metadata`() {
        val providerId = Uuid.random()
        val model = Model(id = Uuid.random(), modelId = "demo", displayName = "Old model name")
        val baseProvider = ProviderSetting.OpenAI(
            id = providerId,
            name = "Original provider",
            models = listOf(model),
            apiKey = "old-key",
            builtIn = true,
        )

        val merged = mergeStaleSettings(
            base = Settings(providers = listOf(baseProvider)),
            incoming = Settings(providers = listOf(baseProvider.copy(name = "Renamed provider"))),
            current = Settings(
                providers = listOf(
                    baseProvider.copy(
                        apiKey = "fresh-key",
                        models = listOf(model.copy(displayName = "Current model name")),
                    )
                )
            ),
        ).providers.single() as ProviderSetting.OpenAI

        assertEquals("Renamed provider", merged.name)
        assertEquals("fresh-key", merged.apiKey)
        assertEquals("Current model name", merged.models.single().displayName)
        assertEquals(true, merged.builtIn)
    }

    @Test
    fun `stable item merge keeps concurrent additions and current deletions`() {
        val first = QuickMessage(id = Uuid.random(), title = "Old title", content = "Old content")
        val removed = QuickMessage(id = Uuid.random(), title = "Remove me")
        val concurrentlyAdded = QuickMessage(id = Uuid.random(), title = "Concurrent addition")

        val merged = mergeStaleSettings(
            base = Settings(quickMessages = listOf(first, removed)),
            incoming = Settings(quickMessages = listOf(first.copy(title = "New title"), removed)),
            current = Settings(
                quickMessages = listOf(first.copy(content = "New content"), concurrentlyAdded)
            ),
        ).quickMessages

        assertEquals(listOf(first.id, concurrentlyAdded.id), merged.map(QuickMessage::id))
        assertEquals("New title", merged.first().title)
        assertEquals("New content", merged.first().content)
    }

    @Test
    fun `nested values and identity lists merge independent concurrent changes`() {
        val baseFavorite = Uuid.random()
        val staleAddition = Uuid.random()
        val currentAddition = Uuid.random()
        val base = Settings(
            displaySetting = DisplaySetting(showModelName = true, showTokenUsage = true),
            favoriteModels = listOf(baseFavorite),
        )

        val merged = mergeStaleSettings(
            base = base,
            incoming = base.copy(
                displaySetting = base.displaySetting.copy(showTokenUsage = false),
                favoriteModels = listOf(baseFavorite, staleAddition),
            ),
            current = base.copy(
                displaySetting = base.displaySetting.copy(showModelName = false),
                favoriteModels = listOf(baseFavorite, currentAddition),
            ),
        )

        assertEquals(false, merged.displaySetting.showModelName)
        assertEquals(false, merged.displaySetting.showTokenUsage)
        assertEquals(listOf(baseFavorite, staleAddition, currentAddition), merged.favoriteModels)
    }

    @Test
    fun `stale MCP metadata edit preserves a concurrently refreshed token`() {
        val serverId = Uuid.random()
        val oldOAuth = oauth(accessToken = "old-access", refreshToken = "old-refresh", expiresAt = 10L)
        val baseServer = server(serverId, oauth = oldOAuth)
        val refreshedServer = server(
            serverId,
            oauth = oldOAuth.copy(
                accessToken = "fresh-access",
                refreshToken = "fresh-refresh",
                expiresAt = 20L,
            ),
        )
        val editedServer = server(
            serverId,
            oauth = oldOAuth,
            tools = listOf(McpTool(name = "search", description = "new metadata")),
        )

        val merged = mergeStaleSettings(
            base = Settings(mcpServers = listOf(baseServer)),
            incoming = Settings(mcpServers = listOf(editedServer)),
            current = Settings(mcpServers = listOf(refreshedServer), developerMode = true),
        )
        val mergedServer = merged.mcpServers.single()

        assertEquals("fresh-access", mergedServer.commonOptions.oauth?.accessToken)
        assertEquals("fresh-refresh", mergedServer.commonOptions.oauth?.refreshToken)
        assertEquals(20L, mergedServer.commonOptions.oauth?.expiresAt)
        assertEquals("new metadata", mergedServer.commonOptions.tools.single().description)
        assertEquals(true, merged.developerMode)
    }

    @Test
    fun `stale MCP edit cannot resurrect a removed server or cleared authorization`() {
        val removedId = Uuid.random()
        val disconnectedId = Uuid.random()
        val baseRemoved = server(removedId, oauth = oauth("removed-token", "removed-refresh", 10L))
        val baseDisconnected = server(disconnectedId, oauth = oauth("old-token", "old-refresh", 10L))
        val staleRemovedEdit = baseRemoved.withTools(McpTool(name = "removed-tool"))
        val staleDisconnectedEdit = baseDisconnected.withTools(McpTool(name = "still-present"))
        val disconnectedCurrent = server(disconnectedId, oauth = null)

        val merged = mergeStaleMcpServers(
            base = listOf(baseRemoved, baseDisconnected),
            incoming = listOf(staleRemovedEdit, staleDisconnectedEdit),
            current = listOf(disconnectedCurrent),
        )

        assertEquals(listOf(disconnectedId), merged.map(McpServerConfig::id))
        assertNull(merged.single().commonOptions.oauth)
    }

    @Test
    fun `changing MCP authority strips credentials copied from the old authority`() {
        val serverId = Uuid.random()
        val oldOAuth = oauth("old-access", "old-refresh", 10L)
        val baseServer = server(serverId, url = "https://old.example/mcp", oauth = oldOAuth)
        val staleAuthorityEdit = server(
            serverId,
            url = "https://new.example/mcp",
            oauth = oldOAuth,
        )

        val merged = mergeStaleMcpServers(
            base = listOf(baseServer),
            incoming = listOf(staleAuthorityEdit),
            current = listOf(baseServer),
        ).single()

        assertEquals("https://new.example/mcp", (merged as McpServerConfig.StreamableHTTPServer).url)
        assertNull(merged.commonOptions.oauth?.accessToken)
        assertNull(merged.commonOptions.oauth?.refreshToken)
        assertEquals(0L, merged.commonOptions.oauth?.expiresAt)
    }

    private fun server(
        id: Uuid,
        url: String = "https://example.com/mcp",
        oauth: McpOAuthState?,
        tools: List<McpTool> = emptyList(),
    ): McpServerConfig.StreamableHTTPServer = McpServerConfig.StreamableHTTPServer(
        id = id,
        commonOptions = McpCommonOptions(name = "demo", tools = tools, oauth = oauth),
        url = url,
    )

    private fun McpServerConfig.StreamableHTTPServer.withTools(
        vararg tools: McpTool,
    ): McpServerConfig.StreamableHTTPServer = copy(
        commonOptions = commonOptions.copy(tools = tools.toList()),
    )

    private fun oauth(
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
    ): McpOAuthState = McpOAuthState(
        enabled = true,
        clientId = "client",
        tokenEndpoint = "https://auth.example/token",
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = expiresAt,
    )
}
