package me.rerere.common.http

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import me.rerere.common.http.sse.newEventSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

/**
 * 代表 SSE 连接中的各种事件
 */
sealed class SseEvent {
    /**
     * 连接成功打开
     */
    data object Open : SseEvent()

    /**
     * 收到一个具体事件
     * @param id 事件ID
     * @param type 事件类型
     * @param data 事件数据
     */
    data class Event(val id: String?, val type: String?, val data: String) : SseEvent()

    /**
     * 连接被关闭
     */
    data object Closed : SseEvent()

    /**
     * 发生错误
     * @param throwable 异常信息
     * @param response 错误时的响应（可能为null）
     */
    data class Failure(val throwable: Throwable?, val response: Response?) : SseEvent()
}

/**
 * 为 OkHttpClient 创建 SSE (Server-Sent Events) 连接的扩展函数
 *
 * 将 OkHttp 的 EventSource 封装成 Kotlin Flow，提供响应式的 SSE 事件流
 *
 * @param request HTTP 请求，用于建立 SSE 连接
 * @return Flow<SseEvent> 包含 SSE 事件的响应式流
 */
fun OkHttpClient.sseFlow(request: Request): Flow<SseEvent> {
    return callbackFlow {
        // 1. 创建 EventSourceListener
        // 监听 SSE 连接的各种事件并转换为 Flow 事件
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                // 从回调中安全地发送事件到 Flow
                // 连接成功建立时触发
                trySend(SseEvent.Open)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                // 收到服务器发送的数据事件时触发
                // 将事件数据封装后发送到 Flow
                trySend(SseEvent.Event(id, type, data))
            }

            override fun onClosed(eventSource: EventSource) {
                // 连接正常关闭时触发
                trySend(SseEvent.Closed)
                channel.close() // 关闭 Flow 通道
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // 连接发生错误时触发
                trySend(SseEvent.Failure(t, response))
                channel.close(t) // 以异常关闭 Flow 通道
            }
        }

        // 2. 创建 EventSource
        // 使用当前 OkHttpClient 创建 EventSource 工厂
        val eventSource = this@sseFlow.newEventSource(request, listener)

        // 3. awaitClose 用于在 Flow 被取消时执行清理操作
        // 当收集 Flow 的协程被取消时，这个块会被调用
        awaitClose {
            // 关闭 SSE 连接，释放资源
            eventSource.cancel()
        }
    }
}
