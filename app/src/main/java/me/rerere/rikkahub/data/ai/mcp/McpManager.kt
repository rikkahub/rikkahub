package me.rerere.rikkahub.data.ai.mcp

import android.util.Log
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.StringValues
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.checkDifferent
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val TAG = "McpManager"

// These maps are structurally mutated (put/remove) AND iterated (.entries/.keys)
// concurrently from the parallel per-config add/remove launches, the
// Dispatchers.IO add/remove/reconnect methods, and the arbitrary-thread
// transport onClose/onError callbacks. A plain LinkedHashMap under that pattern
// is undefined behavior (bucket corruption, lost entries -> leaked Client with a
// live transport, or ConcurrentModificationException). ConcurrentHashMap makes
// each op and each weakly-consistent iteration thread-safe; keys/values are
// always non-null here, so its null prohibition is a non-issue.
internal fun <K : Any, V : Any> newMcpConcurrentMap(): MutableMap<K, V> = ConcurrentHashMap()

private const val MAX_RECONNECT_ATTEMPTS = 5
private const val BASE_RECONNECT_DELAY_MS = 1000L
private const val MAX_RECONNECT_DELAY_MS = 30000L

class McpManager(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val filesManager: FilesManager,
) {
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // This client also backs the long-lived SSE keepalive GET (see `client` below), so
        // readTimeout is an inter-byte inactivity ceiling on that stream — not just the POSTs.
        // Keep it tolerant (10 min) so a healthy-but-idle SSE keepalive is not torn every cycle;
        // a silently-dropped stream is instead healed at call time (see callTool/isMcpTransportClosed).
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .build()

    private val client = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        install(SSE)
    }

    private val clients: MutableMap<McpServerConfig, Client> = newMcpConcurrentMap()
    private val reconnectJobs: MutableMap<Uuid, Job> = newMcpConcurrentMap()
    private val reconnectAttempts: MutableMap<Uuid, Int> = newMcpConcurrentMap()
    val syncingStatus = MutableStateFlow<Map<Uuid, McpStatus>>(mapOf())

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .map { settings -> settings.mcpServers }
                .collect { mcpServerConfigs ->
                    runCatching {
                        Log.i(TAG, "update configs: $mcpServerConfigs")
                        val newConfigs = mcpServerConfigs.filter { it.commonOptions.enable }
                        val currentConfigs = clients.keys.toList()
                        val (toAdd, toRemove) = currentConfigs.checkDifferent(
                            other = newConfigs,
                            eq = { a, b -> a.id == b.id }
                        )
                        Log.i(TAG, "to_add: $toAdd")
                        Log.i(TAG, "to_remove: $toRemove")
                        toAdd.forEach { cfg ->
                            appScope.launch {
                                runCatching { addClient(cfg) }
                                    .onFailure { Log.w(TAG, "init: addClient failed", it) }
                            }
                        }
                        toRemove.forEach { cfg ->
                            appScope.launch { removeClient(cfg) }
                        }
                    }.onFailure {
                        Log.w(TAG, "init: failed to apply mcp config update", it)
                    }
                }
        }
    }

    fun getClient(config: McpServerConfig): Client? {
        return clients.entries.find { it.key.id == config.id }?.value
    }

    // Tools must be selected for the TARGET assistant of the generation, not for the GLOBAL
    // current assistant. A subagent (issue #201) runs as a *different* assistant than the one
    // selected in the UI; keying off settings.getCurrentAssistant() here would hand a subagent
    // the PARENT's MCP servers. The target assistant is passed in so the caller (ChatService /
    // SubagentRunner) controls whose allowlist applies. Pure selection lives in
    // selectMcpToolsForAssistant so it is JVM-unit-testable without SettingsStore.
    fun getAllAvailableTools(assistant: Assistant): List<Pair<Uuid, McpTool>> {
        return selectMcpToolsForAssistant(settingsStore.settingsFlow.value.mcpServers, assistant)
    }

    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject): List<UIMessagePart> {
        val entry = clients.entries.find { it.key.id == serverId }
        val client = entry?.value
            ?: return listOf(UIMessagePart.Text("Failed to execute tool, because no such mcp client for the tool"))
        val config = entry.key
        Log.i(TAG, "callTool: $toolName / $args (server: ${config.commonOptions.name})")

        // Invariant fix: liveness was checked by reference-nullness (transport == null),
        // but a transport can be non-null and already closed — the SSE keepalive GET dropped
        // while the Client still holds the (now non-Operational) transport. The first send
        // then fails with an IllegalStateException ("Not connected!" / "SseClientTransport is
        // closed!") or McpException("Connection closed"), which previously escaped callTool. We
        // now heal-and-retry once on that closed signal (see isMcpTransportClosed): re-open the
        // connection via the existing reconnect machinery, re-resolve the (replaced) client from
        // the map, and retry exactly once. A genuine tool-execution error is NOT a reconnect trigger.
        if (client.transport == null) client.connect(getTransport(config))
        return callToolWithHeal(
            initialCall = { invokeCallTool(client, toolName, args) },
            isTransportClosed = ::isMcpTransportClosed,
            reconnectInFlight = { reconnectJobs[config.id] },
            heal = { reconnectClient(config) },
            retryCall = {
                val healed = clients.entries.find { it.key.id == serverId }?.value
                    ?: error("mcp client for $serverId disappeared after reconnect")
                invokeCallTool(healed, toolName, args)
            },
            onHealFailed = {
                Log.e(TAG, "callTool heal failed for ${config.commonOptions.name}", it)
                listOf(UIMessagePart.Text("Failed to execute tool: ${it.message ?: it.javaClass.name}"))
            },
        )
    }

    private suspend fun invokeCallTool(
        client: Client,
        toolName: String,
        args: JsonObject,
    ): List<UIMessagePart> {
        val result = client.callTool(
            request = CallToolRequest(
                params = CallToolRequestParams(
                    name = toolName,
                    arguments = args,
                ),
            ),
            options = RequestOptions(timeout = 120.seconds),
        )
        return result.content.map {
            when (it) {
                is TextContent -> UIMessagePart.Text(it.text)
                is ImageContent -> convertImageContentToFilePart(it)
                else -> UIMessagePart.Text(JsonInstant.encodeToString(it))
            }
        }
    }

    private suspend fun convertImageContentToFilePart(image: ImageContent): UIMessagePart.Image {
        val bytes = Base64.decode(image.data)
        val ext = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(image.mimeType) ?: "bin"
        val entity = filesManager.saveUploadFromBytes(
            bytes = bytes,
            displayName = "mcp_image.$ext",
            mimeType = image.mimeType,
        )
        val uri = filesManager.getFile(entity).toUri()
        Log.i(TAG, "convertImageContentToFilePart: saved mcp image to $uri")
        return UIMessagePart.Image(url = uri.toString())
    }

    private fun getTransport(config: McpServerConfig): AbstractTransport {
        val transport = when (config) {
            is McpServerConfig.SseTransportServer -> {
                SseClientTransport(
                    urlString = config.url,
                    client = client,
                    requestBuilder = {
                        headers.appendAll(StringValues.build {
                            config.commonOptions.headers.forEach {
                                append(it.first, it.second)
                            }
                        })
                    },
                )
            }

            is McpServerConfig.StreamableHTTPServer -> {
                StreamableHttpClientTransport(
                    url = config.url,
                    client = client,
                    requestBuilder = {
                        headers.appendAll(StringValues.build {
                            config.commonOptions.headers.forEach {
                                append(it.first, it.second)
                            }
                        })
                    }
                )
            }
        }
        return attachReconnectCallbacks(transport, config)
    }

    private fun <T : AbstractTransport> attachReconnectCallbacks(
        transport: T,
        config: McpServerConfig,
    ): T = attachReconnectCallbacks(
        transport = transport,
        config = config,
        currentStatus = { syncingStatus.value[config.id] },
        scheduleReconnect = { scheduleReconnect(config) },
    )

    suspend fun addClient(config: McpServerConfig) = withContext(Dispatchers.IO) {
        removeClient(config) // Remove first
        cancelReconnect(config.id)
        reconnectAttempts[config.id] = 0

        val transport = getTransport(config)
        val client = Client(
            clientInfo = Implementation(
                name = config.commonOptions.name,
                version = "1.0",
            )
        )

        clients[config] = client
        runCatching {
            setStatus(config = config, status = McpStatus.Connecting)
            client.connect(transport)
            sync(config)
            setStatus(config = config, status = McpStatus.Connected)
            reconnectAttempts[config.id] = 0 // 重置重连计数
            Log.i(TAG, "addClient: connected ${config.commonOptions.name}")
        }.onFailure {
            Log.w(TAG, "addClient: connect failed for ${config.commonOptions.name}", it)
            setStatus(config = config, status = McpStatus.Error(it.message ?: it.javaClass.name))
        }
    }

    private suspend fun sync(config: McpServerConfig) {
        val client = clients[config] ?: return

        setStatus(config = config, status = McpStatus.Connecting)

        // Update tools
        if (client.transport == null) {
            client.connect(getTransport(config))
        }
        val serverTools = client.listTools().tools
        Log.i(TAG, "sync: tools: $serverTools")
        settingsStore.update { old ->
            old.copy(
                mcpServers = old.mcpServers.map { serverConfig ->
                    if (serverConfig.id != config.id) return@map serverConfig
                    val common = serverConfig.commonOptions
                    val tools = common.tools.toMutableList()

                    // 基于server对比
                    serverTools.forEach { serverTool ->
                        val tool = tools.find { it.name == serverTool.name }
                        if (tool == null) {
                            tools.add(
                                McpTool(
                                    name = serverTool.name,
                                    description = serverTool.description,
                                    enable = true,
                                    inputSchema = serverTool.inputSchema.toSchema()
                                )
                            )
                        } else {
                            val index = tools.indexOf(tool)
                            tools[index] = tool.copy(
                                description = serverTool.description,
                                inputSchema = serverTool.inputSchema.toSchema()
                            )
                        }
                    }

                    // 删除不在server内的
                    tools.removeIf { tool -> serverTools.none { it.name == tool.name } }

                    // 更新clients
                    clients.remove(config)
                    clients.put(
                        config.clone(
                            commonOptions = common.copy(
                                tools = tools
                            )
                        ), client
                    )

                    // 返回新的serverConfig，更新到settings store
                    serverConfig.clone(
                        commonOptions = common.copy(
                            tools = tools
                        )
                    )
                }
            )
        }

        setStatus(config = config, status = McpStatus.Connected)
    }

    suspend fun syncAll() = withContext(Dispatchers.IO) {
        clients.keys.toList().forEach { config ->
            runCatching {
                sync(config)
            }.onFailure {
                Log.w(TAG, "syncAll: sync failed for ${config.commonOptions.name}", it)
                setStatus(config, McpStatus.Error(it.message ?: it.javaClass.name))
            }
        }
    }

    suspend fun removeClient(config: McpServerConfig) = withContext(Dispatchers.IO) {
        cancelReconnect(config.id)
        val toRemove = clients.entries.filter { it.key.id == config.id }
        toRemove.forEach { entry ->
            runCatching {
                entry.value.close()
            }.onFailure {
                Log.w(TAG, "removeClient: close failed for ${config.commonOptions.name}", it)
            }
            clients.remove(entry.key)
            syncingStatus.emit(syncingStatus.value.toMutableMap().apply { remove(entry.key.id) })
            Log.i(TAG, "removeClient: ${entry.key} / ${entry.key.commonOptions.name}")
        }
        reconnectAttempts.remove(config.id)
    }

    private fun scheduleReconnect(config: McpServerConfig) {
        val configId = config.id
        val currentAttempt = (reconnectAttempts[configId] ?: 0) + 1

        if (currentAttempt > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached for ${config.commonOptions.name}")
            appScope.launch {
                setStatus(config, McpStatus.Error("连接断开，已达最大重连次数"))
            }
            return
        }

        reconnectAttempts[configId] = currentAttempt

        // 取消之前的重连任务
        reconnectJobs[configId]?.cancel()

        // 计算指数退避延迟
        val delayMs = calculateBackoffDelay(currentAttempt)
        Log.i(TAG, "Scheduling reconnect for ${config.commonOptions.name}, attempt $currentAttempt/$MAX_RECONNECT_ATTEMPTS, delay ${delayMs}ms")

        reconnectJobs[configId] = appScope.launch {
            try {
                setStatus(config, McpStatus.Reconnecting(currentAttempt, MAX_RECONNECT_ATTEMPTS))
                delay(delayMs)

                // 检查配置是否仍然启用
                val currentConfig = settingsStore.settingsFlow.value.mcpServers
                    .find { it.id == configId && it.commonOptions.enable }

                if (currentConfig == null) {
                    Log.i(TAG, "Config disabled or removed, cancelling reconnect for ${config.commonOptions.name}")
                    return@launch
                }

                Log.i(TAG, "Attempting reconnect for ${config.commonOptions.name}")
                reconnectClient(currentConfig)
            } catch (e: CancellationException) {
                Log.i(TAG, "Reconnect cancelled for ${config.commonOptions.name}")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect failed for ${config.commonOptions.name}", e)
                // 继续尝试重连
                scheduleReconnect(config)
            }
        }
    }

    private fun cancelReconnect(configId: Uuid) {
        reconnectJobs[configId]?.cancel()
        reconnectJobs.remove(configId)
    }

    private fun calculateBackoffDelay(attempt: Int): Long {
        // 指数退避: baseDelay * 2^(attempt-1)，最大不超过 maxDelay
        val exponentialDelay = BASE_RECONNECT_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(10))
        return exponentialDelay.coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private suspend fun reconnectClient(config: McpServerConfig) = withContext(Dispatchers.IO) {
        // 先关闭旧客户端
        val oldEntry = clients.entries.find { it.key.id == config.id }
        if (oldEntry != null) {
            runCatching { oldEntry.value.close() }.onFailure {
                Log.w(TAG, "reconnectClient: failed to close old client for ${config.commonOptions.name}", it)
            }
            clients.remove(oldEntry.key)
        }

        val transport = getTransport(config)
        val client = Client(
            clientInfo = Implementation(
                name = config.commonOptions.name,
                version = "1.0",
            )
        )

        clients[config] = client
        setStatus(config, McpStatus.Connecting)
        client.connect(transport)
        sync(config)
        setStatus(config, McpStatus.Connected)
        reconnectAttempts[config.id] = 0 // 重置重连计数
        Log.i(TAG, "Reconnected successfully: ${config.commonOptions.name}")
    }

    private suspend fun setStatus(config: McpServerConfig, status: McpStatus) {
        syncingStatus.emit(syncingStatus.value.toMutableMap().apply {
            put(config.id, status)
        })
    }

    fun getStatus(config: McpServerConfig): Flow<McpStatus> {
        return syncingStatus.map { it[config.id] ?: McpStatus.Idle }
    }
}

