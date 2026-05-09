package me.rerere.rikkahub.data.ai.mcp.control

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlin.uuid.Uuid

/**
 * The nine LLM-callable mcp_* tools that let an assistant CRUD MCP servers without the
 * user opening Settings. Builders here are pure factories — they capture the manager and
 * settings store, then return a [Tool] whose execute lambda parses args, validates, calls
 * through to the existing infra, and returns a JSON envelope.
 *
 * All side-effecting tools share a common shape:
 *   - validate args (return `{error, detail}` on bad input)
 *   - mutate the settings store (PreferencesStore takes care of persistence + Flow refresh)
 *   - call McpManager methods to keep its in-memory client map in sync
 *   - return the canonical "server view" envelope so the LLM gets the post-state
 *
 * The header redactor + URL guard are shared with the approval-prompt rendering layer so
 * the user sees the same redacted view the tool result returns.
 */
private const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 15
private const val MAX_CONNECT_TIMEOUT_SECONDS = 60

/** ---------- Shared helpers ---------- */

private fun errEnv(error: String, detail: String, extra: Map<String, JsonElement> = emptyMap()): List<UIMessagePart> {
    val obj = buildJsonObject {
        put("error", error)
        put("detail", detail)
        for ((k, v) in extra) put(k, v)
    }
    return listOf(UIMessagePart.Text(obj.toString()))
}

private fun okEnv(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): List<UIMessagePart> {
    return listOf(UIMessagePart.Text(buildJsonObject(builder).toString()))
}

/**
 * Render the canonical server-view envelope used by mcp_add / mcp_update / mcp_test results
 * and as elements of mcp_list. Always redacts headers — the LLM doesn't need plain bytes
 * back since it just typed them. If the server isn't registered with the manager, status
 * defaults to DISABLED (the user can have a config in settings with enable=false).
 */
private fun serverViewEnvelope(
    config: McpServerConfig,
    status: McpStatus?,
    builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
): JsonObject = buildJsonObject {
    put("id", config.id.toString())
    put("name", config.commonOptions.name)
    put("transport", transportLabel(config))
    put("url", urlOf(config))
    put("enabled", config.commonOptions.enable)
    val (statusLabel, errorMessage) = renderStatus(config.commonOptions.enable, status)
    put("status", statusLabel)
    put("tool_count", config.commonOptions.tools.size)
    if (errorMessage != null) put("error", errorMessage)
    putJsonArray("headers") {
        McpHeaderRedactor.redactHeaders(config.commonOptions.headers).forEach { (n, v) ->
            addJsonObject {
                put("name", n)
                put("value", v)
            }
        }
    }
    builder()
}

private fun transportLabel(config: McpServerConfig): String = when (config) {
    is McpServerConfig.SseTransportServer -> "sse"
    is McpServerConfig.StreamableHTTPServer -> "streamable_http"
}

private fun urlOf(config: McpServerConfig): String = when (config) {
    is McpServerConfig.SseTransportServer -> config.url
    is McpServerConfig.StreamableHTTPServer -> config.url
}

private fun renderStatus(enabled: Boolean, status: McpStatus?): Pair<String, String?> {
    if (!enabled) return "DISABLED" to null
    return when (status) {
        null, McpStatus.Idle, McpStatus.Connecting -> "CONNECTING" to null
        McpStatus.Connected -> "CONNECTED" to null
        is McpStatus.Reconnecting -> "CONNECTING" to "reconnecting (attempt ${status.attempt}/${status.maxAttempts})"
        is McpStatus.Error -> "ERROR" to status.message
    }
}

private fun parseHeaders(raw: JsonElement?): List<Pair<String, String>> {
    val arr = raw?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
    return arr.mapNotNull { entry ->
        val obj = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val value = obj["value"]?.jsonPrimitive?.contentOrNull ?: ""
        name to value
    }
}

private fun parseUuid(raw: String?): Uuid? = raw?.let { runCatching { Uuid.parse(it.trim()) }.getOrNull() }

