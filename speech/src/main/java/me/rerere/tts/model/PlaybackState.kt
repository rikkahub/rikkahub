package me.rerere.tts.model

/**
 * 统一的播放状态（对外暴露给 app 侧使用）。
 */
enum class PlaybackStatus {
    Idle,
    Buffering,
    Playing,
    Paused,
    Ended,
    Error
}

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val currentChunkIndex: Int = 0, // 1-based，与 currentChunk StateFlow 对齐
    val totalChunks: Int = 0,
    val errorMessage: String? = null
)


