package me.rerere.rikkahub.data.ai.mcp

import android.util.Log
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.HttpHeaders
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
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
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
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val filesManager: FilesManager,
    private val oAuthManager: McpOAuthManager,
) {
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
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
                        Log.i(TAG, "update configs: $mcpServerConfigs")
                        val newConfigs = mcpServerConfigs.filter { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
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
                                    .onFailure {
                                        Log.w(TAG, "Failed to add MCP client ${cfg.commonOptions.name}: ${it.safeLogMessage()}")
                                    }
                            }
                        }
                        toRemove.forEach { cfg ->
                            appScope.launch { removeClient(cfg) }
                        }
                    }.onFailure {
                        Log.w(TAG, "Failed to update MCP configs: ${it.safeLogMessage()}")
                    }
                }
        }
    }

    fun getClient(config: McpServerConfig): Client? {
        return clients.entries.find { it.key.id == config.id }?.value
    }

    fun getAllAvailableTools(): List<Triple<Uuid, String, McpTool>> {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getCurrentAssistant()
        return settings.mcpServers
            .filter {
                it.commonOptions.enable && it.id in assistant.mcpServers
            }
            .flatMap { server ->
                server.commonOptions.tools
                    .filter { tool -> tool.enable }
                    .map { tool -> Triple(server.id, server.commonOptions.name, tool) }
            }
    }

    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject): List<UIMessagePart> {
        val entry = clients.entries.find { it.key.id == serverId }
        val client = entry?.value
            ?: return listOf(UIMessagePart.Text("Failed to execute tool, because no such mcp client for the tool"))
        val config = entry.key
        Log.i(TAG, "callTool: $toolName / $args (server: ${config.commonOptions.name})")

        if (!ensureOAuthTokenForConnect(config)) {
            return listOf(UIMessagePart.Text("MCP server needs OAuth authorization. Please authorize it in settings."))
        }
        if (client.transport == null) client.connect(getTransport(config))
        val result = runCatching {
            client.callTool(
                request = CallToolRequest(
                    params = CallToolRequestParams(
                        name = toolName,
                        arguments = args,
                    ),
                ),
                options = RequestOptions(timeout = 120.seconds),
            )
        }.getOrElse { error ->
            if (handleOAuthFailure(config, error)) {
                client.callTool(
                    request = CallToolRequest(
                        params = CallToolRequestParams(
                            name = toolName,
                            arguments = args,
                        ),
                    ),
                    options = RequestOptions(timeout = 120.seconds),
                )
            } else if (error.isOAuthAuthorizationError(config)) {
                return listOf(UIMessagePart.Text("MCP OAuth authorization is required or needs more permissions. Please authorize this server in MCP settings."))
            } else {
                throw error
            }
        }
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
                        config.commonOptions.headers.forEach { append(it.first, it.second) }
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
                        if (config.oauth?.enabled == true) {
                            oAuthManager.getCachedAccessToken(config.id)?.let {
                                append(HttpHeaders.Authorization, "Bearer $it")
                            }
                        } else {
                            config.commonOptions.headers.forEach {
                                append(it.first, it.second)
                            }
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

        if (!ensureOAuthTokenForConnect(config)) return@withContext

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
            Log.e(TAG, "Transport error for ${config.commonOptions.name}: ${error.safeLogMessage()}")
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
            Log.w(TAG, "Failed to connect MCP client ${config.commonOptions.name}: ${it.safeLogMessage()}")
            if (handleOAuthFailure(config, it)) {
                if (!retryConnectAfterOAuth(config)) {
                    setStatus(config = config, status = McpStatus.Error(it.message ?: it.javaClass.name))
                }
            } else if (!it.isOAuthAuthorizationError(config)) {
                setStatus(config = config, status = McpStatus.Error(it.message ?: it.javaClass.name))
            }
        }
    }

    fun authorize(config: McpServerConfig.StreamableHTTPServer): Job {
        return appScope.launch(Dispatchers.IO) {
            setStatus(config, McpStatus.Authorizing)
            when (val result = oAuthManager.authorize(config.id, config.url)) {
                is OAuthResult.Success -> addClient(config)
                is OAuthResult.Error -> setStatus(config, McpStatus.Error(result.message))
            }
        }
    }

    fun revokeAuthorization(config: McpServerConfig.StreamableHTTPServer): Job {
        return appScope.launch(Dispatchers.IO) {
            oAuthManager.revoke(config.id)
            removeClient(config)
            setStatus(config, McpStatus.Idle)
        }
    }

    private suspend fun sync(config: McpServerConfig) {
        val client = clients[config] ?: return

        setStatus(config = config, status = McpStatus.Connecting)
        if (!ensureOAuthTokenForConnect(config)) return

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
                Log.w(TAG, "Failed to sync MCP client ${config.commonOptions.name}: ${it.safeLogMessage()}")
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
                Log.w(TAG, "Failed to close MCP client ${entry.key.commonOptions.name}: ${it.safeLogMessage()}")
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
                Log.e(TAG, "Reconnect failed for ${config.commonOptions.name}: ${e.safeLogMessage()}")
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
        if (!ensureOAuthTokenForConnect(config)) return@withContext

        // 先关闭旧客户端
        val oldEntry = clients.entries.find { it.key.id == config.id }
        if (oldEntry != null) {
            runCatching { oldEntry.value.close() }
                .onFailure {
                    Log.w(TAG, "Failed to close old MCP client ${config.commonOptions.name}: ${it.safeLogMessage()}")
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

        // 注册回调
        transport.onClose {
            Log.i(TAG, "Transport closed for ${config.commonOptions.name}")
            val currentStatus = syncingStatus.value[config.id]
            if (currentStatus == McpStatus.Connected) {
                scheduleReconnect(config)
            }
        }

        transport.onError { error ->
            Log.e(TAG, "Transport error for ${config.commonOptions.name}: ${error.safeLogMessage()}")
            val currentStatus = syncingStatus.value[config.id]
            if (currentStatus == McpStatus.Connected) {
                scheduleReconnect(config)
            }
        }

        clients[config] = client
        setStatus(config, McpStatus.Connecting)
        runCatching {
            client.connect(transport)
            sync(config)
            setStatus(config, McpStatus.Connected)
            reconnectAttempts[config.id] = 0 // 重置重连计数
            Log.i(TAG, "Reconnected successfully: ${config.commonOptions.name}")
        }.onFailure {
            if (!handleOAuthFailure(config, it) && !it.isOAuthAuthorizationError(config)) throw it
        }
    }

    private suspend fun ensureOAuthTokenForConnect(config: McpServerConfig): Boolean {
        if (config !is McpServerConfig.StreamableHTTPServer || config.oauth?.enabled != true) return true
        val token = oAuthManager.ensureValidToken(config.id)
        return if (token == null) {
            setStatus(config, McpStatus.Error("This MCP server requires OAuth authorization. Please tap Authorize."))
            false
        } else {
            true
        }
    }

    private suspend fun handleOAuthFailure(config: McpServerConfig, error: Throwable): Boolean {
        if (config !is McpServerConfig.StreamableHTTPServer || config.oauth?.enabled != true) return false
        val statusCode = error.httpStatusCode() ?: return false
        return oAuthManager.handleAuthFailure(config.id, statusCode, error.wwwAuthenticate())
    }

    private suspend fun retryConnectAfterOAuth(config: McpServerConfig): Boolean {
        if (config !is McpServerConfig.StreamableHTTPServer || config.oauth?.enabled != true) return false
        val transport = getTransport(config)
        val client = Client(
            clientInfo = Implementation(
                name = config.commonOptions.name,
                version = "1.0",
            )
        )
        clients[config] = client
        return runCatching {
            setStatus(config = config, status = McpStatus.Connecting)
            client.connect(transport)
            sync(config)
            setStatus(config = config, status = McpStatus.Connected)
            true
        }.getOrElse {
            false
        }
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

private fun Throwable.httpStatusCode(): Int? {
    val response = runCatching {
        this::class.java.methods.firstOrNull { it.name == "getResponse" }?.invoke(this)
    }.getOrNull()
    val status = runCatching {
        response?.javaClass?.methods?.firstOrNull { it.name == "getStatus" }?.invoke(response)
    }.getOrNull()
    val value = runCatching {
        status?.javaClass?.methods?.firstOrNull { it.name == "getValue" }?.invoke(status) as? Int
    }.getOrNull()
    if (value != null) return value
    val code = runCatching {
        this::class.java.methods.firstOrNull { it.name == "getCode" }?.invoke(this) as? Int
    }.getOrNull()
    if (code != null) return code
    return Regex("""\b(401|403)\b""").find(message.orEmpty())?.value?.toIntOrNull()
}

private fun Throwable.wwwAuthenticate(): String? {
    val response = runCatching {
        this::class.java.methods.firstOrNull { it.name == "getResponse" }?.invoke(this)
    }.getOrNull()
    val headers = runCatching {
        response?.javaClass?.methods?.firstOrNull { it.name == "getHeaders" }?.invoke(response)
    }.getOrNull()
    return runCatching {
        headers?.javaClass?.methods
            ?.firstOrNull { it.name == "get" && it.parameterTypes.size == 1 }
            ?.invoke(headers, "WWW-Authenticate") as? String
    }.getOrNull()
}

private fun Throwable.isOAuthAuthorizationError(config: McpServerConfig): Boolean {
    if (config !is McpServerConfig.StreamableHTTPServer || config.oauth?.enabled != true) return false
    return httpStatusCode() == 401 || httpStatusCode() == 403
}

private fun Throwable.safeLogMessage(): String {
    return "${this::class.java.simpleName}: ${message?.take(160).orEmpty()}"
}
