package me.rerere.rikkahub.data.ai.agent.tools

import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.agent.permission.PermissionPolicy
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation

data class ToolResolveContext(
    val settings: Settings,
    val assistant: Assistant,
    val conversation: Conversation,
    val mode: AgentMode = AgentMode.CHAT,
    val permissionPolicy: PermissionPolicy = PermissionPolicy.compatibleDefault(),
    /**
     * 为 true 时表示当前在 subagent 隔离会话中解析工具。
     * Explore 工具自身不会再注册，防止嵌套 spawn。
     */
    val isSubagentRun: Boolean = false,
    /** 父会话生成状态（Explore 子代理可写入进度文案） */
    val processingStatus: MutableStateFlow<String?>? = null,
)
