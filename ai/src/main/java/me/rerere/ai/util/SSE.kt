package me.rerere.ai.util

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.stripBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.internal.ServerSentEventReader
import java.io.IOException

class SSEEventSource(
    private val request: Request,
    private val listener: EventSourceListener,
    private val allowMissingContentType: Boolean = false,
) : EventSource,
    ServerSentEventReader.Callback,
    Callback {
    private var call: Call? = null

    @Volatile
    private var canceled = false

    fun connect(callFactory: Call.Factory) {
        call =
            callFactory.newCall(request).apply {
                enqueue(this@SSEEventSource)
            }
    }

    override fun onResponse(
        call: Call,
        response: Response,
    ) {
        processResponse(response)
    }

    fun processResponse(response: Response) {
        response.use {
            if (!response.isSuccessful) {
                listener.onFailure(this, null, response)
                return
            }

            val body = response.body

            if (!body.isEventStream() && !(allowMissingContentType && body.contentType() == null)) {
                listener.onFailure(
                    this,
                    IllegalStateException("Invalid content-type: ${body.contentType()}"),
                    response,
                )
                return
            }

            // This is a long-lived response. Cancel full-call timeouts.
            call?.timeout()?.cancel()

            // Replace the body with a stripped one so the callbacks can't see real data.
            val response = response.stripBody()

            val reader = ServerSentEventReader(body.source(), this)
            try {
                if (!canceled) {
                    listener.onOpen(this, response)
                    while (!canceled && reader.processNextEvent()) {
                    }
                }
            } catch (e: Exception) {
                val exception =
                    when {
                        canceled -> IOException("canceled", e)
                        else -> e
                    }
                listener.onFailure(this, exception, response)
                return
            }
            if (canceled) {
                listener.onFailure(this, IOException("canceled"), response)
            } else {
                listener.onClosed(this)
            }
        }
    }

    private fun ResponseBody.isEventStream(): Boolean {
        val contentType = contentType() ?: return false
        return contentType.type == "text" && contentType.subtype == "event-stream"
    }

    override fun onFailure(
        call: Call,
        e: IOException,
    ) {
        listener.onFailure(this, e, null)
    }

    override fun request(): Request = request

    override fun cancel() {
        canceled = true
        call?.cancel()
    }

    override fun onEvent(
        id: String?,
        type: String?,
        data: String,
    ) {
        listener.onEvent(this, id, type, data)
    }

    override fun onRetryChange(timeMs: Long) {
        // Ignored. We do not auto-retry.
    }

    companion object {
        fun factory(
            callFactory: Call.Factory,
            allowMissingContentType: Boolean = false,
        ) = EventSource.Factory { request, listener ->
            val actualRequest =
                if (request.header("Accept") == null) {
                    request.newBuilder().addHeader("Accept", "text/event-stream").build()
                } else {
                    request
                }

            SSEEventSource(
                request = actualRequest,
                listener = listener,
                allowMissingContentType = allowMissingContentType,
            ).apply {
                connect(callFactory)
            }
        }
    }
}