private fun buildConfig(
    id: Uuid,
    transport: String,
    name: String,
    url: String,
    enabled: Boolean,
    headers: List<Pair<String, String>>,
    existingTools: List<McpTool> = emptyList(),
): McpServerConfig {
    val common = McpCommonOptions(
        enable = enabled,
        name = name,
        headers = headers,
        tools = existingTools,
    )
    return when (transport) {
        "sse" -> McpServerConfig.SseTransportServer(id = id, commonOptions = common, url = url)
        "streamable_http" -> McpServerConfig.StreamableHTTPServer(id = id, commonOptions = common, url = url)
        else -> error("unsupported transport (validation should have caught this earlier): $transport")
    }
}

/**
 * Wait until the manager reports a terminal status (Connected or Error) for [serverId], or
 * the timeout elapses. Returns the last observed status (which may still be Connecting if
 * the timeout was hit — caller renders that as CONNECTING and the LLM can poll with
 * mcp_test).
 */
private suspend fun awaitTerminal(manager: McpManager, serverId: Uuid, timeoutMs: Long): McpStatus? {
    return withTimeoutOrNull(timeoutMs) {
        while (true) {
            val s = manager.syncingStatus.value[serverId]
            when (s) {
                McpStatus.Connected, is McpStatus.Error -> return@withTimeoutOrNull s
                else -> delay(150)
            }
        }
        @Suppress("UNREACHABLE_CODE") null
    } ?: manager.syncingStatus.value[serverId]
}

/**
 * Classify a raw McpStatus.Error message into a normalized `error_kind` plus a short
 * actionable hint. The MCP SDK and underlying transport (OkHttp / kotlinx-serialization)
 * surface failures as opaque exception messages — without classification the LLM can't
 * tell a network problem from a malformed-tool-def from an auth failure, and tells the
 * user the wrong thing to fix.
 *
 * Returns (error_kind, hint). Both end up in the rollback envelope.
 */
internal fun classifyMcpError(message: String): Pair<String, String> {
    val m = message.lowercase()
    return when {
        // kotlinx-serialization shape: "Field 'X' is required for type with serial name 'io.modelcontextprotocol...'"
        m.contains("field '") && m.contains("is required for type") -> {
            "remote_invalid_tool_def" to
                "The server returned a tool definition missing a required field. " +
                "Fix the server so each tool entry has all required MCP fields (name, description, inputSchema)."
        }
        m.contains("missingfieldexception") || m.contains("serializationexception") -> {
            "remote_invalid_response" to
                "The server's response did not match the MCP protocol. Check that the server " +
                "implements the MCP spec and returns the expected JSON shapes."
        }
        m.contains("failed to connect to") || m.contains("connectexception") ||
            m.contains("connection refused") -> {
            "connect_failed" to
                "Couldn't open a TCP connection to the URL. If the server runs on this device, " +
                "make sure it's bound to 0.0.0.0 (or the LAN IP) — not 127.0.0.1 — and that the " +
                "port matches the URL."
        }
        m.contains("sockettimeoutexception") || m.contains("read timed out") ||
            m.contains("connect timed out") -> {
            "request_timeout" to
                "The server didn't respond before the timeout. Try a longer connect_timeout_seconds " +
                "or check that the server is actually serving requests."
        }
        m.contains("unknownhostexception") || m.contains("no address associated") -> {
            "host_not_found" to
                "DNS couldn't resolve the host. Check the URL spelling and that the device can reach this hostname."
        }
        m.contains(" 401") || m.contains("unauthorized") -> {
            "auth_required" to
                "The server returned 401. Check that the right Authorization / API-key header is set."
        }
        m.contains(" 403") || m.contains("forbidden") -> {
            "auth_forbidden" to
                "The server returned 403. The credentials are recognised but not allowed to access this endpoint."
        }
        m.contains(" 404") || m.contains("not found") -> {
            "endpoint_not_found" to
                "The server returned 404. Verify the URL points at the MCP endpoint (often /mcp or /sse)."
        }
        else -> "connect_failed" to
            "Server didn't reach Connected state. Check the URL, transport, and headers, then retry."
    }
}

