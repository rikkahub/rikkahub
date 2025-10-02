package me.rerere.rikkahub.data.ai.mcp.transport

import android.util.Log
import io.ktor.http.URLBuilder
import io.ktor.http.path
import io.ktor.http.takeFrom
import io.modelcontextprotocol.kotlin.sdk.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.common.http.await
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.mcp.McpJson
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private const val TAG = "SseClientTransport"

@OptIn(ExperimentalAtomicApi::class)
internal class SseClientTransport(
    private val client: OkHttpClient,
    private val urlString: String,
    private val headers: List<Pair<String, String>>,
) : AbstractTransport() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventSourceFactory = EventSources.createFactory(client)
    private val initialized: AtomicBoolean = AtomicBoolean(false)
    private var session: EventSource? = null
    private val endpoint = CompletableDeferred<String>()

    private var job: Job? = null

    private val baseUrl by lazy {
        URLBuilder()
            .takeFrom(urlString)
            .apply {
                path() // set path to empty
                parameters.clear() //  clear parameters
            }
            .build()
            .toString()
            .trimEnd('/')
    }

    override suspend fun start() {
        if (!initialized.compareAndSet(false, true)) {
            error(
                "SSEClientTransport already started! " +
                    "If using Client class, note that connect() calls start() automatically.",
            )
        }

        session = eventSourceFactory.newEventSource(
            request = Request.Builder()
                .url(urlString)
                .headers(
                    Headers.Builder()
                        .apply {
                            for ((key, value) in headers) {
                                add(key, value)
                            }
                        }
                        .build()
                )
                .addHeader("Accept", "text/event-stream")
                .addHeader("User-Agent", "RikkaHub/${BuildConfig.VERSION_NAME}")
                .build(),
            listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    super.onOpen(eventSource, response)
                    Log.i(TAG, "onOpen: $urlString")
                }

                override fun onClosed(eventSource: EventSource) {
                    super.onClosed(eventSource)
                    Log.i(TAG, "onClosed: $urlString")
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    super.onFailure(eventSource, t, response)
                    t?.printStackTrace()
                    Log.i(TAG, "onFailure: $urlString / $t / $baseUrl")
                    endpoint.completeExceptionally(t ?: Exception("SSE Failure"))
                    _onError(t ?: Exception("SSE Failure"))
                    _onClose()
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    Log.i(TAG, "onEvent($baseUrl):  #$id($type) - $data")
                    when (type) {
                        "error" -> {
                            val e = IllegalStateException("SSE error: $data")
                            _onError(e)
                        }

                        "open" -> {
                            // The connection is open, but we need to wait for the endpoint to be received.
                        }

                        "endpoint" -> {
                            val endpointData =
                                if (data.startsWith("http://") || data.startsWith("https://")) {
                                    // 绝对路径，直接使用
                                    data
                                } else {
                                    // 相对路径，加上baseUrl
                                    baseUrl + if (data.startsWith("/")) data else "/$data"
                                }
                            Log.i(TAG, "onEvent: endpoint: $endpointData")
                            endpoint.complete(endpointData)
                        }

                        else -> {
                            scope.launch {
                                try {
                                    val message = McpJson.decodeFromString<JSONRPCMessage>(data)
                                    _onMessage(message)
                                } catch (e: Exception) {
                                    _onError(e)
                                }
                            }
                        }
                    }
                }
            }
        )
        withTimeout(30000) {
            endpoint.await()
            Log.i(TAG, "start: Connected to endpoint ${endpoint.getCompleted()}")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun send(message: JSONRPCMessage) {
        if (!endpoint.isCompleted) {
            error("Not connected")
        }

        Log.i(TAG, "send: POSTing to endpoint ${endpoint.getCompleted()} - $message")

        try {
            val request = Request.Builder()
                .url(endpoint.getCompleted())
                .apply {
                    for ((key, value) in headers) {
                        addHeader(key, value)
                    }
                }
                .post(
                    McpJson.encodeToString(message).toRequestBody(
                        contentType = "application/json".toMediaType(),
                    )
                )
                .build()
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                val text = response.body.string()
                error("Error POSTing to endpoint ${endpoint.getCompleted()} (HTTP ${response.code}): $text")
            } else {
                Log.i(TAG, "send: POST to endpoint ${endpoint.getCompleted()} successful")
            }
        } catch (e: Exception) {
            _onError(e)
            throw e
        }
    }

    override suspend fun close() {
        if (!initialized.load()) {
            error("SSEClientTransport is not initialized!")
        }

        session?.cancel()
        _onClose()
        job?.cancelAndJoin()
    }
}
