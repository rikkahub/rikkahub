package me.rerere.tts.provider

/**
 * TTS Provider 返回的 HTTP 错误。保留状态码供上层判断是否值得重试。
 */
class TTSProviderException(
    message: String,
    val statusCode: Int,
    cause: Throwable? = null
) : Exception(message, cause) {
    val isRetryable: Boolean
        get() = statusCode == 408 || statusCode == 429 || statusCode in 500..599
}