/** ---------- Tool factories ---------- */

fun mcpListTool(settingsStore: SettingsStore, manager: McpManager): Tool = Tool(
    name = "mcp_list",
    description = """
        List all configured MCP servers with their connection status and tool counts. Read-only.
        Use this to find out what's already wired up before deciding whether to add a new server,
        or to confirm a previously-added server is healthy. Headers are redacted in the result.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
    execute = {
        val servers = settingsStore.settingsFlow.value.mcpServers
        val statuses = manager.syncingStatus.value
        val arr = buildJsonArray {
            servers.forEach { config ->
                add(serverViewEnvelope(config, statuses[config.id]))
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject { put("servers", arr) }.toString()))
    },
)

fun mcpGetTool(settingsStore: SettingsStore, manager: McpManager): Tool = Tool(
    name = "mcp_get",
    description = """
        Return the full configuration of a single MCP server by id, including its redacted
        headers and the list of tools it currently exposes. Read-only.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "UUID of the server, as returned by mcp_list or mcp_add")
                })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val rawId = params["id"]?.jsonPrimitive?.contentOrNull
        val serverId = parseUuid(rawId)
            ?: return@Tool errEnv("invalid_id", "id is required and must be a valid UUID; got '$rawId'")
        val config = settingsStore.settingsFlow.value.mcpServers.firstOrNull { it.id == serverId }
            ?: return@Tool errEnv("unknown_mcp_server_id", "no MCP server registered with id $serverId")
        val status = manager.syncingStatus.value[serverId]
        val toolList = buildJsonArray {
            config.commonOptions.tools.forEach { tool ->
                addJsonObject {
                    put("name", tool.name)
                    put("description", tool.description ?: "")
                    put("enabled", tool.enable)
                    put("needs_approval", tool.needsApproval)
                }
            }
        }
        listOf(UIMessagePart.Text(serverViewEnvelope(config, status) { put("tools", toolList) }.toString()))
    },
)

