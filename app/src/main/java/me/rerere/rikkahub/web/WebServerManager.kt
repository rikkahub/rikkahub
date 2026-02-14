package me.rerere.rikkahub.web

import android.content.Context
import android.util.Log
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.web.startWebServer

private const val TAG = "WebServerManager"

data class WebServerState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val port: Int = 8080,
    val serviceName: String = DEFAULT_SERVICE_NAME,
    val hostname: String? = null,
    val address: String? = null,
    val error: String? = null
)

class WebServerManager(
    private val context: Context,
    private val appScope: AppScope,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val filesManager: FilesManager
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val nsdRegistrar = NsdServiceRegistrar(context)

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    fun start(
        port: Int = 8080,
        serviceName: String = DEFAULT_SERVICE_NAME
    ) {
        if (server != null) {
            Log.w(TAG, "Server already running")
            return
        }

        appScope.launch {
            try {
                _state.value = _state.value.copy(isLoading = true)
                Log.i(TAG, "Starting web server on port $port")
                server = startWebServer(port = port) {
                    configureWebApi(context, chatService, conversationRepo, settingsStore, filesManager)
                }.start(wait = false)

                _state.value = WebServerState(
                    isRunning = true,
                    port = port,
                    serviceName = serviceName
                )
                runCatching {
                    nsdRegistrar.register(
                        port = port,
                        serviceName = serviceName,
                        onRegistered = { info ->
                            _state.value = _state.value.copy(
                                serviceName = info.serviceName,
                                hostname = info.hostname,
                                address = info.address.hostAddress
                            )
                        }
                    )
                }.onFailure {
                    Log.w(TAG, "NSD register failed", it)
                }
                Log.i(TAG, "Web server started successfully on port $port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start web server", e)
                _state.value = WebServerState(
                    isRunning = false,
                    port = port,
                    serviceName = serviceName,
                    error = e.message
                )
            }
        }
    }

    fun stop() {
        _state.value =
            _state.value.copy(isRunning = false, isLoading = true, hostname = null, address = null, error = null)
        appScope.launch {
            try {
                Log.i(TAG, "Stopping web server")
                server?.stop(1000, 2000)
                server = null
                runCatching {
                    nsdRegistrar.unregister()
                }.onFailure {
                    Log.w(TAG, "NSD unregister failed", it)
                }
                _state.value = _state.value.copy(isLoading = false)
                Log.i(TAG, "Web server stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop web server", e)
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun restart(
        port: Int = _state.value.port,
        serviceName: String = _state.value.serviceName
    ) {
        stop()
        start(port, serviceName)
    }
}
