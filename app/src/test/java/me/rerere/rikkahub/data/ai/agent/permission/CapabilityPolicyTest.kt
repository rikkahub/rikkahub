package me.rerere.rikkahub.data.ai.agent.permission

import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.workspace.Workspace
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityPolicyTest {
    private val assistant = Assistant(name = "policy-test")
    private val workspace = Workspace(
        id = "workspace",
        name = "workspace",
        root = "/tmp/workspace",
        shellStatus = WorkspaceShellStatus.READY,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun evaluate(
        toolName: String,
        mode: AgentMode = AgentMode.AGENT,
        workspace: Workspace? = this.workspace,
        policy: PermissionPolicy = PermissionPolicy.compatibleDefault(),
        mcpServer: McpServerPolicyContext? = null,
        isSubagentRun: Boolean = false,
    ): PolicyDecision = CapabilityPolicy.evaluate(
        CapabilityPolicyContext(
            assistant = assistant,
            mode = mode,
            workspace = workspace,
            descriptor = ToolDescriptorRegistry.descriptorFor(toolName),
            permissionPolicy = policy,
            mcpServer = mcpServer,
            isSubagentRun = isSubagentRun,
        )
    )

    @Test
    fun `critical confirmation allows normal workspace edits but asks for shell`() {
        val policy = PermissionPolicy.compatibleDefault(
            permissionMode = AgentPermissionMode.CONFIRM_CRITICAL,
        )

        assertTrue(evaluate("workspace_write_file", policy = policy) is PolicyDecision.Allow)
        assertTrue(evaluate("workspace_edit_file", policy = policy) is PolicyDecision.Allow)
        assertTrue(evaluate("workspace_shell", policy = policy) is PolicyDecision.Ask)
    }

    @Test
    fun `full access bypasses approval rules`() {
        val policy = PermissionPolicy.compatibleDefault(
            permissionMode = AgentPermissionMode.FULL_ACCESS,
        )

        assertTrue(evaluate("workspace_shell", policy = policy) is PolicyDecision.Allow)
        assertTrue(evaluate("workspace_write_file", policy = policy) is PolicyDecision.Allow)
        assertTrue(evaluate("memory_tool", policy = policy) is PolicyDecision.Allow)
        assertTrue(
            evaluate(
                "mcp__demo__write",
                policy = policy,
                mcpServer = McpServerPolicyContext(needsApproval = true),
            ) is PolicyDecision.Allow,
        )
        assertTrue(evaluate("future_tool", policy = policy) is PolicyDecision.Allow)
    }

    @Test
    fun `full access preserves workspace structural denies in plan mode`() {
        val policy = PermissionPolicy.compatibleDefault(
            permissionMode = AgentPermissionMode.FULL_ACCESS,
        )

        assertTrue(evaluate("workspace_shell", mode = AgentMode.PLAN, policy = policy) is PolicyDecision.Allow)
        assertTrue(evaluate("workspace_write_file", workspace = null, policy = policy) is PolicyDecision.Deny)
    }

    @Test
    fun `low risk read-only tools are allowed`() {
        val decision = evaluate("search_web")

        assertTrue(decision is PolicyDecision.Allow)
        assertEquals(PolicyCode.LOW_RISK_READ_ONLY, decision.code)
    }

    @Test
    fun `unknown tools are conservative by default`() {
        val decision = evaluate("future_tool")

        assertTrue(decision is PolicyDecision.Ask)
        assertEquals(PolicyCode.HIGH_RISK_OR_UNKNOWN, decision.code)
    }

    @Test
    fun `PLAN applies normal approval policy to shell`() {
        val decision = evaluate("workspace_shell", mode = AgentMode.PLAN)

        assertTrue(decision is PolicyDecision.Ask)
        assertEquals(PolicyCode.HIGH_RISK_OR_UNKNOWN, decision.code)
    }

    @Test
    fun `PLAN still evaluates approval for allowed sensitive tool`() {
        val decision = evaluate("calendar_query", mode = AgentMode.PLAN)

        assertTrue(decision is PolicyDecision.Ask)
        assertEquals(PolicyCode.HIGH_RISK_OR_UNKNOWN, decision.code)
    }

    @Test
    fun `MCP server false approval cannot lower local baseline`() {
        val decision = evaluate(
            toolName = "mcp__demo__list_files",
            mcpServer = McpServerPolicyContext(needsApproval = false),
        )

        assertTrue(decision is PolicyDecision.Ask)
        assertEquals(PolicyCode.HIGH_RISK_OR_UNKNOWN, decision.code)
    }

    @Test
    fun `sensitive local and memory mutations require approval`() {
        assertTrue(evaluate("clipboard_tool") is PolicyDecision.Ask)
        assertTrue(evaluate("memory_tool") is PolicyDecision.Ask)
    }

    @Test
    fun `workspace writes require workspace and approval`() {
        val allowedContext = evaluate("workspace_write_file")
        val missingWorkspace = evaluate("workspace_write_file", workspace = null)

        assertTrue(allowedContext is PolicyDecision.Ask)
        assertEquals(PolicyCode.HIGH_RISK_OR_UNKNOWN, allowedContext.code)
        assertTrue(missingWorkspace is PolicyDecision.Deny)
        assertEquals(PolicyCode.WORKSPACE_UNAVAILABLE, missingWorkspace.code)
    }

    @Test
    fun `legacy policy can raise approval for safe tool`() {
        val decision = evaluate(
            toolName = "search_web",
            policy = PermissionPolicy(byCategory = mapOf(ToolCategory.SEARCH to ApprovalAction.ASK)),
        )

        assertTrue(decision is PolicyDecision.Ask)
        assertEquals(PolicyCode.LEGACY_POLICY_ASK, decision.code)
    }

    @Test
    fun `PLAN keeps legacy category approval rules`() {
        val decision = evaluate(
            toolName = "memory_tool",
            mode = AgentMode.PLAN,
            policy = PermissionPolicy(byCategory = mapOf(ToolCategory.MEMORY to ApprovalAction.AUTO)),
        )

        assertTrue(decision is PolicyDecision.Ask)
        assertEquals(PolicyCode.HIGH_RISK_OR_UNKNOWN, decision.code)
    }

    @Test
    fun `execution policy denies every non repository read for controlled child`() {
        assertTrue(evaluate("workspace_read_file", isSubagentRun = true) is PolicyDecision.Allow)
        assertTrue(evaluate("workspace_search_files", isSubagentRun = true) is PolicyDecision.Allow)
        listOf("workspace_write_file", "workspace_shell", "memory_tool", "mcp__demo__read", "search_web").forEach {
            val decision = PolicyEngine.evaluate(
                CapabilityPolicyContext(
                    assistant = assistant,
                    mode = AgentMode.PLAN,
                    workspace = workspace,
                    descriptor = ToolDescriptorRegistry.descriptorFor(it),
                    isSubagentRun = true,
                ),
            )
            assertTrue("$it must be rejected", decision is PolicyDecision.Deny)
            assertEquals(PolicyCode.SUBAGENT_TOOL_NOT_ALLOWED, decision.code)
        }
    }
}
