package me.rerere.rikkahub.data.ai.agent.tools.providers

import android.util.Log
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.agent.tools.ToolProvider
import me.rerere.rikkahub.data.ai.agent.tools.ToolProviderOrder
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

class WorkspaceToolProvider(
    private val workspaceRepository: WorkspaceRepository,
) : ToolProvider {
    override val order: Int = ToolProviderOrder.WORKSPACE

    override fun isEnabled(ctx: ToolResolveContext): Boolean =
        ctx.assistant.workspaceId != null

    override suspend fun provide(ctx: ToolResolveContext): List<Tool> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(TAG, "skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}")
            return emptyList()
        }
        return createWorkspaceTools(
            workspaceId = workspaceId,
            workspaceRepository = workspaceRepository,
            cwd = ctx.conversation.workspaceCwd,
        )
    }

    companion object {
        private const val TAG = "WorkspaceToolProvider"
    }
}
