package me.rerere.rikkahub.web

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
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.web.startWebServer

private const val TAG = "WebServerManager"

data class WebServerState(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val error: String? = null
)

class WebServerManager(
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    fun start(port: Int = 8080) {
        if (server != null) {
            Log.w(TAG, "Server already running")
            return
        }

        scope.launch {
            try {
                Log.i(TAG, "Starting web server on port $port")
                server = startWebServer(port = port) {
                    configureWebApi(chatService, conversationRepo, settingsStore)
                }.start(wait = false)

                _state.value = WebServerState(isRunning = true, port = port)
                Log.i(TAG, "Web server started successfully on port $port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start web server", e)
                _state.value = WebServerState(isRunning = false, error = e.message)
            }
        }
    }

    fun stop() {
        scope.launch {
            try {
                Log.i(TAG, "Stopping web server")
                server?.stop(1000, 2000)
                server = null
                _state.value = WebServerState(isRunning = false)
                Log.i(TAG, "Web server stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop web server", e)
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    fun restart(port: Int = _state.value.port) {
        stop()
        start(port)
    }
}
