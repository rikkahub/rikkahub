package me.rerere.rikkahub.data.ai.agent.tools.providers

import android.util.Log
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.agent.permission.DescribedTool
import me.rerere.rikkahub.data.ai.agent.permission.ToolApprovalPolicyContext
import me.rerere.rikkahub.data.ai.agent.permission.ToolDescriptorRegistry
import me.rerere.rikkahub.data.ai.agent.tools.ToolProvider
import me.rerere.rikkahub.data.ai.agent.tools.ToolProviderOrder
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.ai.agent.tools.WorkspaceToolPolicy
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

class WorkspaceToolProvider(
    private val workspaceRepository: WorkspaceRepository,
) : ToolProvider {
    override val order: Int = ToolProviderOrder.WORKSPACE

    override fun isEnabled(ctx: ToolResolveContext): Boolean =
        ctx.assistant.workspaceId != null

    override suspend fun provide(ctx: ToolResolveContext): List<Tool> =
        resolveWorkspaceTools(ctx)?.tools.orEmpty()

    override suspend fun provideWithDescriptors(ctx: ToolResolveContext): List<DescribedTool> {
        val resolved = resolveWorkspaceTools(ctx) ?: return emptyList()
        return resolved.tools.map { tool ->
            DescribedTool(
                tool = tool,
                descriptor = ToolDescriptorRegistry.descriptorFor(tool),
                approvalPolicy = ToolApprovalPolicyContext(
                    configuredNeedsApproval = resolveWorkspaceToolApproval(tool.name, resolved.approvalOverrides),
                ),
            )
        }
    }

    private suspend fun resolveWorkspaceTools(ctx: ToolResolveContext): ResolvedWorkspaceTools? {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return null
        var expectedFrozenWorkspace: me.rerere.workspace.Workspace? = null
        val approvalOverrides = when (val policy = ctx.workspaceToolPolicy) {
            WorkspaceToolPolicy.FrozenAbsent -> return null
            is WorkspaceToolPolicy.Frozen -> {
                if (
                    policy.workspace.id != workspaceId ||
                    policy.workspace.shellStatus != WorkspaceShellStatus.READY
                ) {
                    Log.d(TAG, "skip frozen workspace tools, workspace=$workspaceId, status=${policy.workspace.shellStatus}")
                    return null
                }
                expectedFrozenWorkspace = policy.workspace
                policy.approvalOverrides
            }
            WorkspaceToolPolicy.Live -> {
                val workspace = workspaceRepository.getById(workspaceId) ?: return null
                if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
                    Log.d(TAG, "skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}")
                    return null
                }
                workspace.toolApprovalOverrides()
            }
        }
        val resolvedTools = createWorkspaceTools(
            workspaceId = workspaceId,
            workspaceRepository = workspaceRepository,
            cwd = ctx.conversation.workspaceCwd,
            approvalOverrides = approvalOverrides,
            expectedWorkspaceRoot = expectedFrozenWorkspace?.root,
        )
        return ResolvedWorkspaceTools(resolvedTools, approvalOverrides)
    }

    private data class ResolvedWorkspaceTools(
        val tools: List<Tool>,
        val approvalOverrides: Map<String, Boolean>,
    )

    companion object {
        private const val TAG = "WorkspaceToolProvider"
    }
}
