package me.rerere.asr

enum class ASRStatus {
    Idle,
    Connecting,
    Listening,
    Stopping,
    Error
}

data class ASRState(
    val status: ASRStatus = ASRStatus.Idle,
    val isAvailable: Boolean = false,
    val transcript: String = "",
    val errorMessage: String? = null,
    val amplitudes: List<Float> = emptyList(),
) {
    val isRecording: Boolean
        get() = status == ASRStatus.Connecting || status == ASRStatus.Listening || status == ASRStatus.Stopping
}
