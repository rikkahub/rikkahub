package me.rerere.rikkahub.data.ai.agent.tools.providers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.ai.agent.tools.WorkspaceToolPolicy
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.Workspace
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import kotlin.uuid.Uuid

class WorkspaceToolProviderTest {
    private val assistant = Assistant(id = Uuid.random(), workspaceId = Uuid.random())

    @Test
    fun `frozen absent workspace never gains live tools`() = runBlocking {
        val provider = WorkspaceToolProvider(mock(WorkspaceRepository::class.java))

        val tools = provider.provide(context(WorkspaceToolPolicy.FrozenAbsent))

        assertTrue(tools.isEmpty())
    }

    @Test
    fun `frozen workspace retains its approval overrides without a live lookup`() = runBlocking {
        val provider = WorkspaceToolProvider(mock(WorkspaceRepository::class.java))
        val policy = WorkspaceToolPolicy.Frozen(
            workspace = workspace().copy(id = assistant.workspaceId.toString()),
            approvalOverrides = mapOf(
                "workspace_read_file" to true,
                "workspace_shell" to false,
            ),
        )

        val tools = provider.provide(context(policy))

        assertEquals(5, tools.size)
        assertTrue(tools.single { it.name == "workspace_read_file" }.needsApproval(JsonObject(emptyMap())))
        assertFalse(tools.single { it.name == "workspace_shell" }.needsApproval(JsonObject(emptyMap())))
    }

    private fun context(policy: WorkspaceToolPolicy): ToolResolveContext = ToolResolveContext(
        settings = Settings(),
        assistant = assistant,
        conversation = Conversation.ofId(Uuid.random(), assistant.id),
        workspace = (policy as? WorkspaceToolPolicy.Frozen)?.workspace,
        workspaceToolPolicy = policy,
    )

    private fun workspace() = Workspace(
        id = assistant.workspaceId.toString(),
        name = "workspace",
        root = "/workspace",
        shellStatus = WorkspaceShellStatus.READY,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