// The reconnect/close invariant — every transport McpManager connects must carry
// onClose/onError handlers that drive scheduleReconnect — has to hold at ALL connect
// sites (addClient, reconnectClient, and the lazy connects in callTool/sync). Issue
// #28: it was registered only at addClient/reconnectClient, so a transport opened on
// the lazy callTool/sync path had no onClose handler — a dropped stream was never
// rescheduled and the orphaned connection leaked. Attaching the callbacks here, called
// from the single getTransport factory all four sites share, makes the invariant hold
// by construction. It only reconnects while status is Connected so a deliberate
// shutdown does not trigger a reconnect. Kept as a pure function (status + reconnect
// injected) so the attachment behavior is JVM-unit-testable without the Android deps.
internal fun <T : AbstractTransport> attachReconnectCallbacks(
    transport: T,
    config: McpServerConfig,
    currentStatus: () -> McpStatus?,
    scheduleReconnect: () -> Unit,
): T {
    transport.onClose {
        Log.i(TAG, "Transport closed for ${config.commonOptions.name}")
        if (currentStatus() == McpStatus.Connected) {
            scheduleReconnect()
        }
    }
    transport.onError { error ->
        Log.e(TAG, "Transport error for ${config.commonOptions.name}: ${error.message}")
        if (currentStatus() == McpStatus.Connected) {
            scheduleReconnect()
        }
    }
    return transport
}

