package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.StringValues
import io.modelcontextprotocol.kotlin.sdk.client.Client
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
import me.rerere.rikkahub.data.ai.mcp.transport.SseClientTransport
import me.rerere.rikkahub.data.ai.mcp.transport.StreamableHttpClientTransport
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.checkDifferent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val TAG = "McpManager"
private const val MAX_RECONNECT_ATTEMPTS = 5
private const val BASE_RECONNECT_DELAY_MS = 1000L
private const val MAX_RECONNECT_DELAY_MS = 30000L

class McpManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val filesManager: FilesManager,
) {
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .build()
        .also { me.rerere.rikkahub.utils.NetworkChangeMonitor.register(it) }

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

    private val clients: MutableMap<McpServerConfig, Client> = mutableMapOf()
    private val reconnectJobs: MutableMap<Uuid, Job> = mutableMapOf()
    private val reconnectAttempts: MutableMap<Uuid, Int> = mutableMapOf()
    val syncingStatus = MutableStateFlow<Map<Uuid, McpStatus>>(mapOf())

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .map { settings -> settings.mcpServers }
                .collect { mcpServerConfigs ->
                    runCatching {
                        Log.i(TAG, "update configs: ${mcpServerConfigs.joinToString { redactConfigForLog(it) }}")
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
                                    .onFailure { Log.w(TAG, "addClient failed for ${cfg.commonOptions.name}", it) }
                            }
                        }
                        toRemove.forEach { cfg ->
                            appScope.launch { removeClient(cfg) }
                        }
                    }.onFailure {
                        Log.w(TAG, "settings collector reconcile failed", it)
                    }
                }
        }
    }

    fun getClient(config: McpServerConfig): Client? {
        return clients.entries.find { it.key.id == config.id }?.value
    }

    fun getAllAvailableTools(): List<Pair<Uuid, McpTool>> {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getCurrentAssistant()
        return settings.mcpServers
            .filter {
                it.commonOptions.enable && it.id in assistant.mcpServers
            }
            .flatMap { server ->
                server.commonOptions.tools
                    .filter { tool -> tool.enable }
                    .map { tool -> server.id to tool }
            }
    }

    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject): List<UIMessagePart> {
        val entry = clients.entries.find { it.key.id == serverId }
        val client = entry?.value
            ?: return listOf(UIMessagePart.Text("Failed to execute tool, because no such mcp client for the tool"))
        val config = entry.key
        Log.i(TAG, "callTool: $toolName / $args (server: ${config.commonOptions.name})")

        if (client.transport == null) client.connect(getTransport(config))
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
            when(it) {
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

    private fun getTransport(config: McpServerConfig): AbstractTransport = when (config) {
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

        // 注册 transport 回调以支持自动重连
        transport.onClose {
            Log.i(TAG, "Transport closed for ${config.commonOptions.name}")
            val currentStatus = syncingStatus.value[config.id]
            // 只有在已连接状态下才触发重连，避免正常关闭时重连
            if (currentStatus == McpStatus.Connected) {
                scheduleReconnect(config)
            }
        }

        transport.onError { error ->
            Log.e(TAG, "Transport error for ${config.commonOptions.name}: ${error.message}")
            val currentStatus = syncingStatus.value[config.id]
            // 只有在已连接状态下才触发重连
            if (currentStatus == McpStatus.Connected) {
                scheduleReconnect(config)
            }
        }

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
        val serverTools = client.listTools()?.tools ?: emptyList()
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
            }
        }
    }

    /**
     * Force a re-connect + tool re-sync for a single server identified by its id. Used by
     * the LLM-callable `mcp_test` tool: tearing down the existing client and re-`addClient`ing
     * gives us the same code path the initial connect uses, so a "test now" reflects exactly
     * what the next reconnect would do. Also resets the per-server backoff counter — a
     * successful manual test means the next genuine failure starts at the lowest delay
     * instead of inheriting whatever the auto-reconnect ladder had wound up to.
     *
     * Returns the in-memory config the manager ended up with (so callers can read the
     * fresh tool list), or null if no server with that id is currently registered.
     */
    suspend fun forceResync(serverId: Uuid): McpServerConfig? = withContext(Dispatchers.IO) {
        val current = clients.keys.firstOrNull { it.id == serverId }
            ?: settingsStore.settingsFlow.value.mcpServers.firstOrNull { it.id == serverId }
            ?: return@withContext null
        cancelReconnect(serverId)
        reconnectAttempts[serverId] = 0
        addClient(current)
        clients.keys.firstOrNull { it.id == serverId }
    }

    suspend fun removeClient(config: McpServerConfig) = withContext(Dispatchers.IO) {
        cancelReconnect(config.id)
        val toRemove = clients.entries.filter { it.key.id == config.id }
        toRemove.forEach { entry ->
            runCatching {
                entry.value.close()
            }.onFailure {
                Log.w(TAG, "removeClient: close failed for ${entry.key.commonOptions.name}", it)
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
                setStatus(config, McpStatus.Error(context.getString(R.string.mcp_error_reconnect_exhausted)))
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
            runCatching { oldEntry.value.close() }
                .onFailure { Log.w(TAG, "reconnectClient: old client close failed for ${config.commonOptions.name}", it) }
            clients.remove(oldEntry.key)
        }

        val transport = getTransport(config)
        val client = Client(
            clientInfo = Implementation(
                name = config.commonOptions.name,
                version = "1.0",
            )
        )

        // 注册回调
        transport.onClose {
            Log.i(TAG, "Transport closed for ${config.commonOptions.name}")
            val currentStatus = syncingStatus.value[config.id]
            if (currentStatus == McpStatus.Connected) {
                scheduleReconnect(config)
            }
        }

        transport.onError { error ->
            Log.e(TAG, "Transport error for ${config.commonOptions.name}: ${error.message}")
            val currentStatus = syncingStatus.value[config.id]
            if (currentStatus == McpStatus.Connected) {
                scheduleReconnect(config)
            }
        }

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

/**
 * Build a one-line summary of an MCP config that's safe to log. Uses the shared header
 * redactor so secret values never reach logcat. Logging the full data class via toString
 * (which is what `$mcpServerConfigs` would do) leaks every Authorization / X-Api-Key
 * value verbatim — addressed in the Phase 10 audit pass.
 */
private fun redactConfigForLog(config: McpServerConfig): String {
    val transport = when (config) {
        is McpServerConfig.SseTransportServer -> "sse"
        is McpServerConfig.StreamableHTTPServer -> "streamable_http"
    }
    val url = when (config) {
        is McpServerConfig.SseTransportServer -> config.url
        is McpServerConfig.StreamableHTTPServer -> config.url
    }
    val redactedHeaders = me.rerere.rikkahub.data.ai.mcp.control.McpHeaderRedactor
        .redactHeaders(config.commonOptions.headers)
    return buildString {
        append("McpServer(id=").append(config.id)
        append(", name='").append(config.commonOptions.name).append('\'')
        append(", transport=").append(transport)
        append(", url=").append(url)
        append(", enabled=").append(config.commonOptions.enable)
        append(", tools=").append(config.commonOptions.tools.size)
        append(", headers=[").append(redactedHeaders.joinToString { "${it.first}=${it.second}" }).append("]")
        append(")")
    }
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
