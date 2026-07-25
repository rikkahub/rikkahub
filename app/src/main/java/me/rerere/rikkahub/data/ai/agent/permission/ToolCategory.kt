package me.rerere.rikkahub.data.ai.agent.permission

/**
 * 工具类别（学 Codex / Claude Code 的权限分层）。
 * 用于策略映射；默认策略等价于改造前 needsApproval 行为。
 */
enum class ToolCategory {
    SEARCH,
    LOCAL_SAFE,
    LOCAL_SENSITIVE,
    CONVERSATION,
    WORKSPACE_READ,
    WORKSPACE_WRITE,
    WORKSPACE_SHELL,
    SKILL,
    MCP,
    MEMORY,
    UNKNOWN,
    ;

    companion object {
        fun ofToolName(name: String): ToolCategory = when (name) {
            "search_web", "scrape_web" -> SEARCH
            "ask_user",
            "calendar_query",
            "calendar_create",
            "get_screen_time",
            -> LOCAL_SENSITIVE
            "eval_javascript",
            "get_time_info",
            "clipboard_tool",
            "text_to_speech",
            -> LOCAL_SAFE
            "recent_chats", "conversation_search" -> CONVERSATION
            "workspace_read_file" -> WORKSPACE_READ
            "workspace_write_file", "workspace_edit_file" -> WORKSPACE_WRITE
            "workspace_shell" -> WORKSPACE_SHELL
            "use_skill" -> SKILL
            "memory_tool" -> MEMORY
            "explore_subagent" -> LOCAL_SAFE
            else -> when {
                name.startsWith("mcp__") -> MCP
                name.startsWith("search_") -> SEARCH
                else -> UNKNOWN
            }
        }
    }
}
