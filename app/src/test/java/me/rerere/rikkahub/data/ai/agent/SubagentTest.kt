package me.rerere.rikkahub.data.ai.agent

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.subagent.DefaultSubagentRunner
import me.rerere.rikkahub.data.ai.agent.subagent.ControlledExploreBatch
import me.rerere.rikkahub.data.ai.agent.subagent.ControlledSubagentLimits
import me.rerere.rikkahub.data.ai.agent.subagent.EXPLORE_SUBAGENT_TOOL_NAME
import me.rerere.rikkahub.data.ai.agent.subagent.ExploreToolAllowlist
import me.rerere.rikkahub.data.ai.agent.tools.ToolProvider
import me.rerere.rikkahub.data.ai.agent.tools.ToolProviderOrder
import me.rerere.rikkahub.data.ai.agent.tools.ToolRegistry
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubagentTest {
    @Test
    fun `explore allowlist is read-only and excludes write shell and explore itself`() {
        assertTrue(ExploreToolAllowlist.isAllowed("workspace_read_file"))
        assertTrue(ExploreToolAllowlist.isAllowed("workspace_search_files"))
        assertFalse(ExploreToolAllowlist.isAllowed("workspace_write_file"))
        assertFalse(ExploreToolAllowlist.isAllowed("workspace_shell"))
        assertFalse(ExploreToolAllowlist.isAllowed("memory_tool"))
        assertFalse(ExploreToolAllowlist.isAllowed(EXPLORE_SUBAGENT_TOOL_NAME))
        assertFalse(ExploreToolAllowlist.isAllowed("mcp__server__tool"))
        assertFalse(ExploreToolAllowlist.isAllowed("search_web"))
    }

    @Test
    fun `explore tool not registered inside subagent run`() = runBlocking {
        val exploreProvider = object : ToolProvider {
            override val order: Int = ToolProviderOrder.SUBAGENT
            override fun isEnabled(ctx: ToolResolveContext): Boolean = !ctx.isSubagentRun
            override suspend fun provide(ctx: ToolResolveContext): List<Tool> = listOf(
                Tool(
                    name = EXPLORE_SUBAGENT_TOOL_NAME,
                    description = "explore",
                    execute = { listOf(UIMessagePart.Text("ok")) },
                )
            )
        }
        val registry = ToolRegistry(listOf(exploreProvider))
        val assistant = Assistant(id = Uuid.random(), name = "t")
        val conversation = Conversation.ofId(Uuid.random(), assistant.id)
        val parent = ToolResolveContext(
            settings = Settings(),
            assistant = assistant,
            conversation = conversation,
            isSubagentRun = false,
        )
        val child = parent.copy(isSubagentRun = true)
        assertEquals(listOf(EXPLORE_SUBAGENT_TOOL_NAME), registry.resolve(parent).map { it.name })
        assertTrue(registry.resolve(child).isEmpty())
    }

    @Test
    fun `extract final assistant text prefers last assistant message`() {
        val messages = listOf(
            UIMessage.user("task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("draft")),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("## Findings\n"),
                    UIMessagePart.Text("- done"),
                ),
            ),
        )
        assertEquals(
            "## Findings\n- done",
            DefaultSubagentRunner.extractFinalAssistantText(messages),
        )
    }

    @Test
    fun `filter tools with extra allowlist intersection`() {
        val names = listOf(
            "workspace_read_file",
            "search_web",
            "workspace_shell",
            "memory_tool",
        )
        assertEquals(
            listOf("workspace_read_file"),
            ExploreToolAllowlist.filter(names, setOf("workspace_read_file", "workspace_shell")),
        )
    }

    @Test
    fun `controlled explore admits at most two calls from one tool batch`() {
        val admitted = ControlledExploreBatch.admittedCallIds(listOf("first", "second", "third"))

        assertEquals(setOf("first", "second"), admitted)
        assertEquals(setOf("first"), ControlledExploreBatch.admittedCallIds(listOf("first", "second"), 1))
    }

    @Test
    fun `controlled explore never permits more than two children for one parent`() {
        assertEquals(2, ControlledSubagentLimits().maxChildrenPerParent)
        assertTrue(runCatching { ControlledSubagentLimits(maxChildrenPerParent = 3) }.isFailure)
    }

    @Test
    fun `controlled report strips raw trace and non repository evidence`() {
        val report = DefaultSubagentRunner.controlledReport(
            """
            ## Findings
            - The policy is evaluated at execution.
            ## Evidence paths
            - app/src/main/java/Policy.kt
            - C:/Users/secret.txt
            ## Confidence
            - HIGH
            ## Open questions
            - Is artifact search exposed?
            """.trimIndent()
        )

        assertEquals(listOf("The policy is evaluated at execution."), report.findings)
        assertEquals(listOf("app/src/main/java/Policy.kt"), report.evidencePaths)
        assertEquals("HIGH", report.confidence)
        assertEquals(listOf("Is artifact search exposed?"), report.unresolved)
        assertFalse(report.toString().contains("C:/Users/secret.txt"))
    }

}
