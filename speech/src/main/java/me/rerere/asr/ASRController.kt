package me.rerere.asr

import kotlinx.coroutines.flow.StateFlow

interface ASRController {
    val state: StateFlow<ASRState>
    fun start(onTranscriptChange: (String) -> Unit)
    fun stop()
    /** Stop microphone capture while keeping the connection open for the final transcript. */
    fun pauseCapture() {}
    fun dispose()
}
