package me.rerere.ai.core

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
)

/**
 * 合并同一次请求内的 usage 快照：各字段取最新的非零值。
 * 跨请求（如多轮工具调用的各个步骤）累计请用 [sum]。
 */
fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    val totalTokens = promptTokens + completionTokens
    val cachedTokens = if (other.cachedTokens > 0) {
        other.cachedTokens
    } else {
        this?.cachedTokens ?: 0
    }
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens
    )
}

/**
 * 累加多次请求的 usage，每次请求都会按完整上下文计费，因此 prompt 也直接相加。
 */
fun TokenUsage?.sum(other: TokenUsage?): TokenUsage? {
    if (this == null) return other
    if (other == null) return this
    return TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        totalTokens = totalTokens + other.totalTokens,
    )
}