fun mcpAddTool(settingsStore: SettingsStore, manager: McpManager): Tool = Tool(
    name = "mcp_add",
    description = """
        Add a new MCP server. Pass transport="sse" or "streamable_http", a unique name (≤60
        chars), an http(s) url, optional enabled (default true), and optional headers as a
        list of {name, value} pairs (max 32 entries; sensitive values like Authorization or
        X-Api-Key are redacted in display layers but stored verbatim).

        After registering, the tool waits up to connect_timeout_seconds (default 15, max 60)
        for the first sync to complete and returns the resulting status. If still CONNECTING
        when the timeout elapses, poll with mcp_test.

        Typical flow: install your MCP server in Termux or on a remote box, expose it over
        HTTP/SSE (e.g. via mcp-proxy), then mcp_add with the URL. Loopback URLs (localhost,
        127.x.x.x, ::1) are accepted only in interactive contexts — scheduled jobs and other
        headless callers cannot wire up loopback servers.

        "Always Allow" is INTENTIONALLY not offered for this tool — a hostile MCP server can
        exfiltrate everything the assistant has access to, so each install is per-call confirmed.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("transport", buildJsonObject {
                    put("type", "string")
                    put("description", "Either 'sse' or 'streamable_http'. Stdio is intentionally unsupported.")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Human-readable name shown to the user. Must be unique among existing servers (case-insensitive). Max 60 chars.")
                })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "http:// or https:// URL of the MCP endpoint. Other schemes are rejected.")
                })
                put("enabled", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether the server is enabled on creation. Defaults to true.")
                })
                put("headers", buildJsonObject {
                    put("type", "array")
                    put("description", "Optional HTTP headers as [{name, value}, ...]. Max 32 entries.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject { put("type", "string") })
                            put("value", buildJsonObject { put("type", "string") })
                        })
                        put("required", buildJsonArray { add("name"); add("value") })
                    })
                })
                put("connect_timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "How long to wait for the first sync. Default 15, max 60.")
                })
            },
            required = listOf("transport", "name", "url"),
        )
    },
    needsApproval = true,
    execute = { args ->
        val params = args.jsonObject
        val transport = params["transport"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return@Tool errEnv("invalid_transport", "transport is required and must be 'sse' or 'streamable_http'")
        if (transport != "sse" && transport != "streamable_http") {
            return@Tool errEnv(
                "unsupported_transport",
                "unsupported transport: $transport. Supported: sse, streamable_http"
            )
        }
        val rawName = params["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val rawUrl = params["url"]?.jsonPrimitive?.contentOrNull ?: ""
        val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val headers = parseHeaders(params["headers"])
        val timeoutSec = (params["connect_timeout_seconds"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CONNECT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_CONNECT_TIMEOUT_SECONDS)

        val urlCheck = McpUrlGuard.check(rawUrl, headless = McpUrlGuard.currentlyHeadless())
        if (urlCheck is McpUrlGuard.Result.Reject) {
            return@Tool errEnv(urlCheck.error, urlCheck.detail)
        }
        val existing = settingsStore.settingsFlow.value.mcpServers
        val nameCheck = McpControlValidation.validateName(rawName, existing, excludingId = null)
        if (nameCheck is McpControlValidation.Result.Reject) {
            return@Tool errEnv(nameCheck.error, nameCheck.detail)
        }
        val headerCheck = McpControlValidation.validateHeaders(headers)
        if (headerCheck is McpControlValidation.Result.Reject) {
            return@Tool errEnv(headerCheck.error, headerCheck.detail)
        }
        val name = (nameCheck as McpControlValidation.Result.Ok).value
        val newId = Uuid.random()
        val config = buildConfig(
            id = newId,
            transport = transport,
            name = name,
            url = rawUrl.trim(),
            enabled = enabled,
            headers = headers,
        )
        settingsStore.update { old -> old.copy(mcpServers = old.mcpServers + config) }
        if (enabled) {
            manager.addClient(config)
            awaitTerminal(manager, newId, timeoutSec * 1000L)
        }
        val finalStatus = manager.syncingStatus.value[newId]
        // Rollback on permanent failure (Error). Without this the failed config sits in
        // settings; the next mcp_add with the same name hits name_already_in_use and the
        // user has to manually mcp_delete first. Connecting/null timeouts are KEPT
        // because the LLM is told to poll with mcp_test — pulling the row out from
        // under the next poll would be a worse UX than leaving a row marked CONNECTING.
        if (finalStatus is McpStatus.Error) {
            manager.removeClient(config)
            settingsStore.update { s -> s.copy(mcpServers = s.mcpServers.filter { it.id != newId }) }
            val (kind, hint) = classifyMcpError(finalStatus.message)
            return@Tool errEnv(
                kind,
                "MCP add failed: ${finalStatus.message}. $hint Server config rolled back; you can retry with the same name.",
                extra = mapOf(
                    "raw_error" to kotlinx.serialization.json.JsonPrimitive(finalStatus.message),
                    "name" to kotlinx.serialization.json.JsonPrimitive(name),
                    "url" to kotlinx.serialization.json.JsonPrimitive(rawUrl.trim()),
                ),
            )
        }
        // Re-read after sync — sync mutates tool list.
        val finalConfig = settingsStore.settingsFlow.value.mcpServers.firstOrNull { it.id == newId } ?: config
        listOf(UIMessagePart.Text(serverViewEnvelope(finalConfig, finalStatus).toString()))
    },
)

fun mcpUpdateTool(settingsStore: SettingsStore, manager: McpManager): Tool = Tool(
    name = "mcp_update",
    description = """
        Replace an existing MCP server's configuration in one shot. Body matches mcp_add plus
        an `id` field. Internally tears down the old client and adds the new one to ensure
        transport / URL / header changes take effect. The tool list is preserved across
        the update; sync runs automatically after re-add.

        Like mcp_add, "Always Allow" is intentionally NOT offered: a hostile updated config
        could exfiltrate everything the assistant has access to.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
                put("transport", buildJsonObject { put("type", "string") })
                put("name", buildJsonObject { put("type", "string") })
                put("url", buildJsonObject { put("type", "string") })
                put("enabled", buildJsonObject { put("type", "boolean") })
                put("headers", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject { put("type", "string") })
                            put("value", buildJsonObject { put("type", "string") })
                        })
                        put("required", buildJsonArray { add("name"); add("value") })
                    })
                })
                put("connect_timeout_seconds", buildJsonObject { put("type", "integer") })
            },
            required = listOf("id", "transport", "name", "url"),
        )
    },
    needsApproval = true,
    execute = { args ->
        val params = args.jsonObject
        val rawId = params["id"]?.jsonPrimitive?.contentOrNull
        val serverId = parseUuid(rawId)
            ?: return@Tool errEnv("invalid_id", "id is required and must be a valid UUID; got '$rawId'")
        val transport = params["transport"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return@Tool errEnv("invalid_transport", "transport is required")
        if (transport != "sse" && transport != "streamable_http") {
            return@Tool errEnv("unsupported_transport", "unsupported transport: $transport")
        }
        val rawName = params["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val rawUrl = params["url"]?.jsonPrimitive?.contentOrNull ?: ""
        val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val headers = parseHeaders(params["headers"])
        val timeoutSec = (params["connect_timeout_seconds"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CONNECT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_CONNECT_TIMEOUT_SECONDS)

        val all = settingsStore.settingsFlow.value.mcpServers
        val old = all.firstOrNull { it.id == serverId }
            ?: return@Tool errEnv("unknown_mcp_server_id", "no MCP server registered with id $serverId")
        val urlCheck = McpUrlGuard.check(rawUrl, headless = McpUrlGuard.currentlyHeadless())
        if (urlCheck is McpUrlGuard.Result.Reject) {
            return@Tool errEnv(urlCheck.error, urlCheck.detail)
        }
        val nameCheck = McpControlValidation.validateName(rawName, all, excludingId = serverId.toString())
        if (nameCheck is McpControlValidation.Result.Reject) {
            return@Tool errEnv(nameCheck.error, nameCheck.detail)
        }
        val headerCheck = McpControlValidation.validateHeaders(headers)
        if (headerCheck is McpControlValidation.Result.Reject) {
            return@Tool errEnv(headerCheck.error, headerCheck.detail)
        }
        val name = (nameCheck as McpControlValidation.Result.Ok).value
        val newConfig = buildConfig(
            id = serverId,
            transport = transport,
            name = name,
            url = rawUrl.trim(),
            enabled = enabled,
            headers = headers,
            existingTools = old.commonOptions.tools, // preserve known tools across update
        )
        manager.removeClient(old)
        settingsStore.update { s ->
            s.copy(mcpServers = s.mcpServers.map { if (it.id == serverId) newConfig else it })
        }
        if (enabled) {
            manager.addClient(newConfig)
            awaitTerminal(manager, serverId, timeoutSec * 1000L)
        }
        val finalConfig = settingsStore.settingsFlow.value.mcpServers.firstOrNull { it.id == serverId } ?: newConfig
        val finalStatus = manager.syncingStatus.value[serverId]
        listOf(UIMessagePart.Text(serverViewEnvelope(finalConfig, finalStatus).toString()))
    },
)

fun mcpDeleteTool(settingsStore: SettingsStore, manager: McpManager): Tool = Tool(
    name = "mcp_delete",
    description = """
        Remove an MCP server. Disconnects the client, cancels any pending reconnect, and
        deletes the config from settings. The user's existing assistants will lose access
        to any tools that server exposed. Irreversible.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { put("id", buildJsonObject { put("type", "string") }) },
            required = listOf("id"),
        )
    },
    needsApproval = true,
    execute = { args ->
        val params = args.jsonObject
        val rawId = params["id"]?.jsonPrimitive?.contentOrNull
        val serverId = parseUuid(rawId)
            ?: return@Tool errEnv("invalid_id", "id is required and must be a valid UUID; got '$rawId'")
        val all = settingsStore.settingsFlow.value.mcpServers
        val old = all.firstOrNull { it.id == serverId }
            ?: return@Tool errEnv("unknown_mcp_server_id", "no MCP server registered with id $serverId")
        manager.removeClient(old)
        settingsStore.update { s -> s.copy(mcpServers = s.mcpServers.filter { it.id != serverId }) }
        // Drop any assistant references to this server so the LLM doesn't keep trying to
        // call its (now-orphan) tools. Mirrors the cleanup done by PreferencesStore.
        settingsStore.update { s ->
            s.copy(
                assistants = s.assistants.map { a ->
                    a.copy(mcpServers = a.mcpServers.filter { it != serverId }.toSet())
                }
            )
        }
        okEnv {
            put("deleted", true)
            put("id", serverId.toString())
            put("name", old.commonOptions.name)
        }
    },
)

fun mcpSetEnabledTool(settingsStore: SettingsStore, manager: McpManager): Tool = Tool(
    name = "mcp_set_enabled",
    description = """
        Toggle a server's enabled flag. Disabling tears down its client; enabling reconnects.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
                put("enabled", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("id", "enabled"),
        )
    },
    needsApproval = true,
    execute = { args ->
        val params = args.jsonObject
        val rawId = params["id"]?.jsonPrimitive?.contentOrNull
        val serverId = parseUuid(rawId)
            ?: return@Tool errEnv("invalid_id", "id is required and must be a valid UUID; got '$rawId'")
        val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: return@Tool errEnv("invalid_enabled", "enabled is required and must be true/false")
        val all = settingsStore.settingsFlow.value.mcpServers
        val old = all.firstOrNull { it.id == serverId }
            ?: return@Tool errEnv("unknown_mcp_server_id", "no MCP server registered with id $serverId")
        if (old.commonOptions.enable == enabled) {
            // No-op: still return the current view so the LLM knows.
            return@Tool listOf(
                UIMessagePart.Text(
                    serverViewEnvelope(old, manager.syncingStatus.value[serverId]).toString()
                )
            )
        }
        val newConfig = old.clone(commonOptions = old.commonOptions.copy(enable = enabled))
        settingsStore.update { s ->
            s.copy(mcpServers = s.mcpServers.map { if (it.id == serverId) newConfig else it })
        }
        // Manager's settings-flow collector will pick up the change and add/remove the
        // client. We don't await sync here — the call returns the post-write view.
        listOf(
            UIMessagePart.Text(
                serverViewEnvelope(newConfig, manager.syncingStatus.value[serverId]).toString()
            )
        )
    },
)

fun mcpTestTool(settingsStore: SettingsStore, manager: McpManager): Tool = Tool(
    name = "mcp_test",
    description = """
        Force a re-connect + tool re-sync for a server right now and report the result. Read-only
        from the user's perspective (no config change), but the connection is exercised.
        Useful as a poll companion to mcp_add when the initial sync is still running, and as
        a quick "is it really alive" check. Resets the per-server reconnect-backoff counter
        so the next failure starts at the lowest delay.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
                put("wait_seconds", buildJsonObject { put("type", "integer") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val rawId = params["id"]?.jsonPrimitive?.contentOrNull
        val serverId = parseUuid(rawId)
            ?: return@Tool errEnv("invalid_id", "id is required and must be a valid UUID; got '$rawId'")
        val timeoutSec = (params["wait_seconds"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CONNECT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_CONNECT_TIMEOUT_SECONDS)
        val current = settingsStore.settingsFlow.value.mcpServers.firstOrNull { it.id == serverId }
            ?: return@Tool errEnv("unknown_mcp_server_id", "no MCP server registered with id $serverId")
        if (!current.commonOptions.enable) {
            return@Tool errEnv("server_disabled", "server '${current.commonOptions.name}' is disabled; enable it first with mcp_set_enabled")
        }
        manager.forceResync(serverId)
        awaitTerminal(manager, serverId, timeoutSec * 1000L)
        val finalConfig = settingsStore.settingsFlow.value.mcpServers.firstOrNull { it.id == serverId } ?: current
        val finalStatus = manager.syncingStatus.value[serverId]
        listOf(UIMessagePart.Text(serverViewEnvelope(finalConfig, finalStatus).toString()))
    },
)

fun mcpListToolsTool(settingsStore: SettingsStore, manager: McpManager): Tool = Tool(
    name = "mcp_list_tools",
    description = """
        List the MCP tools exposed by one server (when id is provided), or aggregated across
        all enabled servers (when id is null/absent). Each entry includes server name + id,
        tool name, description, and whether it requires approval.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = emptyList(),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val rawId = params["id"]?.jsonPrimitive?.contentOrNull
        val all = settingsStore.settingsFlow.value.mcpServers
        val targets = if (rawId.isNullOrBlank()) {
            all.filter { it.commonOptions.enable }
        } else {
            val serverId = parseUuid(rawId)
                ?: return@Tool errEnv("invalid_id", "id is required and must be a valid UUID; got '$rawId'")
            val one = all.firstOrNull { it.id == serverId }
                ?: return@Tool errEnv("unknown_mcp_server_id", "no MCP server registered with id $serverId")
            listOf(one)
        }
        val arr = buildJsonArray {
            for (server in targets) {
                for (tool in server.commonOptions.tools) {
                    addJsonObject {
                        put("server_id", server.id.toString())
                        put("server_name", server.commonOptions.name)
                        put("tool_name", tool.name)
                        put("description", tool.description ?: "")
                        put("enabled", tool.enable)
                        put("needs_approval", tool.needsApproval)
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject { put("tools", arr) }.toString()))
    },
)

fun mcpSetToolApprovalTool(settingsStore: SettingsStore): Tool = Tool(
    name = "mcp_set_tool_approval",
    description = """
        Set the needsApproval flag on a single MCP tool exposed by a given server. Useful
        for marking specific tools as side-effecting on a server whose tool list is
        otherwise read-only — without flipping approval for every other call.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("server_id", buildJsonObject { put("type", "string") })
                put("tool_name", buildJsonObject { put("type", "string") })
                put("needs_approval", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("server_id", "tool_name", "needs_approval"),
        )
    },
    needsApproval = true,
    execute = { args ->
        val params = args.jsonObject
        val rawServerId = params["server_id"]?.jsonPrimitive?.contentOrNull
        val serverId = parseUuid(rawServerId)
            ?: return@Tool errEnv("invalid_id", "server_id is required and must be a valid UUID; got '$rawServerId'")
        val toolName = params["tool_name"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_tool_name", "tool_name is required")
        val needs = params["needs_approval"]?.jsonPrimitive?.booleanOrNull
            ?: return@Tool errEnv("invalid_needs_approval", "needs_approval is required (true/false)")
        val all = settingsStore.settingsFlow.value.mcpServers
        val server = all.firstOrNull { it.id == serverId }
            ?: return@Tool errEnv("unknown_mcp_server_id", "no MCP server registered with id $serverId")
        val tool = server.commonOptions.tools.firstOrNull { it.name == toolName }
            ?: return@Tool errEnv(
                "unknown_tool_name",
                "server '${server.commonOptions.name}' does not currently expose a tool named '$toolName'"
            )
        if (tool.needsApproval == needs) {
            return@Tool okEnv {
                put("server_id", serverId.toString())
                put("tool_name", toolName)
                put("needs_approval", needs)
                put("changed", false)
            }
        }
        val newServer = server.clone(
            commonOptions = server.commonOptions.copy(
                tools = server.commonOptions.tools.map { t ->
                    if (t.name == toolName) t.copy(needsApproval = needs) else t
                }
            )
        )
        settingsStore.update { s ->
            s.copy(mcpServers = s.mcpServers.map { if (it.id == serverId) newServer else it })
        }
        okEnv {
            put("server_id", serverId.toString())
            put("tool_name", toolName)
            put("needs_approval", needs)
            put("changed", true)
        }
    },
)
