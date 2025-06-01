package me.rerere.rikkahub.data.mcp

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.WebSocketClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.mcp.transport.SseClientTransport
import me.rerere.rikkahub.ui.hooks.getCurrentAssistant
import me.rerere.rikkahub.utils.checkDifferent
import kotlin.time.Duration.Companion.seconds

private const val TAG = "McpManager"

class McpManager(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope
) {
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(McpJson)
        }
//        install(Logging) {
//            level = LogLevel.ALL
//            logger = object : Logger {
//                override fun log(message: String) {
//                    println()
//                    println("[ktor] $message")
//                }
//            }
//        }
        install(SSE)
        install(WebSockets)
    }

    private val clients: MutableMap<McpServerConfig, Client> = mutableMapOf()
    val syncing = MutableStateFlow(false)

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .map { settings -> settings.mcpServers }
                .collect { mcpServerConfigs ->
                    syncing.value = true
                    runCatching {
                        val newConfigs = mcpServerConfigs.filter { it.commonOptions.enable }
                        val currentConfigs = clients.keys.toList()
                        val (toAdd, toRemove) = currentConfigs.checkDifferent(
                            other = newConfigs,
                            eq = { a, b -> a.id == b.id }
                        )
                        Log.i(TAG, "to_add: $toAdd")
                        Log.i(TAG, "to_remove: $toRemove")
                        coroutineScope {
                            toAdd.forEach { cfg ->
                                launch {
                                    runCatching { addClient(cfg) }
                                        .onFailure { it.printStackTrace() }
                                }
                            }
                            toRemove.forEach { cfg ->
                                launch { removeClient(cfg) }
                            }
                        }
                    }.onFailure {
                        it.printStackTrace()
                    }
                    syncing.value = false
                }
        }
    }

    fun getClient(config: McpServerConfig): Client? = clients[config]

    fun getAllAvailableTools(): List<McpTool> {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getCurrentAssistant()
        val mcpServers = settings.mcpServers
            .filter {
                it.commonOptions.enable && it.id in assistant.mcpServers
            }
            .flatMap {
                it.commonOptions.tools.filter { tool -> tool.enable }
            }
        return mcpServers
    }

    suspend fun callTool(toolName: String, args: JsonObject): JsonElement {
        val tools = getAllAvailableTools()
        val tool = tools.find { it.name == toolName }
            ?: return JsonPrimitive("Failed to execute tool, because no such tool")
        val client =
            clients.entries.find { it.key.commonOptions.tools.any { it.name == toolName } }
        if (client == null) return JsonPrimitive("Failed to execute tool, because no such mcp client for the tool")
        Log.i(TAG, "callTool: $toolName / $args")

        val result = withTimeout(15.seconds) {
            client.value.callTool(
                request = CallToolRequest(
                    name = tool.name,
                    arguments = args,
                ),
                options = RequestOptions(timeout = 15.seconds),
                compatibility = true
            )
        }
        require(result != null) {
            "Result is null"
        }
        return McpJson.encodeToJsonElement(result.content)
    }

    suspend fun addClient(config: McpServerConfig) {
        this.removeClient(config) // Remove first
        val client = when (config) {
            is McpServerConfig.SseTransportServer -> {
                val transport = SseClientTransport(
                    urlString = config.url,
                    client = httpClient,
                )
                val client = Client(
                    clientInfo = Implementation(
                        name = "test", version = "1.0.0"
                    ),
                )
                client.connect(transport)
                client
            }

            is McpServerConfig.WebSocketServer -> {
                val transport = WebSocketClientTransport(
                    urlString = config.url,
                    client = httpClient,
                )
                val client = Client(
                    clientInfo = Implementation(
                        name = "test", version = "1.0.0"
                    ),
                )
                client.connect(transport)
                client
            }
        }
        Log.i(TAG, "addClient: connected ${config.commonOptions.name}")
        clients[config] = client
        this.sync(config)
    }

    suspend fun sync(config: McpServerConfig) {
        val client = clients[config] ?: return

        // Update tools
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
    }

    suspend fun removeClient(config: McpServerConfig) {
        val toRemove = clients.entries.filter { it.key.id == config.id }
        toRemove.forEach { entry ->
            runCatching {
                entry.value.close()
            }.onFailure {
                it.printStackTrace()
            }
            clients.remove(entry.key)
            Log.i(TAG, "removeClient: ${entry.key}")
        }
    }

    suspend fun closeAll() {
        clients.values.forEach {
            runCatching { it.close() }.onFailure { it.printStackTrace() }
        }
        clients.clear()
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

private fun Tool.Input.toSchema(): InputSchema {
    return InputSchema.Obj(properties = this.properties, required = this.required)
}