/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// 抄自 okhttp-sse 5.5.0 的 okhttp3.sse.internal.RealEventSource / EventSources，
// 以及 okhttp 5.5.0 的 okhttp3.internal.UnreadableResponseBody。改动：
// - 响应没有 Content-Type 时按事件流处理（ChatGPT 的 Codex 后端不带这个头）；
//   明确声明了其他类型（比如 200 但返回 JSON 错误体）时仍然判失败
// - 不再依赖 OkHttp 的内部 API
package me.rerere.common.http.sse

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException

/**
 * 发起一个 SSE 请求，用法同 `EventSources.createFactory(client).newEventSource(request, listener)`。
 *
 * 请求没有 Accept 头时补上 `text/event-stream`。
 */
fun Call.Factory.newEventSource(
    request: Request,
    listener: EventSourceListener,
): EventSource {
    val actualRequest =
        if (request.header("Accept") == null) {
            request.newBuilder().addHeader("Accept", "text/event-stream").build()
        } else {
            request
        }

    return RealEventSource(actualRequest, listener).apply {
        connect(this@newEventSource)
    }
}

internal class RealEventSource(
    private val request: Request,
    private val listener: EventSourceListener,
) : EventSource,
    ServerSentEventReader.Callback,
    Callback {
    private var call: Call? = null

    @Volatile
    private var canceled = false

    fun connect(callFactory: Call.Factory) {
        call =
            callFactory.newCall(request).apply {
                enqueue(this@RealEventSource)
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

            if (!body.isEventStream()) {
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

    // 原版要求必须是 text/event-stream；这里没有 Content-Type 也接受
    private fun ResponseBody.isEventStream(): Boolean {
        val contentType = contentType() ?: return true
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
}

private fun Response.stripBody(): Response =
    newBuilder()
        .body(UnreadableResponseBody(body.contentType(), body.contentLength()))
        .build()

private class UnreadableResponseBody(
    private val mediaType: MediaType?,
    private val contentLength: Long,
) : ResponseBody(),
    Source {
    override fun contentType() = mediaType

    override fun contentLength() = contentLength

    override fun source() = buffer()

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long =
        throw IllegalStateException(
            """
            |Unreadable ResponseBody! These Response objects have bodies that are stripped:
            | * EventSourceListener
            |(It is safe to call contentType() and contentLength() on these response bodies.)
            """.trimMargin(),
        )

    override fun timeout() = Timeout.NONE

    override fun close() {
    }
}
