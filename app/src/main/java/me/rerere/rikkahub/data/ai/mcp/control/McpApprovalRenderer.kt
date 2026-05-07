package me.rerere.rikkahub.data.ai.mcp.control

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Approval-prompt body renderer for the mcp_* tools.
 *
 * The default prompt path serialises the raw args JSON into a `<pre>` block — for
 * mcp_add and mcp_update that would expose Authorization tokens, API keys, and cookies
 * verbatim to whoever is reading the prompt (in-app dialog or Telegram chat). Spec
 * 2026-05-05-mcp-control-design §6 + §"Approval prompt rendering" require these prompts
 * to use [McpHeaderRedactor] before display.
 *
 * The renderer returns plain text (no HTML / Compose). Each calling surface decides how
 * to escape and present it — Telegram wraps in `<pre>` after HTML-escaping, the in-app
 * approval card renders it as a `Text` block.
 *
 * Returns null when the tool isn't an MCP tool that needs custom rendering — callers
 * fall back to the generic args display.
 */
object McpApprovalRenderer {

    fun render(toolName: String, args: JsonObject): String? = when (toolName) {
        "mcp_add" -> renderAdd(args)
        "mcp_update" -> renderUpdate(args)
        "mcp_delete" -> renderDelete(args)
        "mcp_set_enabled" -> renderSetEnabled(args)
        "mcp_set_tool_approval" -> renderSetToolApproval(args)
        else -> null
    }

    private fun renderAdd(args: JsonObject): String {
        val name = args["name"]?.jsonPrimitive?.contentOrNull ?: "(unnamed)"
        val transport = args["transport"]?.jsonPrimitive?.contentOrNull ?: "(unknown)"
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: "(no url)"
        val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val headers = parseHeadersForDisplay(args)
        return buildString {
            append("Add MCP server \"$name\"\n\n")
            append("Transport: $transport\n")
            append("URL: $url\n")
            appendHeadersBlock(this, headers)
            append("\nEnabled: ${if (enabled) "yes" else "no"}")
        }
    }

    private fun renderUpdate(args: JsonObject): String {
        val name = args["name"]?.jsonPrimitive?.contentOrNull ?: "(unnamed)"
        val transport = args["transport"]?.jsonPrimitive?.contentOrNull ?: "(unknown)"
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: "(no url)"
        val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val headers = parseHeadersForDisplay(args)
        return buildString {
            append("Update MCP server \"$name\"\n\n")
            append("Transport: $transport\n")
            append("URL: $url\n")
            appendHeadersBlock(this, headers)
            append("\nEnabled: ${if (enabled) "yes" else "no"}")
        }
    }

    private fun renderDelete(args: JsonObject): String {
        val id = args["id"]?.jsonPrimitive?.contentOrNull ?: "(unknown)"
        return "Delete MCP server (id $id)\n\nThis will disconnect the server and remove all of its tools from the assistant."
    }

    private fun renderSetEnabled(args: JsonObject): String {
        val id = args["id"]?.jsonPrimitive?.contentOrNull ?: "(unknown)"
        val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull
        val verb = when (enabled) {
            true -> "Enable"
            false -> "Disable"
            null -> "Toggle"
        }
        return "$verb MCP server (id $id)"
    }

    private fun renderSetToolApproval(args: JsonObject): String {
        val serverId = args["server_id"]?.jsonPrimitive?.contentOrNull ?: "(unknown)"
        val toolName = args["tool_name"]?.jsonPrimitive?.contentOrNull ?: "(unknown)"
        val needs = args["needs_approval"]?.jsonPrimitive?.booleanOrNull
        val verb = when (needs) {
            true -> "Require approval"
            false -> "Drop approval requirement"
            null -> "Update approval flag"
        }
        return "$verb for tool \"$toolName\" on MCP server (id $serverId)"
    }

    private fun parseHeadersForDisplay(args: JsonObject): List<Pair<String, String>> {
        val arr = runCatching { args["headers"]?.jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { entry ->
            val obj = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val value = obj["value"]?.jsonPrimitive?.contentOrNull ?: ""
            name to value
        }
    }

    private fun appendHeadersBlock(out: StringBuilder, headers: List<Pair<String, String>>) {
        if (headers.isEmpty()) {
            out.append("Headers: (none)\n")
            return
        }
        out.append("Headers:\n")
        for ((name, value) in McpHeaderRedactor.redactHeaders(headers)) {
            out.append("  ").append(name).append(": ").append(value).append('\n')
        }
        val (sensitive, plain) = McpHeaderRedactor.classify(headers)
        out.append("(")
        out.append(sensitive).append(" secret header").append(if (sensitive == 1) "" else "s")
        out.append(", ")
        out.append(plain).append(" plain")
        out.append(")\n")
    }
}
