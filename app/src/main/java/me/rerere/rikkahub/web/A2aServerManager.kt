package me.rerere.rikkahub.web

import android.util.Log
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.web.a2a.A2aTaskRegistry
import java.net.ServerSocket

private const val TAG = "A2aServerManager"
private const val HOST_ALL_INTERFACES = "0.0.0.0"
private const val HOST_LOOPBACK = "127.0.0.1"

data class A2aServerState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val port: Int = 9000,
    val localhostOnly: Boolean = true,
    val url: String? = null,
    val error: String? = null,
)

internal fun a2aConflictsWithWebServer(a2aPort: Int, webServerState: WebServerState): Boolean =
    webServerState.isRunning && webServerState.port == a2aPort

class A2aServerManager(
    private val appScope: AppScope,
    private val chatService: ChatService,
    private val settingsStore: SettingsStore,
    private val a2aTaskRegistry: A2aTaskRegistry,
    private val webServerManager: WebServerManager,
) {
    private val lifecycle =
        WebServerLifecycle<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>>()

    private val _state = MutableStateFlow(A2aServerState())
    val state: StateFlow<A2aServerState> = _state.asStateFlow()

    fun start(
        port: Int = 9000,
        localhostOnly: Boolean = true,
    ) {
        appScope.launch {
            val host = if (localhostOnly) HOST_LOOPBACK else HOST_ALL_INTERFACES
            val baseState = A2aServerState(
                port = port,
                localhostOnly = localhostOnly,
                url = localUrl(port),
            )
            try {
                _state.value = _state.value.copy(isLoading = true, error = null)
                if (a2aConflictsWithWebServer(port, webServerManager.state.value)) {
                    _state.value = baseState.copy(error = "Port $port is already used by the web server")
                    return@launch
                }
                if (!isPortAvailable(port)) {
                    _state.value = baseState.copy(error = "Port $port is already in use")
                    return@launch
                }
                val started = lifecycle.start {
                    startKtorServerNoSpa(port = port, host = host) {
                        configureA2aApi(
                            appScope = appScope,
                            chatService = chatService,
                            settingsStore = settingsStore,
                            a2aTaskRegistry = a2aTaskRegistry,
                        )
                    }.start(wait = false)
                }
                if (started == null) {
                    Log.w(TAG, "A2A server already running")
                    return@launch
                }

                _state.value = baseState.copy(isRunning = true)
                Log.i(TAG, "A2A server started on $host:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start A2A server", e)
                _state.value = baseState.copy(error = e.message)
            }
        }
    }

    fun stop() {
        _state.value = _state.value.copy(isRunning = false, isLoading = true, error = null)
        appScope.launch {
            try {
                lifecycle.stop { it.stop(1000, 2000) }
                _state.value = _state.value.copy(isLoading = false, url = null)
                Log.i(TAG, "A2A server stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop A2A server", e)
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun restart(
        port: Int = _state.value.port,
        localhostOnly: Boolean = _state.value.localhostOnly,
    ) {
        appScope.launch {
            val host = if (localhostOnly) HOST_LOOPBACK else HOST_ALL_INTERFACES
            val baseState = A2aServerState(
                port = port,
                localhostOnly = localhostOnly,
                url = localUrl(port),
            )
            try {
                _state.value = _state.value.copy(isLoading = true, error = null)
                lifecycle.restart(
                    onStop = { server -> server.stop(1000, 2000) },
                    factory = {
                        if (a2aConflictsWithWebServer(port, webServerManager.state.value)) {
                            error("Port $port is already used by the web server")
                        }
                        if (!isPortAvailable(port)) error("Port $port is already in use")
                        startKtorServerNoSpa(port = port, host = host) {
                            configureA2aApi(
                                appScope = appScope,
                                chatService = chatService,
                                settingsStore = settingsStore,
                                a2aTaskRegistry = a2aTaskRegistry,
                            )
                        }.start(wait = false)
                    },
                )
                _state.value = baseState.copy(isRunning = true)
                Log.i(TAG, "A2A server restarted on $host:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart A2A server", e)
                _state.value = baseState.copy(error = e.message)
            }
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }

    private fun localUrl(port: Int): String = "http://localhost:$port"
}