// The SDK (kotlin-sdk 0.12.0) exposes no public closed/liveness getter, so a dead transport is
// recognised from the exact failures its send/request path throws. Verified by decompiling the
// pinned jars:
//   - SseClientTransport.performSend throws plain IllegalStateException with
//       "SseClientTransport is closed!"  (job null/inactive),
//       "Not connected!"                 (endpoint deferred uncompleted — the dropped-keepalive case),
//       "...Error POSTing to endpoint (HTTP <code>)..."  (POST failed).
//   - Protocol.request throws McpException(-32000, "Connection closed") when onClose fires mid-call,
//     and IllegalStateException("Not connected") on a pre-send guard.
//   - StreamableHttpClientTransport.performSend throws StreamableHttpError on POST failure.
//   - A dead underlying socket surfaces from OkHttp/ktor as an IOException.
// These are exactly the closed/dropped-stream signals; "Request timed out" (McpException -32001, a
// slow tool) and any other RPC error response are deliberately NOT matched, so a genuine
// tool-execution error does not trigger a needless reconnect+retry. (The strings "Transport is not
// ready" / "Error while sending message" the earlier draft matched do not exist anywhere in 0.12.0.)
internal fun isMcpTransportClosed(t: Throwable): Boolean {
    if (t is CancellationException) return false
    if (t is java.io.IOException) return true
    val message = t.message ?: return false
    return message.contains("Connection closed", ignoreCase = true) ||
        message.contains("Not connected", ignoreCase = true) ||
        message.contains("is closed", ignoreCase = true) ||
        message.contains("Error POSTing to endpoint", ignoreCase = true)
}

