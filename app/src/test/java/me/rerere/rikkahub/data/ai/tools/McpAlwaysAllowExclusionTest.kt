package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mcp_add and mcp_update tools must drop the "Always Allow" approval scope: a hostile
 * MCP server can exfiltrate everything reachable through the assistant, so each install /
 * config-change is per-call confirmed. This is a load-bearing security guarantee — every
 * surface that renders an approval keyboard reads [ToolApprovalDefaults.allowsAlwaysAllow]
 * to decide whether to show the button.
 */
class McpAlwaysAllowExclusionTest {

    @Test fun `mcp_add rejects always-allow scope`() {
        assertFalse(ToolApprovalDefaults.allowsAlwaysAllow("mcp_add"))
    }

    @Test fun `mcp_update rejects always-allow scope`() {
        assertFalse(ToolApprovalDefaults.allowsAlwaysAllow("mcp_update"))
    }

    @Test fun `other side-effecting MCP tools allow always-allow`() {
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("mcp_delete"))
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("mcp_set_enabled"))
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("mcp_set_tool_approval"))
    }

    @Test fun `read-only mcp tools not in NO_ALWAYS_ALLOW`() {
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("mcp_list"))
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("mcp_get"))
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("mcp_test"))
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("mcp_list_tools"))
    }

    @Test fun `unrelated tools allow always-allow`() {
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("ssh_exec"))
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("termux_run_command"))
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("get_battery_status"))
    }

    @Test fun `mcp_add and mcp_update are in ALWAYS_ASK set`() {
        assertTrue("mcp_add" in ToolApprovalDefaults.ALWAYS_ASK)
        assertTrue("mcp_update" in ToolApprovalDefaults.ALWAYS_ASK)
    }

    @Test fun `requiresApproval true for all five mcp side-effecting tools`() {
        for (name in listOf("mcp_add", "mcp_update", "mcp_delete", "mcp_set_enabled", "mcp_set_tool_approval")) {
            assertTrue("$name should requireApproval", ToolApprovalDefaults.requiresApproval(name))
        }
    }
}
