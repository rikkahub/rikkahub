package me.rerere.rikkahub.web

import android.content.Context
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
    // mDNS discovery (LAN mode only): the device-unique `<host>.local` and the resolved LAN IP. Null in
    // localhost-only mode or before/after registration.
    val hostname: String? = null,
    val address: String? = null,
    val error: String? = null,
)

internal fun a2aConflictsWithWebServer(a2aPort: Int, webServerState: WebServerState): Boolean =
    webServerState.isRunning && webServerState.port == a2aPort

class A2aServerManager(
    private val context: Context,
    private val appScope: AppScope,
    private val chatService: ChatService,
    private val settingsStore: SettingsStore,
    private val a2aTaskRegistry: A2aTaskRegistry,
    private val webServerManager: WebServerManager,
) {
    private val lifecycle =
        WebServerLifecycle<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>>()

    // A2A's OWN mDNS registrar (Option A) — independent of the web server's, with a device-unique host
    // label so two devices (even same model) never collide. Web server is untouched.
    private val nsdRegistrar = NsdServiceRegistrar(context)

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
                registerNsd(port, localhostOnly)
                Log.i(TAG, "A2A server started on $host:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start A2A server", e)
                _state.value = baseState.copy(error = e.message)
            }
        }
    }

    fun stop() {
        _state.value = _state.value.copy(
            isRunning = false, isLoading = true, hostname = null, address = null, error = null
        )
        appScope.launch {
            try {
                lifecycle.stop { it.stop(1000, 2000) }
                runCatching { nsdRegistrar.unregister() }
                    .onFailure { Log.w(TAG, "NSD unregister failed", it) }
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
                    onStop = { server ->
                        server.stop(1000, 2000)
                        runCatching { nsdRegistrar.unregister() }
                            .onFailure { Log.w(TAG, "NSD unregister failed", it) }
                    },
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
                registerNsd(port, localhostOnly)
                Log.i(TAG, "A2A server restarted on $host:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart A2A server", e)
                _state.value = baseState.copy(error = e.message)
            }
        }
    }

    /**
     * Register the A2A service via mDNS — LAN mode only (localhost has no peers to discover it). The host
     * label is device-unique (`poci-a2a-<model>-<idhash>.local`) so two devices never collide; the
     * service instance carries the human device name; TXT advertises the agent-card/RPC paths (NOT the
     * bearer token). mDNS advertises reachability only — the route-level bearer gate still decides access.
     */
    private suspend fun registerNsd(port: Int, localhostOnly: Boolean) {
        if (localhostOnly) return
        val identity = deviceMdnsIdentity(context.contentResolver)
        runCatching {
            nsdRegistrar.register(
                port = port,
                hostLabel = mdnsHostLabel(prefix = "poci-a2a", model = identity.modelSlug, idHash = identity.idHash),
                instanceName = serviceInstanceName(kind = "A2A", displayName = identity.displayName),
                description = "Poci A2A Agent",
                txt = a2aTxtRecord(),
                onRegistered = { info ->
                    _state.value = _state.value.copy(
                        hostname = info.hostname,
                        address = info.address.hostAddress,
                    )
                },
            )
        }.onFailure {
            Log.w(TAG, "A2A NSD register failed", it)
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
