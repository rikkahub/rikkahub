package me.rerere.rikkahub.data.ai.agent

import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.permission.ApprovalAction
import me.rerere.rikkahub.data.ai.agent.permission.AgentPermissionMode
import me.rerere.rikkahub.data.ai.agent.permission.PermissionPolicy
import me.rerere.rikkahub.data.ai.agent.permission.ToolCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    private val autoTool = Tool(
        name = "workspace_read_file",
        description = "read",
        needsApproval = { false },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )
    private val askTool = Tool(
        name = "ask_user",
        description = "ask",
        needsApproval = { true },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    @Test
    fun `compatible default defers to tool needsApproval`() {
        val policy = PermissionPolicy.compatibleDefault()
        val args = buildJsonObject { }
        assertFalse(policy.requiresApproval(autoTool, args, AgentMode.CHAT))
        assertTrue(policy.requiresApproval(askTool, args, AgentMode.CHAT))
    }

    @Test
    fun `full access bypasses tool approval metadata`() {
        val policy = PermissionPolicy.compatibleDefault(
            permissionMode = AgentPermissionMode.FULL_ACCESS,
        )

        assertFalse(policy.requiresApproval(askTool, buildJsonObject { }, AgentMode.AGENT))
    }

    @Test
    fun `full access applies to plan mode after its plan is presented`() {
        val policy = PermissionPolicy.compatibleDefault(
            permissionMode = AgentPermissionMode.FULL_ACCESS,
        )
        val shell = Tool(
            name = "workspace_shell",
            description = "shell",
            needsApproval = { false },
            execute = { listOf(UIMessagePart.Text("ok")) },
        )

        assertFalse(policy.requiresApproval(shell, buildJsonObject { }, AgentMode.PLAN))
    }

    @Test
    fun `category ASK forces approval`() {
        val policy = PermissionPolicy(
            byCategory = mapOf(ToolCategory.WORKSPACE_READ to ApprovalAction.ASK)
        )
        assertTrue(policy.requiresApproval(autoTool, buildJsonObject { }, AgentMode.CHAT))
    }

    @Test
    fun `plan mode uses the same approval policy as execution mode`() {
        val policy = PermissionPolicy.compatibleDefault()
        val shell = Tool(
            name = "workspace_shell",
            description = "shell",
            needsApproval = { false },
            execute = { listOf(UIMessagePart.Text("ok")) },
        )
        assertFalse(policy.requiresApproval(shell, buildJsonObject { }, AgentMode.PLAN))
        assertFalse(policy.requiresApproval(shell, buildJsonObject { }, AgentMode.AGENT))
    }

    @Test
    fun `tool category mapping covers workspace tools`() {
        assertEquals(ToolCategory.LOCAL_SENSITIVE, ToolCategory.ofToolName("clipboard_tool"))
        assertEquals(ToolCategory.LOCAL_SENSITIVE, ToolCategory.ofToolName("calendar_query"))
        assertEquals(ToolCategory.LOCAL_SENSITIVE, ToolCategory.ofToolName("calendar_create"))
        assertEquals(ToolCategory.LOCAL_SENSITIVE, ToolCategory.ofToolName("get_screen_time"))
        assertEquals(ToolCategory.WORKSPACE_READ, ToolCategory.ofToolName("workspace_read_file"))
        assertEquals(ToolCategory.WORKSPACE_WRITE, ToolCategory.ofToolName("workspace_edit_file"))
        assertEquals(ToolCategory.WORKSPACE_SHELL, ToolCategory.ofToolName("workspace_shell"))
        assertEquals(ToolCategory.MCP, ToolCategory.ofToolName("mcp__demo__tool"))
        assertEquals(ToolCategory.MEMORY, ToolCategory.ofToolName("memory_tool"))
    }

    @Test
    fun `agent mode cycles chat plan agent`() {
        assertEquals(AgentMode.PLAN, AgentMode.CHAT.next())
        assertEquals(AgentMode.AGENT, AgentMode.PLAN.next())
        assertEquals(AgentMode.CHAT, AgentMode.AGENT.next())
        assertEquals(AgentMode.CHAT, AgentMode.fromStorage(null))
        assertEquals(AgentMode.PLAN, AgentMode.fromStorage("plan"))
    }
}
