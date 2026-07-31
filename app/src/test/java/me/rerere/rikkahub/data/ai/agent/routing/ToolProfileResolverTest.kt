package me.rerere.rikkahub.data.ai.agent.routing

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.permission.ApprovalAction
import me.rerere.rikkahub.data.ai.agent.permission.AgentPermissionMode
import me.rerere.rikkahub.data.ai.agent.permission.DescribedTool
import me.rerere.rikkahub.data.ai.agent.permission.McpServerPolicyContext
import me.rerere.rikkahub.data.ai.agent.permission.PermissionPolicy
import me.rerere.rikkahub.data.ai.agent.permission.ToolCategory
import me.rerere.rikkahub.data.ai.agent.permission.ToolApprovalPolicyContext
import me.rerere.rikkahub.data.ai.agent.permission.ToolDescriptorRegistry
import me.rerere.rikkahub.data.ai.agent.permission.ToolDefaultApproval
import me.rerere.rikkahub.data.ai.agent.permission.ToolRiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolProfileResolverTest {
    private val resolver = ToolProfileResolver()

    @Test
    fun `intent profiles expose only their exact safe capabilities`() {
        val candidates = candidateNames.map(::described)

        assertEquals(
            listOf("ask_user", "calendar_query", "get_time_info"),
            resolve(candidates, AgentIntent.ANSWER).resolvedToolNames,
        )
        assertEquals(
            listOf("ask_user"),
            resolve(candidates, AgentIntent.CLARIFY).resolvedToolNames,
        )
        assertEquals(
            listOf(
                "artifact_read",
                "ask_user",
                "calendar_query",
                "conversation_search",
                "explore_subagent",
                "get_time_info",
                "search_web",
                "use_skill",
                "workspace_read_file",
                "workspace_search_files",
            ),
            resolve(candidates, AgentIntent.EXPLORE).resolvedToolNames,
        )
        val executeNames = resolve(candidates, AgentIntent.EXECUTE).resolvedToolNames
        assertEquals(candidateNames.sorted(), executeNames)
        assertFalse(resolve(candidates, AgentIntent.EXPLORE).resolvedToolNames.contains("get_screen_time"))
    }

    @Test
    fun `untrusted execute intent is clamped to explore`() {
        val candidates = candidateNames.map(::described)
        val profile = resolver.resolve(
            candidates,
            request(AgentIntent.EXECUTE, InputTrust.DERIVED_UNTRUSTED),
        )

        assertEquals(AgentIntent.EXPLORE, profile.effectiveIntent)
        assertFalse(profile.resolvedToolNames.contains("workspace_write_file"))
        assertFalse(profile.resolvedToolNames.any { it.startsWith("mcp__") })
    }

    @Test
    fun `execute retains enabled candidates except descriptor-denied tools`() {
        val allowed = described("future_tool")
        val denied = described("disabled_by_descriptor").let {
            it.copy(descriptor = it.descriptor.copy(defaultApproval = ToolDefaultApproval.DENY))
        }

        val profile = resolver.resolve(listOf(denied, allowed), request(AgentIntent.EXECUTE))

        assertEquals(listOf("future_tool"), profile.resolvedToolNames)
    }

    @Test
    fun `subagent hard allowlist and disabled names are final subtraction filters`() {
        val candidates = candidateNames.map(::described)
        val profile = resolver.resolve(
            candidates,
            request(
                intent = AgentIntent.EXPLORE,
                disabled = setOf("artifact_search"),
                hardAllowed = setOf(
                    "workspace_read_file",
                    "workspace_search_files",
                    "artifact_read",
                    "artifact_search",
                    "search_web",
                ),
                isSubagent = true,
            ),
        )

        assertEquals(
            listOf("artifact_read", "workspace_read_file", "workspace_search_files"),
            profile.resolvedToolNames,
        )
    }

    @Test
    fun `global duplicate names fail closed before filtering`() {
        val candidates = listOf(described("Duplicate"), described("duplicate"))

        try {
            resolver.resolve(candidates, request(AgentIntent.ANSWER))
            fail("Case-folded duplicates must be rejected")
        } catch (error: ToolNameCollisionException) {
            assertEquals(listOf("Duplicate", "duplicate"), error.collidingNames)
        }
    }

    @Test
    fun `descriptor name mismatch fails closed`() {
        val mismatched = described("search_web").let {
            it.copy(descriptor = it.descriptor.copy(toolName = "scrape_web"))
        }

        try {
            resolver.resolve(listOf(mismatched), request(AgentIntent.EXPLORE))
            fail("Mismatched descriptor identity must be rejected")
        } catch (error: ToolDescriptorNameMismatchException) {
            assertEquals("search_web", error.toolName)
            assertEquals("scrape_web", error.descriptorName)
        }
    }

    @Test
    fun `permission digest is canonical and only policy facts affect it`() {
        val search = described("search_web")
        val mcp = described("mcp__server_tool").copy(
            mcpServer = McpServerPolicyContext("server-id", "Display name", needsApproval = false),
        )
        val policyA = PermissionPolicy(
            byCategory = linkedMapOf(
                ToolCategory.WORKSPACE_WRITE to ApprovalAction.ASK,
                ToolCategory.SEARCH to ApprovalAction.AUTO,
            ),
            injectPromptSummary = true,
        )
        val policyB = PermissionPolicy(
            byCategory = linkedMapOf(
                ToolCategory.SEARCH to ApprovalAction.AUTO,
                ToolCategory.WORKSPACE_WRITE to ApprovalAction.ASK,
            ),
            injectPromptSummary = true,
        )
        val first = resolver.resolve(
            listOf(search, mcp),
            request(AgentIntent.EXECUTE, policy = policyA),
        )
        val reorderedAndRenamed = resolver.resolve(
            listOf(mcp.copy(mcpServer = mcp.mcpServer?.copy(serverName = "Renamed")), search),
            request(AgentIntent.EXECUTE, policy = policyB),
        )

        assertEquals(first.permissionDigest, reorderedAndRenamed.permissionDigest)
        assertTrue(first.permissionDigest.matches(Regex("sha256:[0-9a-f]{64}")))

        val approvalChanged = resolver.resolve(
            listOf(search, mcp.copy(mcpServer = mcp.mcpServer?.copy(needsApproval = true))),
            request(AgentIntent.EXECUTE, policy = policyA),
        )
        val riskChanged = resolver.resolve(
            listOf(search.copy(descriptor = search.descriptor.copy(riskLevel = ToolRiskLevel.MEDIUM)), mcp),
            request(AgentIntent.EXECUTE, policy = policyA),
        )
        val configuredApprovalAllowed = resolver.resolve(
            listOf(
                search.copy(approvalPolicy = ToolApprovalPolicyContext(configuredNeedsApproval = false)),
                mcp,
            ),
            request(AgentIntent.EXECUTE, policy = policyA),
        )
        val configuredApprovalRequired = resolver.resolve(
            listOf(
                search.copy(approvalPolicy = ToolApprovalPolicyContext(configuredNeedsApproval = true)),
                mcp,
            ),
            request(AgentIntent.EXECUTE, policy = policyA),
        )
        val actionChanged = resolver.resolve(
            listOf(search, mcp),
            request(
                AgentIntent.EXECUTE,
                policy = policyA.copy(
                    byCategory = policyA.byCategory + (ToolCategory.SEARCH to ApprovalAction.ASK),
                ),
            ),
        )
        val assistantScopeChanged = resolver.resolve(
            listOf(search, mcp),
            request(AgentIntent.EXECUTE, policy = policyA).copy(assistantId = "other-assistant"),
        )
        val workspaceScopeChanged = resolver.resolve(
            listOf(search, mcp),
            request(AgentIntent.EXECUTE, policy = policyA).copy(workspaceId = "other-workspace"),
        )
        assertNotEquals(first.permissionDigest, approvalChanged.permissionDigest)
        assertNotEquals(first.permissionDigest, riskChanged.permissionDigest)
        assertNotEquals(configuredApprovalAllowed.permissionDigest, configuredApprovalRequired.permissionDigest)
        assertNotEquals(first.permissionDigest, actionChanged.permissionDigest)
        assertNotEquals(first.permissionDigest, assistantScopeChanged.permissionDigest)
        assertNotEquals(first.permissionDigest, workspaceScopeChanged.permissionDigest)
        assertNotEquals(
            first.permissionDigest,
            resolver.resolve(
                listOf(search, mcp),
                request(AgentIntent.EXECUTE, policy = policyA).copy(policyVersion = "tool-profile-policy-v2"),
            ).permissionDigest,
        )
    }

    @Test
    fun `implicit and explicit AUTO category policy have the same digest`() {
        val candidates = listOf(described("search_web"))
        val implicit = resolver.resolve(candidates, request(AgentIntent.EXPLORE))
        val explicit = resolver.resolve(
            candidates,
            request(
                AgentIntent.EXPLORE,
                policy = PermissionPolicy(mapOf(ToolCategory.SEARCH to ApprovalAction.AUTO)),
            ),
        )

        assertEquals(implicit.permissionDigest, explicit.permissionDigest)
    }

    @Test
    fun `permission mode changes the permission digest`() {
        val candidates = listOf(described("workspace_write_file"))
        val critical = resolver.resolve(
            candidates,
            request(
                AgentIntent.EXECUTE,
                policy = PermissionPolicy.compatibleDefault(
                    permissionMode = AgentPermissionMode.CONFIRM_CRITICAL,
                ),
            ),
        )
        val fullAccess = resolver.resolve(
            candidates,
            request(
                AgentIntent.EXECUTE,
                policy = PermissionPolicy.compatibleDefault(
                    permissionMode = AgentPermissionMode.FULL_ACCESS,
                ),
            ),
        )

        assertNotEquals(critical.permissionDigest, fullAccess.permissionDigest)
    }

    @Test
    fun `frozen profile ignores new tools but rejects missing tools and policy drift`() {
        val originalCandidates = listOf(described("search_web"), described("get_time_info"))
        val request = request(AgentIntent.EXPLORE)
        val original = resolver.resolve(originalCandidates, request)
        val snapshot = AgentRoutingSnapshot.create(
            intent = request.intent,
            inputTrust = request.inputTrust,
            reasonCode = "test_profile",
            resolvedToolNames = original.resolvedToolNames,
            permissionDigest = original.permissionDigest,
            executionContextDigest =
                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            providerIdleTimeoutMillis = 60_000,
            toolTimeoutMillis = request.defaultToolTimeoutMillis,
            runTimeoutMillis = 600_000,
        )

        val withExtra = resolver.resolveFrozen(
            originalCandidates + described("workspace_read_file"),
            snapshot,
            request,
        )
        assertEquals(original.resolvedToolNames, withExtra.resolvedToolNames)

        val withUnrelatedCollision = resolver.resolveFrozen(
            originalCandidates + described("unrelated") + described("UNRELATED"),
            snapshot,
            request,
        )
        assertEquals(original.resolvedToolNames, withUnrelatedCollision.resolvedToolNames)

        try {
            resolver.resolveFrozen(
                originalCandidates + described("SEARCH_WEB"),
                snapshot,
                request,
            )
            fail("A new alias colliding with a frozen name must fail closed")
        } catch (error: ToolNameCollisionException) {
            assertEquals(listOf("SEARCH_WEB", "search_web"), error.collidingNames)
        }

        try {
            resolver.resolveFrozen(listOf(described("search_web")), snapshot, request)
            fail("Missing frozen tools must fail closed")
        } catch (error: FrozenToolProfileMismatchException) {
            assertEquals(listOf("get_time_info"), error.missingToolNames)
        }

        try {
            resolver.resolveFrozen(
                originalCandidates,
                snapshot,
                request.copy(permissionPolicy = PermissionPolicy(injectPromptSummary = true)),
            )
            fail("Permission drift must fail closed")
        } catch (error: FrozenToolProfileMismatchException) {
            assertTrue(error.permissionDigestChanged)
        }
    }

    private fun resolve(
        candidates: List<DescribedTool>,
        intent: AgentIntent,
    ): ResolvedToolProfile = resolver.resolve(candidates, request(intent))

    private fun request(
        intent: AgentIntent,
        trust: InputTrust = InputTrust.USER_DIRECT,
        policy: PermissionPolicy = PermissionPolicy.compatibleDefault(),
        disabled: Set<String> = emptySet(),
        hardAllowed: Set<String>? = null,
        isSubagent: Boolean = false,
    ) = ToolProfileRequest(
        intent = intent,
        inputTrust = trust,
        assistantId = "assistant",
        workspaceId = "workspace",
        permissionPolicy = policy,
        defaultToolTimeoutMillis = 30_000,
        disabledToolNames = disabled,
        hardAllowedToolNames = hardAllowed,
        isSubagentRun = isSubagent,
    )

    private fun described(name: String): DescribedTool {
        val tool = Tool(
            name = name,
            description = name,
            execute = { listOf(UIMessagePart.Text("ok")) },
        )
        return DescribedTool(tool, ToolDescriptorRegistry.descriptorFor(name))
    }

    private companion object {
        val candidateNames = listOf(
            "search_web",
            "get_time_info",
            "calendar_query",
            "get_screen_time",
            "ask_user",
            "conversation_search",
            "workspace_read_file",
            "workspace_search_files",
            "artifact_read",
            "use_skill",
            "explore_subagent",
            "text_to_speech",
            "workspace_write_file",
            "workspace_shell",
            "memory_tool",
            "mcp__server_tool",
            "future_tool",
        )
    }
}
