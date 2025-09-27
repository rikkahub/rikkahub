package me.rerere.rikkahub.data.ai.mcp.transport

import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.RequestId
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.McpJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.rerere.common.http.SseEvent
import me.rerere.common.http.sseFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "StreamableHttpClientTra"

private const val MCP_SESSION_ID_HEADER = "mcp-session-id"
private const val MCP_PROTOCOL_VERSION_HEADER = "mcp-protocol-version"
private const val MCP_RESUMPTION_TOKEN_HEADER = "Last-Event-ID"

class StreamableHttpError(
    val code: Int? = null,
    message: String? = null
) : Exception("Streamable HTTP error: $message")

@OptIn(ExperimentalAtomicApi::class)
class StreamableHttpClientTransport(
    private val client: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
) : AbstractTransport() {
    var sessionId: String? = null
        private set
    var protocolVersion: String? = null

    private val initialized: AtomicBoolean = AtomicBoolean(false)

    private var sseJob: Job? = null

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    private var lastEventId: String? = null

    override suspend fun start() {
        if (!initialized.compareAndSet(expectedValue = false, newValue = true)) {
            error("StreamableHttpClientTransport already started!")
        }
        Log.d(TAG, "start: Client transport starting...")
    }

    /**
     * Sends a single message with optional resumption support
     */
    override suspend fun send(message: JSONRPCMessage) {
        send(message, null)
    }

    /**
     * Sends one or more messages with optional resumption support.
     * This is the main send method that matches the TypeScript implementation.
     */
    suspend fun send(
        message: JSONRPCMessage,
        resumptionToken: String?,
        onResumptionToken: ((String) -> Unit)? = null
    ) {
        Log.d(
            TAG,
            "send: Client sending message via POST to $url: ${McpJson.encodeToString(message)}"
        )

        // If we have a resumption token, reconnect the SSE stream with it
        resumptionToken?.let { token ->
            startSseSession(
                resumptionToken = token,
                onResumptionToken = onResumptionToken,
                replayMessageId = if (message is JSONRPCRequest) message.id else null
            )
            return
        }

        val jsonBody = McpJson.encodeToString(message)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .apply {
                applyCommonHeaders(this)
                addHeader("Accept", "application/json, text/event-stream")
            }
            .build()

        val response = suspendCancellableCoroutine<Response> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }
            })
        }

        response.use { resp ->
            resp.header(MCP_SESSION_ID_HEADER)?.let {
                sessionId = it
            }

            if (resp.code == 202) { // HTTP_ACCEPTED
                if (message is JSONRPCNotification && message.method == "notifications/initialized") {
                    startSseSession(onResumptionToken = onResumptionToken)
                }
                return
            }

            if (!resp.isSuccessful) {
                val error = StreamableHttpError(resp.code, resp.body?.string())
                _onError(error)
                throw error
            }

            val contentType = resp.header("Content-Type")
            when {
                contentType?.startsWith("application/json") == true -> {
                    val body = resp.body?.string()
                    if (!body.isNullOrEmpty()) {
                        runCatching { McpJson.decodeFromString<JSONRPCMessage>(body) }
                            .onSuccess { _onMessage(it) }
                            .onFailure(_onError)
                    }
                }

                contentType?.startsWith("text/event-stream") == true -> {
                    handleInlineSse(
                        resp, onResumptionToken = onResumptionToken,
                        replayMessageId = if (message is JSONRPCRequest) message.id else null
                    )
                }

                else -> {
                    val body = resp.body?.string() ?: ""
                    if (contentType == null && body.isBlank()) return

                    val ct = contentType ?: "<none>"
                    val error = StreamableHttpError(-1, "Unexpected content type: $ct")
                    _onError(error)
                    throw error
                }
            }
        }
    }

    override suspend fun close() {
        if (!initialized.load()) return // Already closed or never started
        Log.d(TAG, "close: Client transport closing.")

        try {
            // Try to terminate session if we have one
            terminateSession()

            sseJob?.cancelAndJoin()
            scope.cancel()
        } catch (_: Exception) {
            // Ignore errors during cleanup
        } finally {
            initialized.store(false)
            _onClose()
        }
    }

    /**
     * Terminates the current session by sending a DELETE request to the server.
     */
    suspend fun terminateSession() {
        if (sessionId == null) return
        Log.d(TAG, "terminateSession: Terminating session: $sessionId")

        val request = Request.Builder()
            .url(url)
            .delete()
            .apply { applyCommonHeaders(this) }
            .build()

        val response = suspendCancellableCoroutine<Response> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }
            })
        }

        response.use { resp ->
            // 405 means server doesn't support explicit session termination
            if (!resp.isSuccessful && resp.code != 405) {
                val error = StreamableHttpError(
                    resp.code,
                    "Failed to terminate session: ${resp.message}"
                )
                Log.e(TAG, "Failed to terminate session", error)
                _onError(error)
                throw error
            }
        }

        sessionId = null
        lastEventId = null
        Log.d(TAG, "Session terminated successfully")
    }

    private suspend fun startSseSession(
        resumptionToken: String? = null,
        replayMessageId: RequestId? = null,
        onResumptionToken: ((String) -> Unit)? = null
    ) {
        sseJob?.cancelAndJoin()

        Log.d(TAG, "startSseSession: Client attempting to start SSE session at url: $url")

        val request = Request.Builder()
            .url(url)
            .get()
            .apply {
                applyCommonHeaders(this)
                addHeader("Accept", "text/event-stream")
                (resumptionToken ?: lastEventId)?.let {
                    addHeader(MCP_RESUMPTION_TOKEN_HEADER, it)
                }
            }
            .build()

        sseJob = client.sseFlow(request)
            .onEach { event ->
                when (event) {
                    is SseEvent.Open -> {
                        Log.d(TAG, "startSseSession: Client SSE session started successfully.")
                    }

                    is SseEvent.Event -> {
                        event.id?.let {
                            lastEventId = it
                            onResumptionToken?.invoke(it)
                        }
                        Log.d(
                            TAG,
                            "collectSse: Client received SSE event: event=${event.type}, data=${event.data}, id=${event.id}"
                        )

                        when (event.type) {
                            null, "message" -> {
                                if (event.data.isNotEmpty()) {
                                    runCatching { McpJson.decodeFromString<JSONRPCMessage>(event.data) }
                                        .onSuccess { msg ->
                                            scope.launch {
                                                if (replayMessageId != null && msg is JSONRPCResponse) {
                                                    _onMessage(msg.copy(id = replayMessageId))
                                                } else {
                                                    _onMessage(msg)
                                                }
                                            }
                                        }
                                        .onFailure(_onError)
                                }
                            }

                            "error" -> _onError(StreamableHttpError(null, event.data))
                        }
                    }

                    is SseEvent.Closed -> {
                        Log.d(TAG, "startSseSession: SSE connection closed")
                    }

                    is SseEvent.Failure -> {
                        if (event.response?.code == 405) {
                            Log.i(TAG, "startSseSession: Server returned 405 for GET/SSE, stream disabled.")
                            return@onEach
                        }
                        event.throwable?.let { _onError(it) }
                    }
                }
            }
            .catch { throwable ->
                Log.e(TAG, "SSE flow error", throwable)
                _onError(throwable)
            }
            .launchIn(scope)
    }

    private fun applyCommonHeaders(builder: Request.Builder) {
        sessionId?.let { builder.addHeader(MCP_SESSION_ID_HEADER, it) }
        protocolVersion?.let { builder.addHeader(MCP_PROTOCOL_VERSION_HEADER, it) }
        headers.forEach { (name, value) ->
            builder.addHeader(name, value)
        }
    }


    private suspend fun handleInlineSse(
        response: Response,
        replayMessageId: RequestId?,
        onResumptionToken: ((String) -> Unit)?
    ) {
        Log.d(TAG, "handleInlineSse: Handling inline SSE from POST response")
        val body = response.body ?: return

        val sb = StringBuilder()
        var id: String? = null
        var eventName: String? = null

        fun dispatch(data: String) {
            id?.let {
                lastEventId = it
                onResumptionToken?.invoke(it)
            }
            if (eventName == null || eventName == "message") {
                runCatching { McpJson.decodeFromString<JSONRPCMessage>(data) }
                    .onSuccess { msg ->
                        scope.launch {
                            if (replayMessageId != null && msg is JSONRPCResponse) {
                                _onMessage(msg.copy(id = replayMessageId))
                            } else {
                                _onMessage(msg)
                            }
                        }
                    }
                    .onFailure(_onError)
            }
            // reset
            id = null
            eventName = null
            sb.clear()
        }

        body.source().use { source ->
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) {
                    dispatch(sb.toString())
                    continue
                }
                when {
                    line.startsWith("id:") -> id = line.substringAfter("id:").trim()
                    line.startsWith("event:") -> eventName = line.substringAfter("event:").trim()
                    line.startsWith("data:") -> sb.append(line.substringAfter("data:").trim())
                }
            }
        }
    }
}
