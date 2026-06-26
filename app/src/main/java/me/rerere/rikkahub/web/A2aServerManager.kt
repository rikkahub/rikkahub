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
import me.rerere.rikkahub.web.a2a.a2aCardBaseUrl
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
                val started = lifecycle.start {
                    // The conflict/port checks, the bind, the mDNS register, AND the running-state publish
                    // ALL run INSIDE the locked transition (#415). isRunning/hostname/address are written
                    // ONLY here and in stop()'s onStop, so the observable state can never drift from the
                    // server slot under a concurrent start/stop — "advertise iff running" holds for the wire
                    // advert AND the UI state, not just by timing.
                    if (a2aConflictsWithWebServer(port, webServerManager.state.value)) {
                        error("Port $port is already used by the web server")
                    }
                    if (!isPortAvailable(port)) error("Port $port is already in use")
                    val server = startKtorServerNoSpa(port = port, host = host) {
                        configureA2aApi(
                            appScope = appScope,
                            chatService = chatService,
                            settingsStore = settingsStore,
                            a2aTaskRegistry = a2aTaskRegistry,
                            // The agent card's RPC url is derived from THIS server's bind config — never the
                            // inbound Host header — so a poisoned Host can't redirect a token-bearing client.
                            cardBaseUrl = { a2aCardBaseUrl(localhostOnly = localhostOnly, lanIp = _state.value.address, port = port) },
                        )
                    }.start(wait = false)
                    val registered = registerNsd(port, localhostOnly)
                    _state.value = baseState.copy(
                        isRunning = true,
                        hostname = registered?.hostname,
                        address = registered?.address?.hostAddress,
                    )
                    server
                }
                if (started == null) {
                    _state.value = _state.value.copy(isLoading = false)
                    Log.w(TAG, "A2A server already running")
                    return@launch
                }
                _state.value = _state.value.copy(isLoading = false)
                Log.i(TAG, "A2A server started on $host:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start A2A server", e)
                // The factory threw before its running-publish, so the slot is still empty and isRunning is
                // already false; only flip the transient loading/error fields. Never write
                // isRunning/hostname/address outside the locked transition (#415).
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun stop() {
        // Only the transient loading hint is flipped outside the lock; the authoritative
        // isRunning/hostname/address transition happens INSIDE onStop so it can never race a concurrent
        // start's publish (#415).
        _state.value = _state.value.copy(isLoading = true, error = null)
        appScope.launch {
            try {
                lifecycle.stop { server ->
                    // Tear down RESILIENTLY: a failing server.stop() must not skip the advert unregister or
                    // the stopped-state publish, and must not throw out of onStop — if it did,
                    // WebServerLifecycle would leave the slot un-cleared and the advert/state would split
                    // from the (dead) server (#415).
                    runCatching { server.stop(1000, 2000) }
                        .onFailure { Log.w(TAG, "A2A server stop failed", it) }
                    runCatching { nsdRegistrar.unregister() }
                        .onFailure { Log.w(TAG, "NSD unregister failed", it) }
                    _state.value = _state.value.copy(
                        isRunning = false, hostname = null, address = null, url = null
                    )
                }
                _state.value = _state.value.copy(isLoading = false)
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
                        // Resilient teardown (mirrors stop): a failing server.stop() must not skip the
                        // unregister, abort the restart, or leak the old advert (#415).
                        runCatching { server.stop(1000, 2000) }
                            .onFailure { Log.w(TAG, "A2A server stop failed", it) }
                        runCatching { nsdRegistrar.unregister() }
                            .onFailure { Log.w(TAG, "NSD unregister failed", it) }
                        // Publish the stopped state INSIDE the lock (mirrors stop): the factory below
                        // re-publishes running on success, but if it throws the catch can preserve this
                        // stopped state instead of writing isRunning/hostname/address outside the lock
                        // (#415). The intermediate stopped state is covered by isLoading = true (NONE).
                        _state.value = _state.value.copy(
                            isRunning = false, hostname = null, address = null, url = null
                        )
                    },
                    factory = {
                        if (a2aConflictsWithWebServer(port, webServerManager.state.value)) {
                            error("Port $port is already used by the web server")
                        }
                        if (!isPortAvailable(port)) error("Port $port is already in use")
                        val server = startKtorServerNoSpa(port = port, host = host) {
                            configureA2aApi(
                                appScope = appScope,
                                chatService = chatService,
                                settingsStore = settingsStore,
                                a2aTaskRegistry = a2aTaskRegistry,
                                // Card url from THIS server's bind config, never the inbound Host header.
                                cardBaseUrl = { a2aCardBaseUrl(localhostOnly = localhostOnly, lanIp = _state.value.address, port = port) },
                            )
                        }.start(wait = false)
                        // Register the advert AND publish the running state INSIDE the locked transition
                        // (mirrors start) so neither can drift from the server slot (#415).
                        val registered = registerNsd(port, localhostOnly)
                        _state.value = baseState.copy(
                            isRunning = true,
                            hostname = registered?.hostname,
                            address = registered?.address?.hostAddress,
                        )
                        server
                    },
                )
                _state.value = _state.value.copy(isLoading = false)
                Log.i(TAG, "A2A server restarted on $host:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart A2A server", e)
                // onStop already published the stopped state under the lock (or the server was never
                // running), so isRunning/hostname/address are correct; only flip loading/error here.
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    /**
     * Register the A2A service via mDNS — LAN mode only (localhost has no peers to discover it). The host
     * label is device-unique (`poci-a2a-<model>-<idhash>.local`) so two devices never collide; the
     * service instance carries the human device name; TXT advertises the agent-card/RPC paths (NOT the
     * bearer token). mDNS advertises reachability only — the route-level bearer gate still decides access.
     *
     * Returns the registered service info (null in localhost mode or on failure). Called INSIDE the
     * [WebServerLifecycle] transition (#415) so the advert is serialized with the server; the caller sets
     * the observable hostname/address from the return value (also inside the locked transition) — this no
     * longer touches [_state] itself via a callback.
     */
    private suspend fun registerNsd(port: Int, localhostOnly: Boolean): RegisteredServiceInfo? {
        if (localhostOnly) return null
        val identity = deviceMdnsIdentity(context.contentResolver)
        var registered: RegisteredServiceInfo? = null
        runCatching {
            nsdRegistrar.register(
                port = port,
                hostLabel = mdnsHostLabel(prefix = "poci-a2a", model = identity.modelSlug, idHash = identity.idHash),
                instanceName = serviceInstanceName(kind = "A2A", displayName = identity.displayName),
                description = "Poci A2A Agent",
                txt = a2aTxtRecord(),
                onRegistered = { registered = it },
            )
        }.onFailure {
            Log.w(TAG, "A2A NSD register failed", it)
        }
        return registered
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
