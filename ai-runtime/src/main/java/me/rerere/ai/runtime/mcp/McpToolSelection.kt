package me.rerere.ai.runtime.mcp

import me.rerere.ai.runtime.contract.AssistantConfig
import kotlin.uuid.Uuid

// Pure server/tool selection for a given assistant — extracted from getAllAvailableTools so the
// load-bearing rule (a server's tools are included iff the server is enabled AND its id is in the
// TARGET assistant's allowlist, NOT the global current assistant's) is JVM-unit-testable without the
// app settings store. Issue #201: a subagent runs as a different assistant; selecting by the
// passed-in assistant is what keeps a subagent from inheriting the parent's MCP servers.
fun selectMcpToolsForAssistant(
    mcpServers: List<McpServerConfig>,
    assistant: AssistantConfig,
): List<Pair<Uuid, McpTool>> =
    mcpServers
        .filter { it.commonOptions.enable && it.id in assistant.mcpServers }
        .flatMap { server ->
            server.commonOptions.tools
                .filter { tool -> tool.enable }
                .map { tool -> server.id to tool }
        }
