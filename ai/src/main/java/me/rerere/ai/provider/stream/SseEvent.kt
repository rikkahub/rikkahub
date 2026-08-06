package me.rerere.ai.provider.stream

import kotlinx.serialization.Serializable

/**
 * 与具体 HTTP 客户端无关的 Server-Sent Event。
 *
 * [event] 对应 SSE 的 `event` 字段；Provider 响应 JSON 中的 `type` 仍保留在 [data] 内。
 * 轨迹测试记录这一层，因此可以在 OkHttp、Ktor 或其他传输实现之间复用。
 */
@Serializable
data class SseEvent(
    val id: String? = null,
    val event: String? = null,
    val data: String,
    val retryMillis: Long? = null,
)
