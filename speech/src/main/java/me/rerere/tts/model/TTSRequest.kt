package me.rerere.tts.model

import kotlinx.serialization.Serializable

@Serializable
data class TTSRequest(
    val text: String
)

@Serializable
enum class AudioFormat {
    MP3,
    WAV,
    OGG,
    AAC,
    OPUS,
    PCM
}