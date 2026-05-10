package me.rerere.tts.model

import kotlinx.serialization.Serializable

@Serializable
data class TTSResponse(
    val audioData: ByteArray,
    val format: AudioFormat,
    val sampleRate: Int? = null,
    val duration: Float? = null,
    val metadata: Map<String, String> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TTSResponse

        if (!audioData.contentEquals(other.audioData)) return false
        if (format != other.format) return false
        if (sampleRate != other.sampleRate) return false
        if (duration != other.duration) return false
        if (metadata != other.metadata) return false

        return true
    }

    override fun hashCode(): Int {
        var result = audioData.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + (sampleRate ?: 0)
        result = 31 * result + (duration?.hashCode() ?: 0)
        result = 31 * result + metadata.hashCode()
        return result
    }
}

@Serializable
data class AudioChunk(
    val data: ByteArray,
    val format: AudioFormat,
    val sampleRate: Int? = null,
    val isLast: Boolean = false,
    val metadata: Map<String, String> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioChunk

        if (!data.contentEquals(other.data)) return false
        if (format != other.format) return false
        if (sampleRate != other.sampleRate) return false
        if (isLast != other.isLast) return false
        if (metadata != other.metadata) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + (sampleRate ?: 0)
        result = 31 * result + isLast.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }
}