// Heal-and-retry control flow extracted as a pure suspend function (no Android deps) so the
// closed-detection + single-heal + single-retry policy is JVM-unit-testable, mirroring the
// attachReconnectCallbacks seam. Contract:
//  - run initialCall; on success return it.
//  - CancellationException is always rethrown (never swallow cancellation).
//  - on a throwable where isTransportClosed == false, rethrow it (a real tool error is not a
//    reconnect trigger — no catch-and-retry on non-transient failures).
//  - on a throwable where isTransportClosed == true: if a reconnect job is already in flight,
//    join() it (no double-connect); otherwise heal() synchronously. Then retryCall() exactly
//    once, returning its result, or onHealFailed(originalError) if heal/retry itself throws.
internal suspend fun callToolWithHeal(
    initialCall: suspend () -> List<UIMessagePart>,
    isTransportClosed: (Throwable) -> Boolean,
    reconnectInFlight: () -> Job?,
    heal: suspend () -> Unit,
    retryCall: suspend () -> List<UIMessagePart>,
    onHealFailed: (Throwable) -> List<UIMessagePart>,
): List<UIMessagePart> {
    val closedError = try {
        return initialCall()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        if (!isTransportClosed(e)) throw e
        e
    }

    return try {
        val inFlight = reconnectInFlight()
        if (inFlight != null) inFlight.join() else heal()
        retryCall()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        onHealFailed(closedError)
    }
}

// Pure server/tool selection for a given assistant — extracted from getAllAvailableTools so the
// load-bearing rule (a server's tools are included iff the server is enabled AND its id is in the
// TARGET assistant's allowlist, NOT the global current assistant's) is JVM-unit-testable without a
// SettingsStore. Issue #201: a subagent runs as a different assistant; selecting by the passed-in
// assistant is what keeps a subagent from inheriting the parent's MCP servers.
internal fun selectMcpToolsForAssistant(
    mcpServers: List<McpServerConfig>,
    assistant: Assistant,
): List<Pair<Uuid, McpTool>> =
    mcpServers
        .filter { it.commonOptions.enable && it.id in assistant.mcpServers }
        .flatMap { server ->
            server.commonOptions.tools
                .filter { tool -> tool.enable }
                .map { tool -> server.id to tool }
        }

internal val McpJson: Json by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        classDiscriminatorMode = ClassDiscriminatorMode.NONE
        explicitNulls = false
    }
}

private fun ToolSchema.toSchema(): InputSchema {
    return InputSchema.Obj(properties = this.properties ?: JsonObject(emptyMap()), required = this.required)
}
